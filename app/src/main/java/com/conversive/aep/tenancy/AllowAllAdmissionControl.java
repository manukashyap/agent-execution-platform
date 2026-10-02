package com.conversive.aep.tenancy;

import com.conversive.aep.common.TenantId;
import org.springframework.stereotype.Component;

/** P1 placeholder: admits everything. Replaced by the token-bucket / concurrency limiter. */
@Component
public class AllowAllAdmissionControl implements AdmissionControl {

    @Override
    public void admit(TenantId tenantId) {
        // intentionally empty
    }
}
