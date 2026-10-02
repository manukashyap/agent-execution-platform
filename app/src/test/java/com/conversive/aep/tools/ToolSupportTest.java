package com.conversive.aep.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.conversive.aep.common.TenantId;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Credential lookup, args digest and the non-executable JSON template. */
class ToolSupportTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void credentialIsReadFromTheTenantAndToolSpecificVariableElseTheDevFallback() {
        Map<String, String> env = Map.of("AEP_TOOL_CRED_T_DEV_PAYMENTS_CHARGE", "specific");
        EnvToolCredentialProvider provider = new EnvToolCredentialProvider(env::get, "fallback");

        assertThat(provider.credential(TenantId.of("t_dev"), "payments.charge")).contains("specific");
        assertThat(provider.credential(TenantId.of("t_dev"), "crm.get")).contains("fallback");
        assertThat(new EnvToolCredentialProvider(env::get, " ").credential(TenantId.of("t_x"), "crm.get")).isEmpty();
        assertThat(EnvToolCredentialProvider.variable(TenantId.of("t_dev"), "leads.fetch"))
                .isEqualTo("AEP_TOOL_CRED_T_DEV_LEADS_FETCH");
    }

    @Test
    void propertiesNeverPrintTheCredential() {
        assertThat(new ToolsProperties(null, "s3cr3t-value").toString()).doesNotContain("s3cr3t-value");
    }

    @Test
    void argsDigestIgnoresKeyOrder() throws Exception {
        String a = ArgsDigest.sha256(mapper.readTree("{\"b\":1,\"a\":{\"y\":2,\"x\":[1,2]}}"));
        String b = ArgsDigest.sha256(mapper.readTree("{\"a\":{\"x\":[1,2],\"y\":2},\"b\":1}"));
        String c = ArgsDigest.sha256(mapper.readTree("{\"a\":{\"x\":[2,1],\"y\":2},\"b\":1}"));

        assertThat(a).isEqualTo(b).hasSize(64).isNotEqualTo(c);
    }

    @Test
    void templateKeepsTypesDropsMissingFieldsAndInterpolatesEmbeddedPlaceholders() throws Exception {
        JsonNode scope = mapper.readTree("{\"lead\":{\"ref\":\"l1\",\"n\":3,\"tags\":[\"a\",\"b\"]}}");
        JsonNode template = mapper.readTree("""
                {"external_ref":"{{lead.ref}}","limit":"{{ lead.n }}","first":"{{lead.tags.0}}",
                 "gone":"{{lead.missing}}","label":"ref={{lead.ref}} n={{lead.n}}","fixed":true}
                """);

        JsonNode out = JsonTemplate.render(template, scope);

        assertThat(out.path("external_ref").asText()).isEqualTo("l1");
        assertThat(out.path("limit").isInt()).isTrue();
        assertThat(out.path("first").asText()).isEqualTo("a");
        assertThat(out.has("gone")).isFalse();
        assertThat(out.path("label").asText()).isEqualTo("ref=l1 n=3");
        assertThat(out.path("fixed").asBoolean()).isTrue();
        assertThat(JsonTemplate.render(null, scope).isObject()).isTrue();
    }
}
