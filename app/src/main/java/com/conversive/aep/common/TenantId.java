package com.conversive.aep.common;

import java.util.Objects;
import java.util.regex.Pattern;

public record TenantId(String value) {

    private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    public TenantId {
        Objects.requireNonNull(value, "tenantId");
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid tenant id");
        }
    }

    public static TenantId of(String value) {
        return new TenantId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
