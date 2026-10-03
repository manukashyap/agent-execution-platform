package com.conversive.aep.tools;

import java.util.List;
import java.util.Optional;

/** Read-only view of the global tool catalog ({@code tool_registry}, V2). */
public interface ToolRegistry {

    Optional<ToolDefinition> find(String toolName);

    List<ToolDefinition> findAll();
}
