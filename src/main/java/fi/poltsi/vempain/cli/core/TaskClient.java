package fi.poltsi.vempain.cli.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONObject;

public class TaskClient {

    private final HttpTransport transport;

    public TaskClient(HttpTransport transport) {
        this.transport = transport;
    }

    public TaskProgress get(SessionStore.Session session, String taskId) throws IOException, InterruptedException {
        return TaskProgress.fromJson(transport.getJson(session, "/tasks/" + taskId));
    }

    public List<TaskProgress> list(SessionStore.Session session) throws IOException, InterruptedException {
        var response = transport.getJsonFlexible(session, "/tasks");
        var items = response.optJSONArray("items");
        if (items == null
            && response.has("tasks")) {
            items = response.optJSONArray("tasks");
        }
        if (items == null) {
            return List.of();
        }
        var tasks = new ArrayList<TaskProgress>(items.length());
        for (int i = 0; i < items.length(); i++) {
            tasks.add(TaskProgress.fromJson(items.getJSONObject(i)));
        }
        return tasks;
    }

    public TaskProgress cancel(SessionStore.Session session, String taskId) throws IOException, InterruptedException {
        return TaskProgress.fromJson(transport.postJson(session, "/tasks/" + taskId + "/cancel", new JSONObject()));
    }

    public void dismiss(SessionStore.Session session, String taskId) throws IOException, InterruptedException {
        transport.delete(session, "/tasks/" + taskId);
    }

    public static String taskId(JSONObject accepted) {
        var id = accepted.optString("task_id", accepted.optString("taskId", ""));
        return id.isBlank() ? null : id;
    }
}
