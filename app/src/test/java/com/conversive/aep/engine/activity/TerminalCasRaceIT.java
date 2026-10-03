package com.conversive.aep.engine.activity;

import static org.assertj.core.api.Assertions.assertThat;

import com.conversive.aep.common.ExecutionId;
import com.conversive.aep.common.ExecutionMode;
import com.conversive.aep.common.Priority;
import com.conversive.aep.common.TenantId;
import com.conversive.aep.definition.StoredDefinition;
import com.conversive.aep.definition.model.DefinitionCodec;
import com.conversive.aep.definition.persistence.DefinitionRepository;
import com.conversive.aep.engine.activity.ExecutionStateActivity.Transition;
import com.conversive.aep.engine.activity.ExecutionStateActivity.TransitionResult;
import com.conversive.aep.execution.ExecutionStatus;
import com.conversive.aep.execution.persistence.ExecutionRecord;
import com.conversive.aep.execution.persistence.ExecutionRepository;
import com.conversive.aep.execution.persistence.NewExecution;
import com.conversive.aep.support.Fixtures;
import com.conversive.aep.support.PostgresIntegrationTest;
import com.conversive.aep.support.TestTenants;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Ten concurrent terminal CAS writers: exactly one wins and the terminal row is never overwritten. */
class TerminalCasRaceIT extends PostgresIntegrationTest {

    private static final int THREADS = 10;
    private static final Set<ExecutionStatus> LIVE =
            Set.of(ExecutionStatus.QUEUED, ExecutionStatus.RUNNING, ExecutionStatus.COMPENSATING);
    private static final List<ExecutionStatus> TERMINALS = List.of(ExecutionStatus.SUCCEEDED,
            ExecutionStatus.FAILED, ExecutionStatus.CANCELLED, ExecutionStatus.TIMED_OUT);

    @Autowired
    ExecutionStateActivityImpl activity;
    @Autowired
    ExecutionRepository repository;
    @Autowired
    DefinitionRepository definitions;
    @Autowired
    JdbcClient jdbc;

    @Test
    void tenConcurrentTerminalTransitionsLeaveExactlyOneTerminalState() throws Exception {
        TenantId tenant = new TestTenants(jdbc).create("t_race");
        ExecutionId id = runningExecution(tenant);
        CyclicBarrier barrier = new CyclicBarrier(THREADS);
        List<Callable<TransitionResult>> writers = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            ExecutionStatus to = TERMINALS.get(i % TERMINALS.size());
            writers.add(() -> {
                barrier.await();
                return activity.transition(new Transition(tenant, id, LIVE, to, "E" + to, null, null));
            });
        }

        List<TransitionResult> results = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            for (Future<TransitionResult> f : pool.invokeAll(writers)) {
                results.add(f.get());
            }
        } finally {
            pool.shutdownNow();
        }

        List<TransitionResult> winners = results.stream().filter(TransitionResult::applied).toList();
        assertThat(winners).hasSize(1);
        ExecutionStatus winner = winners.get(0).current();
        ExecutionRecord row = repository.findById(tenant, id).orElseThrow();
        assertThat(row.status()).isEqualTo(winner);
        assertThat(row.errorCode()).isEqualTo("E" + winner);
        assertThat(row.rowVersion()).isEqualTo(2);
        assertThat(results).allSatisfy(r -> assertThat(r.current()).isEqualTo(winner));

        TransitionResult late = activity.transition(
                new Transition(tenant, id, LIVE, ExecutionStatus.COMPENSATED, null, null, null));
        assertThat(late.applied()).isFalse();
        assertThat(repository.findById(tenant, id).orElseThrow().status()).isEqualTo(winner);
    }

    private ExecutionId runningExecution(TenantId tenant) {
        var spec = Fixtures.pdfExample();
        definitions.publish(new StoredDefinition(tenant, "lead_enrichment", 1, spec, DefinitionCodec.sha256(spec),
                null));
        Instant now = Instant.now();
        NewExecution e = new NewExecution(ExecutionId.random(), tenant, "lead_enrichment", 1, ExecutionMode.LIVE,
                Priority.NORMAL, "k-" + UUID.randomUUID(), spec.path("nodes"), null, now, now.plusSeconds(3600));
        assertThat(repository.insert(e)).isTrue();
        assertThat(activity.transition(new Transition(tenant, e.id(), Set.of(ExecutionStatus.QUEUED),
                ExecutionStatus.RUNNING, null, null, null)).applied()).isTrue();
        return e.id();
    }
}
