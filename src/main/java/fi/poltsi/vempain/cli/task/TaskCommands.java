package fi.poltsi.vempain.cli.task;

import java.io.PrintStream;
import java.time.Duration;

import org.json.JSONArray;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;

import fi.poltsi.vempain.cli.core.SessionStore;
import fi.poltsi.vempain.cli.core.TaskClient;
import fi.poltsi.vempain.cli.core.TaskMonitor;

public class TaskCommands {

    private final SessionStore sessionStore;
    private final TaskClient taskClient;
    private final TaskMonitor taskMonitor;
    private final PrintStream out;

    public TaskCommands(SessionStore sessionStore, TaskClient taskClient, TaskMonitor taskMonitor, PrintStream out) {
        this.sessionStore = sessionStore;
        this.taskClient = taskClient;
        this.taskMonitor = taskMonitor;
        this.out = out;
    }

    public void list(ListCommand command) throws Exception {
        var session = requireSession(command.backend);
        var tasks = taskClient.list(session);
        if (command.raw) {
            var result = new JSONArray();
            tasks.forEach(task -> result.put(task.raw()));
            out.println(result.toString(2));
            return;
        }
        tasks.forEach(task -> out.printf("%s %-12s %3d%% %s%s%n", task.taskId(), task.status(), task.percent(), task.title(), task.remainingTimeText()
            .isEmpty() ? "" : "  " + task.remainingTimeText() + " left"));
    }

    public void monitor(MonitorCommand command) throws Exception {
        var session = requireSession(command.backend);
        var progress = taskMonitor.monitor(session, command.taskId, Duration.ofMillis(Math.max(100, command.intervalMs)));
        taskMonitor.printFinal(progress);
    }

    public void cancel(CancelCommand command) throws Exception {
        var progress = taskClient.cancel(requireSession(command.backend), command.taskId);
        taskMonitor.printFinal(progress);
    }

    private SessionStore.Session requireSession(String backend) {
        var normalized = SessionStore.BACKEND_ADMIN.equalsIgnoreCase(backend) ? SessionStore.BACKEND_ADMIN : SessionStore.BACKEND_FILE;
        var session = sessionStore.load(normalized);
        if (session == null) {
            throw new IllegalStateException("Not logged in for backend '" + normalized + "'.");
        }
        return session;
    }

    @Parameters(commandDescription = "List tasks for the current user")
    public static class ListCommand {

        @Parameter(names = { "--backend", "-b" }, description = "Backend name: file|admin")
        public String backend = SessionStore.BACKEND_FILE;
        @Parameter(names = "--raw", description = "Print task responses as JSON")
        public boolean raw;
    }

    @Parameters(commandDescription = "Monitor a background task in the full-screen terminal monitor")
    public static class MonitorCommand {

        @Parameter(names = "--task-id", required = true, description = "Task identifier")
        public String taskId;
        @Parameter(names = { "--backend", "-b" }, description = "Backend name: file|admin")
        public String backend = SessionStore.BACKEND_FILE;
        @Parameter(names = "--interval-ms", description = "Polling interval in milliseconds")
        public long intervalMs = 1000;
    }

    @Parameters(commandDescription = "Request cancellation of a background task")
    public static class CancelCommand {

        @Parameter(names = "--task-id", required = true, description = "Task identifier")
        public String taskId;
        @Parameter(names = { "--backend", "-b" }, description = "Backend name: file|admin")
        public String backend = SessionStore.BACKEND_FILE;
    }
}
