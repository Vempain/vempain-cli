package fi.poltsi.vempain.cli.core;

import java.util.Locale;

import org.json.JSONObject;

public record TaskProgress(String taskId, String type, String title, String status, long totalSteps, long completedSteps, long failedSteps, int percent,
        boolean cancelRequested, long revertedSteps, String message, String errorMessage, JSONObject raw) {

    public static TaskProgress fromJson(JSONObject json) {
        return new TaskProgress(json.optString("task_id", json.optString("taskId", "")), json.optString("type", ""), json.optString("title", ""),
                json.optString("status", "UNKNOWN")
                    .toUpperCase(Locale.ROOT),
                json.optLong("total_steps", json.optLong("totalSteps", 0)), json.optLong("completed_steps", json.optLong("completedSteps", 0)),
                json.optLong("failed_steps", json.optLong("failedSteps", 0)), json.optInt("percent", 0),
                json.optBoolean("cancel_requested", json.optBoolean("cancelRequested", false)),
                json.optLong("reverted_steps", json.optLong("revertedSteps", 0)), json.optString("message", ""),
                json.optString("error_message", json.optString("errorMessage", "")), json);
    }

    public boolean isFinished() {
        return switch (status) {
            case "COMPLETED", "FAILED", "CANCELLED" -> true;
            default -> false;
        };
    }
}
