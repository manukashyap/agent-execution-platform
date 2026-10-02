package com.conversive.aep.definition;

import com.conversive.aep.common.IdempotencyMode;
import com.conversive.aep.common.Reversibility;
import java.util.Objects;

/** @param compensation default compensating tool for {@code COMPENSATABLE} tools; may be null */
public record ToolInfo(String name, Reversibility reversibility, IdempotencyMode idempotency, String compensation) {

    public ToolInfo {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(reversibility, "reversibility");
        Objects.requireNonNull(idempotency, "idempotency");
    }

    public boolean readOnly() {
        return reversibility == Reversibility.READ_ONLY;
    }
}
