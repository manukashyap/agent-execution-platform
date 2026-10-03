package com.conversive.aep.tenancy;

import com.conversive.aep.common.TenantId;
import com.conversive.aep.tenancy.persistence.TenantTierRepository;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.LongSupplier;

/** {@code tenant.tier} with a TTL cache; an unknown tenant is {@link TenantTier#STANDARD}. */
public class CachedTenantTiers implements Function<TenantId, TenantTier> {

    private record Entry(TenantTier tier, long loadedAtNanos) {
    }

    private final TenantTierRepository repository;
    private final Duration ttl;
    private final LongSupplier nanoTicker;
    private final Map<TenantId, Entry> cache = new ConcurrentHashMap<>();

    public CachedTenantTiers(TenantTierRepository repository, Duration ttl, LongSupplier nanoTicker) {
        this.repository = repository;
        this.ttl = ttl;
        this.nanoTicker = nanoTicker;
    }

    @Override
    public TenantTier apply(TenantId tenantId) {
        long now = nanoTicker.getAsLong();
        Entry entry = cache.get(tenantId);
        if (entry != null && now - entry.loadedAtNanos() < ttl.toNanos()) {
            return entry.tier();
        }
        TenantTier tier = repository.findTier(tenantId).orElse(TenantTier.STANDARD);
        cache.put(tenantId, new Entry(tier, now));
        return tier;
    }
}
