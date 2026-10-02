package com.conversive.aep.tenancy;

import com.conversive.aep.tenancy.persistence.LiveExecutionCounter;
import com.conversive.aep.tenancy.persistence.TenantLimitsRepository;
import com.conversive.aep.tenancy.persistence.TenantTierRepository;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration(proxyBeanMethods = false)
public class TenancyConfig {

    @Bean
    CachedTenantLimits cachedTenantLimits(TenantLimitsRepository repository, TenancyProperties props) {
        return new CachedTenantLimits(repository, props, System::nanoTime);
    }

    @Bean
    TenantRateLimiter tenantRateLimiter() {
        return new TenantRateLimiter(System::nanoTime);
    }

    @Bean
    TemporalPriorityPolicy temporalPriorityPolicy(TenantTierRepository tiers, TenancyProperties props) {
        return new TemporalPriorityPolicy(new CachedTenantTiers(tiers, props.limitsRefresh(), System::nanoTime));
    }

    /** Supersedes the P1 {@link AllowAllAdmissionControl}. */
    @Bean
    @Primary
    AdmissionControl tokenBucketAdmissionControl(CachedTenantLimits limits, TenantRateLimiter rateLimiter,
                                                 LiveExecutionCounter liveExecutions, TenancyProperties props,
                                                 Clock clock) {
        return new TokenBucketAdmissionControl(limits, rateLimiter, liveExecutions, props, clock);
    }
}
