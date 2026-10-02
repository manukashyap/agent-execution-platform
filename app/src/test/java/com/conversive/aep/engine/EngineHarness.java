package com.conversive.aep.engine;

import com.conversive.aep.common.ExecutionId;
import com.conversive.aep.common.TenantId;
import com.conversive.aep.definition.DefinitionService;
import com.conversive.aep.engine.workflow.DagInterpreterWorkflow;
import com.conversive.aep.engine.workflow.ExecutionResult;
import com.conversive.aep.execution.persistence.ExecutionRecord;
import com.conversive.aep.execution.persistence.ExecutionRepository;
import com.conversive.aep.execution.persistence.NodeRunRecord;
import com.conversive.aep.execution.service.ExecutionService;
import com.conversive.aep.execution.service.StartCommand;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowFailedException;
import io.temporal.failure.CanceledFailure;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Publishes definitions, starts executions through the real service and waits for the workflow result. */
public final class EngineHarness {

    private static final Duration RESULT_TIMEOUT = Duration.ofSeconds(60);

    private final TenantId tenant;
    private final DefinitionService definitions;
    private final ExecutionService executions;
    private final ExecutionRepository repository;
    private final WorkflowClient client;
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public EngineHarness(TenantId tenant, DefinitionService definitions, ExecutionService executions,
                         ExecutionRepository repository, WorkflowClient client, JdbcClient jdbc, ObjectMapper mapper) {
        this.tenant = tenant;
        this.definitions = definitions;
        this.executions = executions;
        this.repository = repository;
        this.client = client;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public TenantId tenant() {
        return tenant;
    }

    public void publish(String specJson) {
        definitions.publish(tenant, json(specJson));
    }

    public ExecutionId start(String workflowId, Integer version, String inputJson) {
        StartCommand cmd = new StartCommand(workflowId, version, json(inputJson), null, null, null,
                UUID.randomUUID().toString());
        return executions.start(tenant, cmd).execution().id();
    }

    public ExecutionId start(String workflowId) {
        return start(workflowId, null, "{}");
    }

    public ExecutionResult await(ExecutionId id) {
        try {
            return client.newUntypedWorkflowStub(DagInterpreterWorkflow.workflowId(tenant.value(), id.toString()))
                    .getResult(RESULT_TIMEOUT.toSeconds(), TimeUnit.SECONDS, ExecutionResult.class);
        } catch (TimeoutException e) {
            throw new AssertionError("execution " + id + " did not finish in " + RESULT_TIMEOUT, e);
        } catch (WorkflowFailedException e) {
            // The SDK closes a cancel-requested run as CANCELED even when the workflow returns normally.
            if (!(e.getCause() instanceof CanceledFailure)) {
                throw e;
            }
            ExecutionRecord r = row(id);
            return new ExecutionResult(r.status(), r.errorCode(), r.errorMessage());
        }
    }

    public ExecutionRecord row(ExecutionId id) {
        return repository.findById(tenant, id).orElseThrow();
    }

    public List<NodeRunRecord> runs(ExecutionId id) {
        return repository.findNodeRuns(tenant, id);
    }

    /** Status of the highest FORWARD attempt per node (call index 0). */
    public Map<String, String> nodeStatuses(ExecutionId id) {
        Map<String, String> out = new LinkedHashMap<>();
        Map<String, Integer> attempts = new LinkedHashMap<>();
        for (NodeRunRecord run : runs(id)) {
            if ("FORWARD".equals(run.phase()) && run.callIndex() == 0
                    && run.attempt() >= attempts.getOrDefault(run.nodeId(), 0)) {
                attempts.put(run.nodeId(), run.attempt());
                out.put(run.nodeId(), run.status());
            }
        }
        return out;
    }

    public long outputRows(ExecutionId id, String nodeId) {
        return jdbc.sql("SELECT count(*) FROM node_output WHERE tenant_id = :t AND execution_id = :e AND node_id = :n")
                .param("t", tenant.value()).param("e", id.value()).param("n", nodeId)
                .query(Long.class).single();
    }

    public JsonNode output(ExecutionId id, String nodeId) {
        String payload = jdbc.sql("""
                        SELECT payload::text FROM node_output
                         WHERE tenant_id = :t AND execution_id = :e AND node_id = :n AND call_index = 0
                        """)
                .param("t", tenant.value()).param("e", id.value()).param("n", nodeId)
                .query(String.class).single();
        return json(payload);
    }

    public static void waitUntil(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long end = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) {
                throw new AssertionError("condition not met within " + timeout);
            }
            Thread.sleep(20);
        }
    }

    public JsonNode json(String text) {
        try {
            return mapper.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
