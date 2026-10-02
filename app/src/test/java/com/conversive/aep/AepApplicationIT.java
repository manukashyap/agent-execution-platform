package com.conversive.aep;

import static org.assertj.core.api.Assertions.assertThat;

import com.conversive.aep.common.Hashing;
import com.conversive.aep.cost.BudgetService;
import com.conversive.aep.nodes.ExecutorRegistry;
import com.conversive.aep.sideeffect.SideEffectGuard;
import com.conversive.aep.support.PostgresIntegrationTest;
import io.temporal.client.WorkflowClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@ActiveProfiles({"test", "dev"})
@TestPropertySource(properties = "aep.tenancy.dev.dev-api-key=test-only-key")
class AepApplicationIT extends PostgresIntegrationTest {

    @Autowired
    JdbcClient jdbc;
    @Autowired
    WorkflowClient workflowClient;
    @Autowired
    ExecutorRegistry executorRegistry;
    @Autowired
    SideEffectGuard sideEffectGuard;
    @Autowired
    BudgetService budgetService;

    @Test
    void contextWiresSeamsAndTemporalClient() {
        assertThat(workflowClient.getOptions().getNamespace()).isEqualTo("default");
        assertThat(executorRegistry).isNotNull();
        assertThat(sideEffectGuard).isNotNull();
        assertThat(budgetService).isNotNull();
    }

    @Test
    void devProfileStoresOnlyTheHashOfTheConfiguredKey() {
        assertThat(jdbc.sql("SELECT tenant_id FROM api_key WHERE key_hash = :h")
                .param("h", Hashing.sha256Hex("test-only-key")).query(String.class).single()).isEqualTo("t_dev");
        assertThat(jdbc.sql("SELECT count(*) FROM api_key WHERE key_hash = 'test-only-key'")
                .query(Long.class).single()).isZero();
    }
}
