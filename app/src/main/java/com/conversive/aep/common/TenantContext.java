package com.conversive.aep.common;

import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.MDC;

/** Request/activity-scoped tenant holder that mirrors the tenant (and execution) into the logging MDC. */
public final class TenantContext {

    public static final String MDC_TENANT = "tenantId";
    public static final String MDC_EXECUTION = "executionId";

    private static final ThreadLocal<TenantId> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static Optional<TenantId> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static TenantId require() {
        return current().orElseThrow(() -> new NonRetryableError(ErrorCodes.UNAUTHORIZED, "no tenant in context"));
    }

    public static <T> T callAs(TenantId tenantId, ExecutionId executionId, Supplier<T> body) {
        TenantId previousTenant = CURRENT.get();
        String previousTenantMdc = MDC.get(MDC_TENANT);
        String previousExecMdc = MDC.get(MDC_EXECUTION);
        set(tenantId, executionId);
        try {
            return body.get();
        } finally {
            CURRENT.set(previousTenant);
            restore(MDC_TENANT, previousTenantMdc);
            restore(MDC_EXECUTION, previousExecMdc);
        }
    }

    public static void runAs(TenantId tenantId, Runnable body) {
        callAs(tenantId, null, () -> {
            body.run();
            return null;
        });
    }

    public static void set(TenantId tenantId, ExecutionId executionId) {
        CURRENT.set(tenantId);
        MDC.put(MDC_TENANT, tenantId.value());
        if (executionId != null) {
            MDC.put(MDC_EXECUTION, executionId.toString());
        }
    }

    public static void clear() {
        CURRENT.remove();
        MDC.remove(MDC_TENANT);
        MDC.remove(MDC_EXECUTION);
    }

    private static void restore(String key, String value) {
        if (value == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, value);
        }
    }
}
