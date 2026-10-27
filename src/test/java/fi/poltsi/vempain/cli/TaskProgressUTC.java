package fi.poltsi.vempain.cli;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import fi.poltsi.vempain.cli.core.TaskProgress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskProgressUTC {

    @Test
    void parsesSnakeCaseAndRecognizesTerminalStatuses() {
        var progress = TaskProgress.fromJson(new JSONObject("""
                {"task_id":"abc","type":"SCAN_DIRECTORIES","title":"Scan","status":"RUNNING",
                 "total_steps":10,"completed_steps":4,"failed_steps":1,"percent":40,
                 "cancel_requested":false,"reverted_steps":0,"message":"Reading","error_message":""}
                """));

        assertEquals("abc", progress.taskId());
        assertEquals("RUNNING", progress.status());
        assertEquals(4, progress.completedSteps());
        assertFalse(progress.isFinished());
        assertTrue(TaskProgress.fromJson(new JSONObject("{\"status\":\"CANCELLED\"}"))
            .isFinished());
        assertNull(progress.estimatedRemainingSeconds());
        assertEquals("", progress.remainingTimeText());
    }

    @Test
    void parsesTheRemainingTimeEstimateAndFormatsItCoarsely() {
        assertEquals(95L, TaskProgress.fromJson(new JSONObject("{\"estimated_remaining_seconds\":95}"))
            .estimatedRemainingSeconds());
        assertNull(TaskProgress.fromJson(new JSONObject("{\"estimated_remaining_seconds\":null}"))
            .estimatedRemainingSeconds());

        assertEquals("< 1 min", withEstimate(0).remainingTimeText());
        assertEquals("< 1 min", withEstimate(59).remainingTimeText());
        assertEquals("~1 min", withEstimate(60).remainingTimeText());
        assertEquals("~2 min", withEstimate(95).remainingTimeText());
        assertEquals("~59 min", withEstimate(59 * 60 + 20).remainingTimeText());
        assertEquals("~1 h", withEstimate(3599).remainingTimeText());
        assertEquals("~2 h 5 min", withEstimate(2 * 3600 + 5 * 60).remainingTimeText());
        assertEquals("", withEstimate(-1).remainingTimeText());
    }

    private static TaskProgress withEstimate(long seconds) {
        return TaskProgress.fromJson(new JSONObject().put("status", "RUNNING")
            .put("estimated_remaining_seconds", seconds));
    }
}
