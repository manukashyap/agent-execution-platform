package com.conversive.aep.tools;

import com.conversive.aep.common.ErrorCodes;
import com.conversive.aep.common.NonRetryableError;
import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Validates tool arguments against the registry's {@code input_schema} (the registry, not the MCP server, is the authority). */
@Component
public class ToolArgsValidator {

    private final JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    private final SchemaValidatorsConfig config = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();
    private final Map<String, JsonSchema> schemas = new ConcurrentHashMap<>();

    /** @throws NonRetryableError {@code VALIDATION_FAILED} listing every violation */
    public void validate(ToolDefinition tool, JsonNode args) {
        JsonSchema schema = schemas.computeIfAbsent(tool.name() + "@" + tool.version(),
                k -> factory.getSchema(tool.inputSchema(), config));
        Set<ValidationMessage> errors = schema.validate(args);
        if (!errors.isEmpty()) {
            String list = errors.stream().map(ValidationMessage::getMessage).sorted().collect(Collectors.joining("; "));
            throw new NonRetryableError(ErrorCodes.VALIDATION_FAILED, "invalid arguments for " + tool.name() + ": " + list);
        }
    }
}
