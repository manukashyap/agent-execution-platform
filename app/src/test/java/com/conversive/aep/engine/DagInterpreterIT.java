package com.conversive.aep.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.conversive.aep.common.ExecutionId;
import com.conversive.aep.definition.DefinitionService;
import com.conversive.aep.engine.workflow.ExecutionResult;
import com.conversive.aep.execution.ExecutionStatus;
import com.conversive.aep.execution.persistence.ExecutionRepository;
import com.conversive.aep.execution.persistence.NodeRunRecord;
import com.conversive.aep.execution.service.ExecutionService;
import com.conversive.aep.support.Fixtures;
import com.conversive.aep.support.InProcessTemporal;
import com.conversive.aep.support.PostgresIntegrationTest;
import com.conversive.aep.support.ScriptedExecutor;
import com.conversive.aep.support.ScriptedExecutor.CallRecord;
import com.conversive.aep.support.TestTenants;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.client.WorkflowClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The ten P2a interpreter scenarios (06 §4.4), against Postgres and an in-process Temporal. */
@Import(InProcessTemporal.class)
class DagInterpreterIT extends PostgresIntegrationTest {

    @Autowired
    DefinitionService definitions;
    @Autowired
    ExecutionService executions;
    @Autowired
    ExecutionRepository repository;
    @Autowired
    WorkflowClient client;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    ScriptedExecutor scripted;

    private EngineHarness h;

    @BeforeEach
    void harness() {
        h = new EngineHarness(new TestTenants(jdbc).create("t_dag"), definitions, executions, repository, client,
                jdbc, mapper);
    }

