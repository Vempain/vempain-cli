package fi.poltsi.vempain.cli.tui;

import java.io.PrintStream;
import java.time.Duration;

import fi.poltsi.vempain.cli.core.SessionStore;
import fi.poltsi.vempain.cli.core.TaskClient;
import fi.poltsi.vempain.cli.core.TaskMonitor;
import fi.poltsi.vempain.cli.core.TaskProgress;

/**
 * Task monitor used inside the TUI shell. The TUI already shows every running task in its tasks window, so instead of taking over the terminal with the
 * full-screen monitor this one refreshes that window and returns the current progress right away.
 */
public class TuiTaskMonitor extends TaskMonitor {

    private final TaskClient taskClient;
    private final PrintStream out;
    private final Runnable refreshTasks;

    public TuiTaskMonitor(TaskClient taskClient, PrintStream out, Runnable refreshTasks) {
        super(taskClient, out);
        this.taskClient = taskClient;
        this.out = out;
        this.refreshTasks = refreshTasks;
    }

    @Override
    public TaskProgress monitor(SessionStore.Session session, String taskId, Duration interval) throws Exception {
        var progress = taskClient.get(session, taskId);
        refreshTasks.run();
        out.println("Task " + taskId + " is followed in the running tasks window.");
        return progress;
    }
}
