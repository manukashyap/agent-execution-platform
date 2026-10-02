package com.conversive.aep.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.conversive.aep.common.ErrorCodes;
import com.conversive.aep.common.NonRetryableError;
import com.conversive.aep.common.TenantId;
import com.conversive.aep.support.PostgresIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** V4 seed: t_dev is granted all seven tools, t_other only the two read tools. */
class ToolGrantsIT extends PostgresIntegrationTest {

    @Autowired
    private ToolAccess access;

    @Autowired
    private ToolGrants grants;

    @Test
    void devTenantIsGrantedEveryToolWithItsScopes() {
        assertThat(access.granted(TenantId.of("t_dev"))).extracting(ToolDefinition::name)
                .containsExactlyInAnyOrder("payments.charge", "payments.refund", "messaging.send", "crm.upsert",
                        "crm.delete", "crm.get", "leads.fetch");
        assertThat(grants.find(TenantId.of("t_dev"), "payments.charge")).get()
                .satisfies(g -> assertThat(g.scopes()).containsExactly("payments:write"));
    }

    @Test
    void otherTenantOnlyReadsAndIsForbiddenEverythingElse() {
        assertThat(access.granted(TenantId.of("t_other"))).extracting(ToolDefinition::name)
                .containsExactlyInAnyOrder("leads.fetch", "crm.get");
        assertThatThrownBy(() -> access.authorize(TenantId.of("t_other"), "crm.upsert"))
                .isInstanceOfSatisfying(NonRetryableError.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCodes.TOOL_FORBIDDEN));
        assertThat(access.granted(TenantId.of("t_nobody"))).isEmpty();
    }

    @Test
    void grantWithoutTheRequiredScopeDoesNotCover() {
        ToolGrant grant = new ToolGrant(TenantId.of("t_dev"), "crm.get", List.of("crm:write"));

        assertThat(grant.covers(List.of("crm:read"))).isFalse();
        assertThat(grant.covers(List.of())).isTrue();
    }
}
