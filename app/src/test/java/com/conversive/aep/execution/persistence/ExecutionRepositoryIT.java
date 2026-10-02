package com.conversive.aep.execution.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.conversive.aep.common.ExecutionId;
import com.conversive.aep.common.ExecutionMode;
import com.conversive.aep.common.Priority;
import com.conversive.aep.common.TenantId;
import com.conversive.aep.definition.StoredDefinition;
import com.conversive.aep.definition.model.DefinitionCodec;
import com.conversive.aep.definition.persistence.DefinitionRepository;
import com.conversive.aep.execution.ExecutionStatus;
import com.conversive.aep.support.Fixtures;
import com.conversive.aep.support.PostgresIntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ExecutionRepositoryIT extends PostgresIntegrationTest {

    private static final TenantId DEV = new TenantId("t_dev");
    private static final TenantId OTHER = new TenantId("t_other");
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    @Autowired
    ExecutionRepository repository;
    @Autowired
    DefinitionRepository definitions;

    @BeforeEach
    void publishDefinition() {
        var spec = Fixtures.pdfExample();
        definitions.publish(new StoredDefinition(DEV, "lead_enrichment", 1, spec, DefinitionCodec.sha256(spec), null));
    }

    private NewExecution newExecution(String key) {
        return new NewExecution(ExecutionId.random(), DEV, "lead_enrichment", 1, ExecutionMode.LIVE, Priority.NORMAL,
                key, Fixtures.pdfExample().path("nodes"), null, NOW, NOW.plusSeconds(3600));
    }

    @Test
    void insertsQueuedAndFindsByIdAndKeyWithinTheTenantOnly() {
        NewExecution e = newExecution("k-" + UUID.randomUUID());

        assertThat(repository.insert(e)).isTrue();

        ExecutionRecord stored = repository.findById(DEV, e.id()).orElseThrow();
        assertThat(stored.status()).isEqualTo(ExecutionStatus.QUEUED);
        assertThat(stored.rowVersion()).isZero();
        assertThat(stored.deadlineAt()).isEqualTo(e.deadlineAt());
        assertThat(stored.input()).isEqualTo(e.input());
        assertThat(repository.findByIdempotencyKey(DEV, e.idempotencyKey())).map(ExecutionRecord::id).contains(e.id());
        assertThat(repository.findById(OTHER, e.id())).isEmpty();
        assertThat(repository.findByIdempotencyKey(OTHER, e.idempotencyKey())).isEmpty();
    }

    @Test
    void duplicateIdempotencyKeyInsertsNothing() {
        String key = "k-" + UUID.randomUUID();
        NewExecution first = newExecution(key);
        NewExecution second = newExecution(key);

        assertThat(repository.insert(first)).isTrue();
        assertThat(repository.insert(second)).isFalse();

        assertThat(repository.findById(DEV, second.id())).isEmpty();
    }

    @Test
    void casTransitionsOnlyFromAllowedStatusesAndBumpsRowVersion() {
        NewExecution e = newExecution("k-" + UUID.randomUUID());
        repository.insert(e);
        Instant later = NOW.plusSeconds(5);

        assertThat(repository.cas(DEV, e.id(), EnumSet.of(ExecutionStatus.QUEUED), ExecutionStatus.RUNNING,
                StatusUpdate.at(later))).isTrue();
        assertThat(repository.cas(DEV, e.id(), EnumSet.of(ExecutionStatus.QUEUED), ExecutionStatus.START_FAILED,
                StatusUpdate.at(later))).isFalse();
        assertThat(repository.cas(OTHER, e.id(), EnumSet.of(ExecutionStatus.RUNNING), ExecutionStatus.FAILED,
                StatusUpdate.at(later))).isFalse();
        assertThat(repository.cas(DEV, e.id(), EnumSet.of(ExecutionStatus.RUNNING), ExecutionStatus.FAILED,
                StatusUpdate.failure(later.plusSeconds(1), "UPSTREAM_IO", "boom"))).isTrue();

        ExecutionRecord stored = repository.findById(DEV, e.id()).orElseThrow();
        assertThat(stored.status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(stored.rowVersion()).isEqualTo(2);
        assertThat(stored.startedAt()).isEqualTo(later);
        assertThat(stored.endedAt()).isEqualTo(later.plusSeconds(1));
        assertThat(stored.errorCode()).isEqualTo("UPSTREAM_IO");
    }

    @Test
    void nodeRunsAreEmptyForAFreshExecution() {
        NewExecution e = newExecution("k-" + UUID.randomUUID());
        repository.insert(e);

        assertThat(repository.findNodeRuns(DEV, e.id())).isEmpty();
    }
}
