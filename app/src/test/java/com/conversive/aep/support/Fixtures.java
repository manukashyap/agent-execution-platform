package com.conversive.aep.support;

import com.conversive.aep.common.ExecutionId;
import com.conversive.aep.common.ExecutionMode;
import com.conversive.aep.common.TenantId;
import com.conversive.aep.definition.DefinitionFreezer;
import com.conversive.aep.definition.DefinitionProperties;
import com.conversive.aep.definition.InMemoryToolCatalog;
import com.conversive.aep.definition.model.DefinitionCodec;
import com.conversive.aep.definition.model.FrozenDefinition;
import com.conversive.aep.definition.model.WorkflowDefinition;
import com.conversive.aep.engine.workflow.ExecutionRequest;
import com.conversive.aep.tenancy.TenantLimits;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

public final class Fixtures {

    private Fixtures() {
    }

    public static String read(String path) {
        try (InputStream in = Fixtures.class.getResourceAsStream("/fixtures/" + path)) {
            if (in == null) {
                throw new IllegalArgumentException("missing fixture " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String pdfExampleJson() {
        return read("pdf-example.json");
    }

    public static JsonNode pdfExample() {
        return DefinitionCodec.readTree(pdfExampleJson());
    }

    public static WorkflowDefinition pdfExampleDefinition() {
        return DefinitionCodec.parse(pdfExample());
    }

    public static FrozenDefinition frozenPdfExample(TenantId tenantId) {
        return new DefinitionFreezer(DefinitionProperties.defaults(), new InMemoryToolCatalog())
                .freeze(pdfExampleDefinition(), "sha-test", TenantLimits.defaults(tenantId));
    }

    public static ExecutionRequest pdfExampleRequest(TenantId tenantId, ExecutionId executionId, long deadlineMs) {
        return new ExecutionRequest(tenantId, executionId, frozenPdfExample(tenantId),
                JsonNodeFactory.instance.objectNode(), ExecutionMode.LIVE, null, null, deadlineMs);
    }
}
