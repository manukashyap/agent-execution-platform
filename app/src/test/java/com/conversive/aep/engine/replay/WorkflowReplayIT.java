package com.conversive.aep.engine.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.conversive.aep.engine.workflow.DagInterpreterWorkflowImpl;
import io.temporal.testing.WorkflowReplayer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Replays histories recorded from the real {@link DagInterpreterWorkflowImpl} against the current code. A failure
 * means a workflow change is not backward compatible with runs already in flight (gate it with
 * {@code Workflow.getVersion}, or re-record deliberately, see {@link RecordHappyPathHistoryIT}).
 */
class WorkflowReplayIT {

    @Test
    void happyPathWithConditionAndForEachReplaysDeterministically() throws Exception {
        WorkflowReplayer.replayWorkflowExecution(history(HistoryFiles.HAPPY_PATH), DagInterpreterWorkflowImpl.class);
    }

    @Test
    void sagaThatCompensatesReplaysDeterministically() throws Exception {
        WorkflowReplayer.replayWorkflowExecution(history(HistoryFiles.SAGA), DagInterpreterWorkflowImpl.class);
    }

    @Test
    void recordedHistoriesContainTheScenariosTheyClaim() throws IOException {
        assertThat(history(HistoryFiles.HAPPY_PATH)).contains("EVENT_TYPE_ACTIVITY_TASK_COMPLETED");
        assertThat(history(HistoryFiles.SAGA)).contains("\"CompensateNode\"");
    }

    @Test
    void aReplayOfAHistoryWithADifferentActivityTypeFails() throws IOException {
        String corrupted = history(HistoryFiles.HAPPY_PATH).replace("\"RunNode\"", "\"RenamedNode\"");
        assertThat(corrupted).isNotEqualTo(history(HistoryFiles.HAPPY_PATH));
        assertThatThrownBy(() ->
                WorkflowReplayer.replayWorkflowExecution(corrupted, DagInterpreterWorkflowImpl.class))
                .isInstanceOf(Exception.class);
    }

    private static String history(String name) throws IOException {
        try (InputStream in = WorkflowReplayIT.class.getResourceAsStream("/histories/" + name)) {
            if (in == null) {
                throw new IOException("missing recorded history " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
