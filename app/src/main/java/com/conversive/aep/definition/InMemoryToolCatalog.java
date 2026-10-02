package com.conversive.aep.definition;

import static com.conversive.aep.common.IdempotencyMode.LOOKUP;
import static com.conversive.aep.common.IdempotencyMode.NATIVE_KEY;
import static com.conversive.aep.common.IdempotencyMode.NONE;
import static com.conversive.aep.common.Reversibility.COMPENSATABLE;
import static com.conversive.aep.common.Reversibility.PIVOT;
import static com.conversive.aep.common.Reversibility.READ_ONLY;
import static com.conversive.aep.common.Reversibility.RETRIABLE;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** The six seed tools (served by the mocks). Superseded by a DB-backed {@code @Primary} catalog in P5. */
@Component
public class InMemoryToolCatalog implements ToolCatalog {

    public static final List<ToolInfo> SEED_TOOLS = List.of(
            new ToolInfo("payments.charge", COMPENSATABLE, NATIVE_KEY, "payments.refund"),
            new ToolInfo("payments.refund", RETRIABLE, NATIVE_KEY, null),
            new ToolInfo("messaging.send", PIVOT, NONE, null),
            new ToolInfo("crm.upsert", COMPENSATABLE, LOOKUP, null),
            new ToolInfo("crm.get", READ_ONLY, NONE, null),
            new ToolInfo("leads.fetch", READ_ONLY, NONE, null));

    private final Map<String, ToolInfo> tools =
            SEED_TOOLS.stream().collect(Collectors.toUnmodifiableMap(ToolInfo::name, Function.identity()));

    @Override
    public Optional<ToolInfo> find(String name) {
        return Optional.ofNullable(name).map(tools::get);
    }
}
