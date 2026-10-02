package com.conversive.aep.tenancy;

import com.conversive.aep.common.TenantId;
import java.util.Objects;
import java.util.Set;

/** The caller behind an API key. Scope {@code *} grants everything. */
public record ApiPrincipal(TenantId tenantId, Set<String> scopes) {

    public static final String ALL = "*";

    public ApiPrincipal {
        Objects.requireNonNull(tenantId, "tenantId");
        scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
    }

    public boolean has(String scope) {
        return scopes.contains(ALL) || scopes.contains(scope);
    }
}
