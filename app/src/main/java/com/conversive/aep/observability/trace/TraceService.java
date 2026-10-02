package com.conversive.aep.observability.trace;

import com.conversive.aep.common.ErrorCodes;
import com.conversive.aep.common.ExecutionId;
import com.conversive.aep.common.NonRetryableError;
import com.conversive.aep.common.TenantId;
import com.conversive.aep.observability.persistence.TraceRepository;
import com.conversive.aep.observability.persistence.TraceRows.ExecutionRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TraceService {

    private final TraceRepository repository;

    public TraceService(TraceRepository repository) {
        this.repository = repository;
    }

    /**
     * One consistent snapshot (repeatable read) of the execution's rows.
     *
     * @throws NonRetryableError {@code NOT_FOUND} when the execution does not exist for this tenant
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ExecutionTrace trace(TenantId tenant, ExecutionId id) {
        ExecutionRow execution = repository.execution(tenant, id).orElseThrow(
                () -> new NonRetryableError(ErrorCodes.NOT_FOUND, "execution " + id.value() + " not found"));
        TraceData data = new TraceData(execution,
                repository.nodeTypes(tenant, execution.workflowId(), execution.version()),
                repository.nodeRuns(tenant, id), repository.llmCalls(tenant, id), repository.toolCalls(tenant, id),
                repository.sideEffects(tenant, id), repository.budget(tenant, id));
        return TraceAssembler.assemble(data);
    }
}