    @Test
    void sequentialPdfExampleRunsEachNodeAfterThePreviousOne() {
        h.publish(Fixtures.pdfExampleJson());
        ExecutionId id = h.start("lead_enrichment");

        assertThat(h.await(id).status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        List<CallRecord> calls = scripted.calls(id.toString());
        assertThat(calls).extracting(CallRecord::nodeId)
                .containsExactly("fetch_leads", "classify_leads", "update_crm", "send_message");
        assertThat(calls.get(3).input().has("update_crm")).isTrue();
        assertThat(calls.get(3).input().has("fetch_leads")).isTrue();
        assertThat(h.nodeStatuses(id)).containsOnlyKeys("fetch_leads", "classify_leads", "update_crm", "send_message")
                .allSatisfy((node, status) -> assertThat(status).isEqualTo("SUCCEEDED"));
        assertThat(h.row(id).status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(h.row(id).output().has("send_message")).isTrue();
    }

    @Test
    void parallelDiamondRunsTheBranchesConcurrentlyAndJoins() {
        h.publish("""
                {"workflow_id":"diamond","version":1,"nodes":[
                  {"id":"a","type":"mcp","config":{"tool":"crm.get"}},
                  {"id":"b","type":"mcp","depends_on":["a"],"config":{"tool":"crm.get","sleepMs":600}},
                  {"id":"c","type":"mcp","depends_on":["a"],"config":{"tool":"crm.get","sleepMs":600}},
                  {"id":"d","type":"mcp","depends_on":["b","c"],"config":{"tool":"crm.get"}}]}
                """);
        ExecutionId id = h.start("diamond");

        assertThat(h.await(id).status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(scripted.peakConcurrency(id.toString())).isEqualTo(2);
        CallRecord d = byNode(id).get("d");
        assertThat(d.input().has("a") && d.input().has("b") && d.input().has("c")).isTrue();
    }

    @Test
    void dependencyOrderIsRespectedWhateverTheListOrder() {
        h.publish("""
                {"workflow_id":"order","version":1,"nodes":[
                  {"id":"c","type":"mcp","depends_on":["b"],"config":{"tool":"crm.get"}},
                  {"id":"b","type":"mcp","depends_on":["a"],"config":{"tool":"crm.get","sleepMs":200}},
                  {"id":"a","type":"mcp","depends_on":[],"config":{"tool":"crm.get","sleepMs":200}}]}
                """);
        ExecutionId id = h.start("order");

        assertThat(h.await(id).status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(scripted.calls(id.toString())).extracting(CallRecord::nodeId).containsExactly("a", "b", "c");
    }

    @Test
    void retryThenSucceedRecordsEveryAttempt() {
        h.publish("""
                {"workflow_id":"retry","version":1,"nodes":[
                  {"id":"flaky","type":"mcp","config":{"tool":"crm.get","failTimes":2},
                   "retry":{"max_attempts":3,"initial_interval_ms":10,"backoff":"FIXED"}}]}
                """);
        ExecutionId id = h.start("retry");

        assertThat(h.await(id).status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(h.runs(id)).extracting(NodeRunRecord::attempt, NodeRunRecord::status)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1, "FAILED"),
                        org.assertj.core.groups.Tuple.tuple(2, "FAILED"),
                        org.assertj.core.groups.Tuple.tuple(3, "SUCCEEDED"));
    }

    @Test
    void nodeTimeoutFailsTheRunAndTheDeadlineTimesItOut() {
        h.publish("""
                {"workflow_id":"slow_node","version":1,"nodes":[
                  {"id":"slow","type":"mcp","timeout_s":1,"retry":{"max_attempts":1},
                   "config":{"tool":"crm.get","sleepMs":4000}}]}
                """);
        ExecutionResult nodeTimeout = h.await(h.start("slow_node"));
        assertThat(nodeTimeout.status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(nodeTimeout.errorCode()).isEqualTo("TIMEOUT");

        h.publish("""
                {"workflow_id":"deadline","version":1,"max_duration_s":2,"nodes":[
                  {"id":"slow","type":"mcp","timeout_s":30,"config":{"tool":"crm.get","sleepMs":8000}},
                  {"id":"after","type":"mcp","config":{"tool":"crm.get"}}]}
                """);
        ExecutionId id = h.start("deadline");
        ExecutionResult deadline = h.await(id);
        assertThat(deadline.status()).isEqualTo(ExecutionStatus.TIMED_OUT);
        assertThat(h.row(id).status()).isEqualTo(ExecutionStatus.TIMED_OUT);
        assertThat(h.nodeStatuses(id)).containsEntry("after", "SKIPPED");
    }

    @Test
    void failFastCancelsSiblingsWhileContinueOnlySkipsDependants() {
        h.publish("""
                {"workflow_id":"ff","version":1,"nodes":[
                  {"id":"bad","type":"mcp","depends_on":[],"config":{"tool":"crm.get","sleepMs":200,"fatal":"BOOM"}},
                  {"id":"slow","type":"mcp","depends_on":[],"config":{"tool":"crm.get","sleepMs":10000}},
                  {"id":"after_bad","type":"mcp","depends_on":["bad"],"config":{"tool":"crm.get"}}]}
                """);
        ExecutionId ff = h.start("ff");
        ExecutionResult failFast = h.await(ff);
        assertThat(failFast.status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(failFast.errorCode()).isEqualTo("BOOM");
        assertThat(h.nodeStatuses(ff)).containsEntry("bad", "FAILED").containsEntry("after_bad", "SKIPPED");
        assertThat(client.newUntypedWorkflowStub("exec:" + h.tenant().value() + ":" + ff)
                .query("snapshot", com.conversive.aep.engine.workflow.ExecutionSnapshot.class).nodes())
                .containsEntry("slow", com.conversive.aep.engine.workflow.NodeStatus.CANCELLED);

        h.publish("""
                {"workflow_id":"cont","version":1,"nodes":[
                  {"id":"bad","type":"mcp","depends_on":[],"on_failure":"CONTINUE",
                   "config":{"tool":"crm.get","fatal":"BOOM"}},
                  {"id":"good","type":"mcp","depends_on":[],"config":{"tool":"crm.get","sleepMs":300}},
                  {"id":"after_bad","type":"mcp","depends_on":["bad"],"config":{"tool":"crm.get"}},
                  {"id":"after_good","type":"mcp","depends_on":["good"],"config":{"tool":"crm.get"}}]}
                """);
        ExecutionId cont = h.start("cont");
        assertThat(h.await(cont).status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(h.nodeStatuses(cont)).containsEntry("bad", "FAILED").containsEntry("after_bad", "SKIPPED")
                .containsEntry("good", "SUCCEEDED").containsEntry("after_good", "SUCCEEDED");
    }

    @Test
    void cancelMidFlightStopsTheRunAndSkipsTheRest() throws Exception {
        h.publish("""
                {"workflow_id":"cancel","version":1,"nodes":[
                  {"id":"long","type":"mcp","config":{"tool":"crm.get","sleepMs":20000}},
                  {"id":"next","type":"mcp","config":{"tool":"crm.get"}}]}
                """);
        ExecutionId id = h.start("cancel");
        EngineHarness.waitUntil(() -> !scripted.calls(id.toString()).isEmpty(), Duration.ofSeconds(10));

        executions.cancel(h.tenant(), id);

        assertThat(h.await(id).status()).isEqualTo(ExecutionStatus.CANCELLED);
        assertThat(h.row(id).status()).isEqualTo(ExecutionStatus.CANCELLED);
        assertThat(h.nodeStatuses(id)).containsEntry("next", "SKIPPED");
        EngineHarness.waitUntil(() -> "CANCELLED".equals(h.nodeStatuses(id).get("long")), Duration.ofSeconds(10));
    }

    @Test
    void aRunningV3IsUnaffectedByPublishingV4() throws Exception {
        h.publish(versioned(3, "v3"));
        ExecutionId v3 = h.start("pinned", 3, "{}");
        EngineHarness.waitUntil(() -> !scripted.calls(v3.toString()).isEmpty(), Duration.ofSeconds(10));

        h.publish(versioned(4, "v4"));
        ExecutionId v4 = h.start("pinned");

        assertThat(h.await(v3).status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(h.await(v4).status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(h.output(v3, "b").path("version").asText()).isEqualTo("v3");
        assertThat(h.output(v4, "b").path("version").asText()).isEqualTo("v4");
        assertThat(h.row(v3).defVersion()).isEqualTo(3);
        assertThat(h.row(v4).defVersion()).isEqualTo(4);
    }

    private static String versioned(int version, String label) {
        return """
                {"workflow_id":"pinned","version":%d,"nodes":[
                  {"id":"a","type":"mcp","config":{"tool":"crm.get","sleepMs":800}},
                  {"id":"b","type":"mcp","config":{"tool":"crm.get","output":{"version":"%s"}}}]}
                """.formatted(version, label);
    }

    @Test
    void conditionSkipsTheUntakenBranchAndTheJoinStillRuns() {
        h.publish("""
                {"workflow_id":"cond","version":1,"nodes":[
                  {"id":"score","type":"mcp","config":{"tool":"crm.get","output":{"score":80}}},
                  {"id":"is_hot","type":"condition","depends_on":["score"],
                   "config":{"left":"$.score.score","op":"gt","right":50,"then":["hot"],"else":["cold"]}},
                  {"id":"hot","type":"mcp","depends_on":["is_hot"],"config":{"tool":"crm.get"}},
                  {"id":"cold","type":"mcp","depends_on":["is_hot"],"config":{"tool":"crm.get"}},
                  {"id":"cold_followup","type":"mcp","depends_on":["cold"],"config":{"tool":"crm.get"}},
                  {"id":"join","type":"mcp","depends_on":["hot","cold"],"config":{"tool":"crm.get"}}]}
                """);
        ExecutionId id = h.start("cond");

        assertThat(h.await(id).status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(scripted.calls(id.toString())).extracting(CallRecord::nodeId)
                .containsExactly("score", "hot", "join");
        assertThat(h.nodeStatuses(id)).containsEntry("is_hot", "SUCCEEDED").containsEntry("cold", "SKIPPED")
                .containsEntry("cold_followup", "SKIPPED").containsEntry("join", "SUCCEEDED");
    }

    @Test
    void forEachOfAHundredItemsKeepsAtMostSixteenInFlight() {
        String items = IntStream.range(0, 100).mapToObj(Integer::toString).collect(Collectors.joining(","));
        h.publish("""
                {"workflow_id":"fan","version":1,"max_parallel":32,"nodes":[
                  {"id":"src","type":"mcp","config":{"tool":"crm.get","output":{"items":[%s]}}},
                  {"id":"each","type":"mcp","for_each":{"items":"$.src.items","max_concurrency":16},
                   "config":{"tool":"crm.get","sleepMs":60}},
                  {"id":"sink","type":"mcp","config":{"tool":"crm.get"}}]}
                """.formatted(items));
        ExecutionId id = h.start("fan");

        assertThat(h.await(id).status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(scripted.peakConcurrency(id.toString())).isBetween(2, 16);
        assertThat(h.outputRows(id, "each")).isEqualTo(100);
        assertThat(scripted.calls(id.toString()).stream().filter(c -> c.nodeId().equals("each"))
                .map(c -> c.input().path("item").asInt()).distinct().count()).isEqualTo(100);
        assertThat(byNode(id).get("sink").input().path("each").size()).isEqualTo(100);
    }

    private Map<String, CallRecord> byNode(ExecutionId id) {
        return scripted.calls(id.toString()).stream()
                .collect(Collectors.toMap(CallRecord::nodeId, c -> c, (x, y) -> y));
    }
}
