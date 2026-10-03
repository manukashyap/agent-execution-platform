package com.conversive.aep.nodes;

public interface ExecutorRegistry {

    NodeExecutor resolve(NodeContext ctx);
}
