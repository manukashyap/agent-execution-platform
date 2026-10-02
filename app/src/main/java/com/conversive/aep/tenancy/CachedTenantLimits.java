package com.conversive.aep.tenancy;

import com.conversive.aep.common.TenantId;
import com.conversive.aep.tenancy.persistence.TenantLimitsRepository;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * {@code tenant_limits} with a short read-through cache, so admission costs no DB read per request
 * for the rate check. A limit change takes effect within {@link TenancyProperties#limitsRefresh()}.
 */
public class CachedTenantLimits {

    private record Entry(TenantLimits limits, long loadedAtNanos) {
    }

    private final TenantLimitsRepository repository;
    private final TenancyProperties props;
    private final LongSupplier nanoTicker;
    private final Map<TenantId, Entry> cache = new ConcurrentHashMap<>();

    public CachedTenantLimits(TenantLimitsRepository repository, TenancyProperties props, LongSupplier nanoTicker) {
        this.repository = repository;
        this.props = props;
        this.nanoTicker = nanoTicker;
    }

    public TenantLimits get(TenantId tenantId) {
        long now = nanoTicker.getAsLong();
        Entry entry = cache.get(tenantId);
        if (entry != null && now - entry.loadedAtNanos() < props.limitsRefresh().toNanos()) {
            return entry.limits();
        }
        TenantLimits limits = repository.find(tenantId).orElseGet(() -> defaults(tenantId));
        cache.put(tenantId, new Entry(limits, now));
        return limits;
    }

    private TenantLimits defaults(TenantId tenantId) {
        TenantLimits d = TenantLimits.defaults(tenantId);
        return new TenantLimits(tenantId, props.defaultRatePerSec(), props.defaultBurst(), props.defaultMaxConcurrent(),
                d.maxCostUsd(), d.maxTokens(), d.maxNodeExecutions(), d.maxFanout(), d.maxToolCalls(),
                d.maxDurationS());
    }
}
