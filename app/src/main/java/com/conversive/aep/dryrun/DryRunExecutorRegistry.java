package com.conversive.aep.dryrun;

import com.conversive.aep.common.ExecutionMode;
import com.conversive.aep.common.http.NonLiveEgress;
import com.conversive.aep.nodes.ExecutorRegistry;
import com.conversive.aep.nodes.NodeContext;
import com.conversive.aep.nodes.NodeExecutor;
import com.conversive.aep.nodes.NodeResult;
import com.conversive.aep.nodes.TypeExecutorRegistry;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The platform's {@link ExecutorRegistry}: by node type in {@code LIVE}; otherwise the {@link DryRunPolicy} decides
 * per node. A node allowed to run live gets its real executor wrapped in a {@link NonLiveEgress} permit (the only
 * way a non-{@code LIVE} request passes {@code OutboundClient}); every other node runs on {@link DryRunMocks}.
 * Same interpreter, same activities — only this resolution differs (06 §4.10).
 */
@Component
public class DryRunExecutorRegistry implements ExecutorRegistry {

    private final TypeExecutorRegistry types;
    private final DryRunPolicy policy;
    private final DryRunMocks mocks;

    public DryRunExecutorRegistry(List<NodeExecutor> executors, DryRunPolicy policy, DryRunMocks mocks) {
        this.types = new TypeExecutorRegistry(executors);
        this.policy = policy;
        this.mocks = mocks;
    }

    @Override
    public NodeExecutor resolve(NodeContext ctx) {
        NodeExecutor executor = types.resolve(ctx);
        if (ctx.mode() == ExecutionMode.LIVE) {
            return executor;
        }
        return policy.runsLive(ctx) ? new Permitted(executor) : new Mocked(executor.type(), mocks);
    }

    /** A live executor in a non-LIVE execution: its outbound calls carry the dry-run policy's permit. */
    record Permitted(NodeExecutor delegate) implements NodeExecutor {

        @Override
        public String type() {
            return delegate.type();
        }

        @Override
        public NodeResult execute(NodeContext ctx) {
            return NonLiveEgress.permit(() -> delegate.execute(ctx));
        }
    }

    /** A mocked node: never reaches the real executor. */
    record Mocked(String type, DryRunMocks mocks) implements NodeExecutor {

        @Override
        public NodeResult execute(NodeContext ctx) {
            return mocks.execute(ctx);
        }
    }
}
