package com.conversive.aep.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class TenantContextTest {

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void scopesTenantAndMdcToTheCallAndRestoresAfterwards() {
        ExecutionId exec = ExecutionId.random();

        String seen = TenantContext.callAs(TenantId.of("t_dev"), exec,
                () -> TenantContext.require().value() + "|" + MDC.get(TenantContext.MDC_EXECUTION));

        assertThat(seen).isEqualTo("t_dev|" + exec);
        assertThat(TenantContext.current()).isEmpty();
        assertThat(MDC.get(TenantContext.MDC_TENANT)).isNull();
    }

    @Test
    void requireWithoutTenantIsUnauthorized() {
        assertThatThrownBy(TenantContext::require)
                .isInstanceOf(NonRetryableError.class)
                .extracting(e -> ((NonRetryableError) e).code())
                .isEqualTo(ErrorCodes.UNAUTHORIZED);
    }

    @Test
    void rejectsMalformedTenantIds() {
        assertThatThrownBy(() -> TenantId.of("t dev; drop")).isInstanceOf(IllegalArgumentException.class);
    }
}
