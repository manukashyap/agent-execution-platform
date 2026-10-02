package com.conversive.aep.engine.replay;

import com.conversive.aep.common.ExecutionId;
import com.conversive.aep.common.TenantId;
import com.conversive.aep.engine.workflow.DagInterpreterWorkflow;
import io.temporal.client.WorkflowClient;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Locations of the recorded workflow histories and the writer used by the recorder ITs. */
final class HistoryFiles {

    static final String HAPPY_PATH = "happy-path-condition-foreach.json";
    static final String SAGA = "saga-compensates.json";
    static final String RECORD_ENV = "AEP_RECORD_HISTORIES";

    private static final Path SOURCE_DIR = Path.of("src/test/resources/histories");

    private HistoryFiles() {
    }

    /** Writes the finished run's history into the source tree (the working dir of a Gradle test is {@code app/}). */
    static void record(WorkflowClient client, TenantId tenant, ExecutionId id, String fileName) {
        String workflowId = DagInterpreterWorkflow.workflowId(tenant.value(), id.toString());
        String json = client.fetchHistory(workflowId).toJson(true);
        try {
            Files.createDirectories(SOURCE_DIR);
            Files.writeString(SOURCE_DIR.resolve(fileName), json + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
