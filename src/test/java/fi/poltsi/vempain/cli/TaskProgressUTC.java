package fi.poltsi.vempain.cli;

import fi.poltsi.vempain.cli.core.TaskProgress;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
	}
}
