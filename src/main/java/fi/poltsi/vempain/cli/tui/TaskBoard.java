package fi.poltsi.vempain.cli.tui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import fi.poltsi.vempain.cli.core.HttpStatusException;
import fi.poltsi.vempain.cli.core.SessionStore;
import fi.poltsi.vempain.cli.core.TaskClient;
import fi.poltsi.vempain.cli.core.TaskProgress;

/**
 * Collects the background tasks that are still running on every backend the user is logged in to. Finished tasks (completed, failed, cancelled) are left out; a
 * backend whose task list cannot be read is reported with its error instead.
 * <p>
 * A backend that rejects the session (401 or 403) is not asked again with that token: the board remembers the rejected token and reports the backend as logged
 * out until a new login stores another token.
 */
public class TaskBoard {

    private static final List<String> BACKENDS = List.of(SessionStore.BACKEND_FILE, SessionStore.BACKEND_ADMIN);
    static final String SESSION_REJECTED = "session no longer valid, log in again";

    private final SessionStore sessionStore;
    private final TaskClient taskClient;
    /** Backend -> the token its backend answered with 401/403; polling resumes once the stored token differs. */
    private final Map<String, String> rejectedTokens = new ConcurrentHashMap<>();

    public TaskBoard(SessionStore sessionStore, TaskClient taskClient) {
        this.sessionStore = sessionStore;
        this.taskClient = taskClient;
    }

    /**
     * Polls {@code GET /tasks} of each logged-in backend. Backends without a stored session are not part of the result.
     */
    public List<BackendTasks> refresh() {
        var result = new ArrayList<BackendTasks>();
        for (var backend : BACKENDS) {
            var session = sessionStore.load(backend);
            if (session == null) {
                rejectedTokens.remove(backend);
                continue;
            }
            if (session.token()
                .equals(rejectedTokens.get(backend))) {
                result.add(new BackendTasks(backend, List.of(), SESSION_REJECTED));
                continue;
            }
            try {
                // A poll, not a user action: it must not keep an idle session alive
                var running = taskClient.list(session, true)
                    .stream()
                    .filter(task -> !task.isFinished())
                    .toList();
                rejectedTokens.remove(backend);
                result.add(new BackendTasks(backend, running, null));
            } catch (InterruptedException e) {
                Thread.currentThread()
                    .interrupt();
                return result;
            } catch (HttpStatusException e) {
                if (e.isSessionRejected()) {
                    rejectedTokens.put(backend, session.token());
                    result.add(new BackendTasks(backend, List.of(), SESSION_REJECTED + " (HTTP " + e.statusCode() + ")"));
                } else {
                    result.add(new BackendTasks(backend, List.of(), e.getMessage()));
                }
            } catch (Exception e) {
                var message = e.getMessage() == null ? e.getClass()
                    .getSimpleName() : e.getMessage();
                result.add(new BackendTasks(backend, List.of(), message));
            }
        }
        return result;
    }

    /**
     * @return whether the backend rejected the currently stored session, so its tasks are not polled any more
     */
    public boolean isSessionRejected(String backend) {
        var session = sessionStore.load(backend);
        return session != null
               && session.token()
                   .equals(rejectedTokens.get(backend));
    }

    public static List<String> lines(List<BackendTasks> snapshot) {
        if (snapshot.isEmpty()) {
            return List.of("Log in to the file or admin backend to follow its tasks.");
        }
        var lines = new ArrayList<String>();
        var idleBackends = new ArrayList<String>();
        var runningTasks = 0;
        for (var backend : snapshot) {
            if (backend.error() != null) {
                lines.add("[" + backend.backend() + "] tasks unavailable: " + backend.error());
                continue;
            }
            if (backend.tasks()
                .isEmpty()) {
                idleBackends.add(backend.backend());
            }
            for (var task : backend.tasks()) {
                lines.add(line(backend.backend(), task));
                runningTasks++;
            }
        }
        if (runningTasks == 0
            && !idleBackends.isEmpty()) {
            lines.add("No running tasks on " + String.join(" or ", idleBackends) + ".");
        }
        return lines;
    }

    private static String line(String backend, TaskProgress task) {
        var label = !task.title()
            .isBlank() ? task.title()
                    : !task.type()
                        .isBlank() ? task.type() : task.taskId();
        var steps = task.totalSteps() > 0 ? " (" + task.completedSteps() + "/" + task.totalSteps() + ")" : "";
        var remaining = task.remainingTimeText()
            .isEmpty() ? "" : "  " + task.remainingTimeText() + " left";
        return String.format("[%s] %-10s %3d%%  %s%s%s", backend, task.status(), task.percent(), label, steps, remaining);
    }

    public record BackendTasks(String backend, List<TaskProgress> tasks, String error) {}
}
