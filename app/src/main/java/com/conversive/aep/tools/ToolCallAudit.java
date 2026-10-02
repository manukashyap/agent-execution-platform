package com.conversive.aep.tools;

import com.conversive.aep.common.ExecutionId;
import com.conversive.aep.common.Phase;
import com.conversive.aep.common.TenantId;
import java.util.List;
import java.util.Objects;

/** Append-only {@code tool_call_audit}: one row per gateway attempt; arguments only as a SHA-256. */
public interface ToolCallAudit {

    String SUCCEEDED = "SUCCEEDED";
    String FAILED = "FAILED";
    String IN_PROGRESS = "IN_PROGRESS";

    void record(Entry entry);

    List<Entry> findByExecution(TenantId tenantId, ExecutionId executionId);

    /** @param effectKey set for guarded (non-READ_ONLY) tools */
    record Entry(TenantId tenantId, ExecutionId executionId, String nodeId, int callIndex, Phase phase, int attempt,
                 String toolName, String argsSha256, String outcome, String errorCode, long latencyMs,
                 String effectKey) {

        public Entry {
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(executionId, "executionId");
            Objects.requireNonNull(outcome, "outcome");
        }
    }
}
