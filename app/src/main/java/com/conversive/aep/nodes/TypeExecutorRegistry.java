package com.conversive.aep.nodes;

import com.conversive.aep.common.ErrorCodes;
import com.conversive.aep.common.NonRetryableError;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Resolves executors by node type only, whatever the mode. Not a bean: the mode-aware
 * {@code dryrun.DryRunExecutorRegistry} wraps it and is the platform's {@link ExecutorRegistry}.
 */
public class TypeExecutorRegistry implements ExecutorRegistry {

    private final Map<String, NodeExecutor> byType;

    public TypeExecutorRegistry(List<NodeExecutor> executors) {
        this.byType = executors.stream().collect(Collectors.toUnmodifiableMap(NodeExecutor::type, Function.identity()));
    }

    @Override
    public NodeExecutor resolve(NodeContext ctx) {
        NodeExecutor executor = byType.get(ctx.nodeType());
        if (executor == null) {
            throw new NonRetryableError(ErrorCodes.VALIDATION_FAILED, "unknown node type: " + ctx.nodeType());
        }
        return executor;
    }
}
