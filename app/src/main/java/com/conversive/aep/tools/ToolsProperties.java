package com.conversive.aep.tools;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param mcpUrl        JSON-RPC 2.0 MCP endpoint ({@code tools/call})
 * @param devCredential fallback tool credential when no {@code AEP_TOOL_CRED_<TENANT>_<TOOL>} is set (dev only)
 */
@ConfigurationProperties("aep.tools")
public record ToolsProperties(URI mcpUrl, String devCredential) {

    public static final URI DEFAULT_MCP_URL = URI.create("http://localhost:8090/mcp");

    public ToolsProperties {
        mcpUrl = mcpUrl == null ? DEFAULT_MCP_URL : mcpUrl;
    }

    @Override
    public String toString() {
        return "ToolsProperties[mcpUrl=" + mcpUrl + ", devCredential=" + (devCredential == null ? "unset" : "***") + "]";
    }
}
