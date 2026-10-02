package com.conversive.aep.tools;

import com.conversive.aep.common.TenantId;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Prototype credential store. A tenant/tool pair reads {@code AEP_TOOL_CRED_<HEX(tenant)>_<HEX(tool)>}, where HEX is
 * the upper-case hex of the UTF-8 bytes (e.g. {@code t_dev} + {@code leads.fetch} gives
 * {@code AEP_TOOL_CRED_745F646576_6C656164732E6665746368}). Hex contains no {@code _}, so the separator is
 * unambiguous and the mapping is injective: different tenants or tools can never share a variable (the earlier
 * upper-case-and-replace scheme made {@code acme-x}, {@code acme_x} and {@code ACME_X} collide).
 * The shared {@code aep.tools.dev-credential} fallback is only honoured under the {@code dev} profile; elsewhere a
 * missing tenant credential fails closed instead of handing one shared key to every tenant.
 */
@Component
public class EnvToolCredentialProvider implements ToolCredentialProvider {

    static final String PREFIX = "AEP_TOOL_CRED_";
    static final String DEV_PROFILE = "dev";

    private final UnaryOperator<String> env;
    private final String fallback;
    private final boolean devProfile;

    @Autowired
    public EnvToolCredentialProvider(ToolsProperties properties, Environment environment) {
        this(System::getenv, properties.devCredential(), environment.acceptsProfiles(Profiles.of(DEV_PROFILE)));
    }

    public EnvToolCredentialProvider(UnaryOperator<String> env, String fallback, boolean devProfile) {
        this.env = env;
        this.fallback = fallback;
        this.devProfile = devProfile;
    }

    @Override
    public Optional<String> credential(TenantId tenantId, String toolName) {
        String specific = env.apply(variable(tenantId, toolName));
        if (!isBlank(specific)) {
            return Optional.of(specific);
        }
        if (isBlank(fallback)) {
            return Optional.empty();
        }
        if (!devProfile) {
            throw new IllegalStateException("no credential in " + variable(tenantId, toolName) + " for tool '"
                    + toolName + "' and tenant " + tenantId.value() + "; the shared aep.tools.dev-credential fallback"
                    + " is only honoured under the dev profile");
        }
        return Optional.of(fallback);
    }

    static String variable(TenantId tenantId, String toolName) {
        return PREFIX + hex(tenantId.value()) + "_" + hex(toolName);
    }

    private static String hex(String part) {
        return HexFormat.of().withUpperCase().formatHex(part.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
