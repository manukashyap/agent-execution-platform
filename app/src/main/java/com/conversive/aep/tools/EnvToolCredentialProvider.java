package com.conversive.aep.tools;

import com.conversive.aep.common.TenantId;
import java.util.Locale;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Prototype credential store: {@code AEP_TOOL_CRED_<TENANT>_<TOOL>} (upper-cased, non-alphanumerics as {@code _},
 * e.g. {@code AEP_TOOL_CRED_T_DEV_PAYMENTS_CHARGE}), else {@code aep.tools.dev-credential}, else none.
 */
@Component
public class EnvToolCredentialProvider implements ToolCredentialProvider {

    static final String PREFIX = "AEP_TOOL_CRED_";

    private final UnaryOperator<String> env;
    private final String fallback;

    @Autowired
    public EnvToolCredentialProvider(ToolsProperties properties) {
        this(System::getenv, properties.devCredential());
    }

    public EnvToolCredentialProvider(UnaryOperator<String> env, String fallback) {
        this.env = env;
        this.fallback = fallback;
    }

    @Override
    public Optional<String> credential(TenantId tenantId, String toolName) {
        String specific = env.apply(variable(tenantId, toolName));
        if (!isBlank(specific)) {
            return Optional.of(specific);
        }
        return isBlank(fallback) ? Optional.empty() : Optional.of(fallback);
    }

    static String variable(TenantId tenantId, String toolName) {
        return PREFIX + normalize(tenantId.value()) + "_" + normalize(toolName);
    }

    private static String normalize(String part) {
        return part.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_");
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
