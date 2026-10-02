package com.conversive.aep.nodes;

/** SPI for one node type ("custom operator" in the PDF). Implementations run inside activities. */
public interface NodeExecutor {

    String type();

    NodeResult execute(NodeContext ctx);
}
