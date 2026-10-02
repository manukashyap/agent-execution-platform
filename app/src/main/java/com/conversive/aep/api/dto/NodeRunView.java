package com.conversive.aep.api.dto;

import com.conversive.aep.execution.persistence.NodeRunRecord;
import java.time.Instant;

public record NodeRunView(String nodeId, int callIndex, String phase, int attempt, String status,
                          String errorCode, String errorMessage, Instant startedAt, Instant endedAt) {

    public static NodeRunView of(NodeRunRecord r) {
        return new NodeRunView(r.nodeId(), r.callIndex(), r.phase(), r.attempt(), r.status(), r.errorCode(),
                r.errorMessage(), r.startedAt(), r.endedAt());
    }
}
