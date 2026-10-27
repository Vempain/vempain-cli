package fi.poltsi.vempain.cli.core;

import java.util.Locale;

import org.json.JSONObject;

/**
 * Progress snapshot of a backend task as returned by {@code GET /tasks/{id}}. {@code estimatedRemainingSeconds} is the backend's extrapolation of the pace of
 * the completed steps over the remaining ones; it is null while the task is queued, before its first completed step, while cancelling and once finished.
 */
public record TaskProgress(String taskId, String type, String title, String status, long totalSteps, long completedSteps, long failedSteps, int percent,
        boolean cancelRequested, long revertedSteps, String message, String errorMessage, Long estimatedRemainingSeconds, JSONObject raw) {

    public static TaskProgress fromJson(JSONObject json) {
        return new TaskProgress(json.optString("task_id", json.optString("taskId", "")), json.optString("type", ""), json.optString("title", ""),
                json.optString("status", "UNKNOWN")
                    .toUpperCase(Locale.ROOT),
                json.optLong("total_steps", json.optLong("totalSteps", 0)), json.optLong("completed_steps", json.optLong("completedSteps", 0)),
                json.optLong("failed_steps", json.optLong("failedSteps", 0)), json.optInt("percent", 0),
                json.optBoolean("cancel_requested", json.optBoolean("cancelRequested", false)),
                json.optLong("reverted_steps", json.optLong("revertedSteps", 0)), json.optString("message", ""),
                json.optString("error_message", json.optString("errorMessage", "")),
                optionalLong(json, "estimated_remaining_seconds", "estimatedRemainingSeconds"), json);
    }

    private static Long optionalLong(JSONObject json, String... names) {
        for (var name : names) {
            if (json.has(name)
                && !json.isNull(name)) {
                return json.optLong(name);
            }
        }
        return null;
    }

    /**
     * The remaining time estimate as a short text ("< 1 min", "~2 min", "~1 h", "~1 h 5 min"), or an empty string when the backend gave no estimate.
     */
    public String remainingTimeText() {
        if (estimatedRemainingSeconds == null
            || estimatedRemainingSeconds < 0) {
            return "";
        }
        if (estimatedRemainingSeconds < 60) {
            return "< 1 min";
        }
        long totalMinutes = Math.round(estimatedRemainingSeconds / 60.0);
        if (totalMinutes < 60) {
            return "~" + totalMinutes + " min";
        }
        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;
        return minutes == 0 ? "~" + hours + " h" : "~" + hours + " h " + minutes + " min";
    }

    public boolean isFinished() {
        return switch (status) {
            case "COMPLETED", "FAILED", "CANCELLED" -> true;
            default -> false;
        };
    }
}
