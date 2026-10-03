package com.conversive.aep.router;

import com.conversive.aep.common.ExecutionId;
import com.conversive.aep.common.TenantId;
import java.util.List;

/** Sink for {@code llm_call} rows; the JDBC implementation lives in {@code router.persistence}. */
public interface LlmCallRecorder {

    void record(LlmCallRecord call);

    List<LlmCallRecord> findByExecution(TenantId tenantId, ExecutionId executionId);
}
