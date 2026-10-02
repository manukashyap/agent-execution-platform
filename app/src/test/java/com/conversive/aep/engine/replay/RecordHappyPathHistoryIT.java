package com.conversive.aep.engine.replay;

import static org.assertj.core.api.Assertions.assertThat;

import com.conversive.aep.common.ExecutionId;
import com.conversive.aep.definition.DefinitionService;
import com.conversive.aep.engine.EngineHarness;
import com.conversive.aep.execution.ExecutionStatus;
import com.conversive.aep.execution.persistence.ExecutionRepository;
import com.conversive.aep.execution.service.ExecutionService;
import com.conversive.aep.support.InProcessTemporal;
import com.conversive.aep.support.PostgresIntegrationTest;
import com.conversive.aep.support.TestTenants;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.client.WorkflowClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Re-records {@code histories/happy-path-condition-foreach.json}. Disabled unless {@code AEP_RECORD_HISTORIES=true}:
 * {@code AEP_RECORD_HISTORIES=true ./gradlew :app:test --tests '*Record*HistoryIT'}.
 */
@EnabledIfEnvironmentVariable(named = HistoryFiles.RECORD_ENV, matches = "true")
@Import(InProcessTemporal.class)
class RecordHappyPathHistoryIT extends PostgresIntegrationTest {

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

    @Test
    void recordsAConditionAndForEachRun() {
        EngineHarness h = new EngineHarness(new TestTenants(jdbc).create("t_replay_happy"), definitions, executions,
                repository, client, jdbc, mapper);
        h.publish("""
                {"workflow_id":"replay_happy","version":1,"nodes":[
                  {"id":"src","type":"mcp","config":{"tool":"crm.get","output":{"score":80,"items":[1,2,3]}}},
                  {"id":"is_hot","type":"condition","depends_on":["src"],
                   "config":{"left":"$.src.score","op":"gt","right":50,"then":["hot"],"else":["cold"]}},
                  {"id":"hot","type":"mcp","depends_on":["is_hot"],"config":{"tool":"crm.get"}},
                  {"id":"cold","type":"mcp","depends_on":["is_hot"],"config":{"tool":"crm.get"}},
                  {"id":"each","type":"mcp","depends_on":["src"],
                   "for_each":{"items":"$.src.items","max_concurrency":2},"config":{"tool":"crm.get"}},
                  {"id":"sink","type":"mcp","depends_on":["hot","cold","each"],"config":{"tool":"crm.get"}}]}
                """);
        ExecutionId id = h.start("replay_happy");

        assertThat(h.await(id).status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(h.nodeStatuses(id)).containsEntry("cold", "SKIPPED").containsEntry("sink", "SUCCEEDED");
        HistoryFiles.record(client, h.tenant(), id, HistoryFiles.HAPPY_PATH);
    }
}
