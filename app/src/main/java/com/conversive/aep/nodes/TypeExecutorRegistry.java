package com.conversive.aep.nodes;

import com.conversive.aep.common.ErrorCodes;
import com.conversive.aep.common.ExecutionMode;
import com.conversive.aep.common.NonRetryableError;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Resolves executors by node type for LIVE executions. P6 supersedes it with a {@code @Primary} mode-aware registry. */
@Component
public class TypeExecutorRegistry implements ExecutorRegistry {

    private final Map<String, NodeExecutor> byType;

    public TypeExecutorRegistry(List<NodeExecutor> executors) {
        this.byType = executors.stream().collect(Collectors.toUnmodifiableMap(NodeExecutor::type, Function.identity()));
    }

    @Override
    public NodeExecutor resolve(NodeContext ctx) {
        if (ctx.mode() != ExecutionMode.LIVE) {
            throw new NonRetryableError(ErrorCodes.VALIDATION_FAILED, "mode not supported yet: " + ctx.mode());
        }
        NodeExecutor executor = byType.get(ctx.nodeType());
        if (executor == null) {
            throw new NonRetryableError(ErrorCodes.VALIDATION_FAILED, "unknown node type: " + ctx.nodeType());
        }
        return executor;
    }
}
