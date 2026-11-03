package fi.poltsi.vempain.cli.core;

import java.io.IOException;
import java.io.PrintStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import com.googlecode.lanterna.terminal.Terminal;

public class TaskMonitor {

    private final TaskClient taskClient;
    private final PrintStream out;

    public TaskMonitor(TaskClient taskClient, PrintStream out) {
        this.taskClient = taskClient;
        this.out = out;
    }

    public TaskProgress monitor(SessionStore.Session session, String taskId, Duration interval) throws Exception {
        try (Terminal terminal = createTerminal()) {
            var screen = new TerminalScreen(terminal);
            screen.startScreen();
            try {
                return monitorScreen(screen, session, taskId, interval);
            } finally {
                screen.stopScreen();
            }
        }
    }

    /**
     * Opens the terminal the full-screen monitor draws on; tests override this with a virtual terminal.
     */
    protected Terminal createTerminal() throws IOException {
        return new DefaultTerminalFactory().createTerminal();
    }

    private TaskProgress monitorScreen(TerminalScreen screen, SessionStore.Session session, String taskId, Duration interval) throws Exception {
        var progress = taskClient.get(session, taskId);
        while (true) {
            render(screen, progress);
            if (progress.isFinished()) {
                return progress;
            }
            var key = screen.pollInput();
            if (key != null
                && key.getKeyType() == KeyType.Character
                && (key.getCharacter() == 'c'
                    || key.getCharacter() == 'C')) {
                progress = taskClient.cancel(session, taskId);
                continue;
            }
            if (key != null
                && key.getKeyType() == KeyType.Escape) {
                return progress;
            }
            Thread.sleep(Math.max(50, interval.toMillis()));
            // The polls after the user's command are background reads: they do not keep the session alive
            progress = taskClient.get(session, taskId, true);
        }
    }

    private void render(TerminalScreen screen, TaskProgress progress) throws IOException {
        screen.clear();
        var graphics = screen.newTextGraphics();
        graphics.setForegroundColor(TextColor.ANSI.WHITE);
        var lines = lines(progress);
        var size = screen.getTerminalSize();
        for (int i = 0; i < Math.min(lines.size(), size.getRows()); i++) {
            graphics.putString(0, i, truncate(lines.get(i), size.getColumns()));
        }
        screen.refresh();
    }

    /**
     * Follows a task on the plain output instead of the full-screen monitor: every poll that shows a new step count or a new message prints one line,
     * {@code [completed/total] message}, so a scan lists the files as the backend imports them. The first read is the user's command, the polls after it are
     * background requests that do not keep the session alive. Returns the finished task (the caller prints the summary with {@link #printFinal}). A poll only
     * sees the latest step, so files imported faster than the interval show up as a jump of the counter, never as missing output.
     */
    public TaskProgress follow(SessionStore.Session session, String taskId, Duration interval) throws Exception {
        var progress = taskClient.get(session, taskId);
        String printed = null;
        while (true) {
            var line = stepLine(progress);
            if (!line.isBlank()
                && !line.equals(printed)) {
                out.println(line);
                printed = line;
            }
            if (progress.isFinished()) {
                return progress;
            }
            Thread.sleep(Math.max(50, interval.toMillis()));
            progress = taskClient.get(session, taskId, true);
        }
    }

    /**
     * The line {@code follow} prints for a progress snapshot: the step counter when the task has steps, then the message; blank when the task has neither
     */
    public static String stepLine(TaskProgress progress) {
        var counter = progress.totalSteps() > 0 ? "[" + progress.completedSteps() + "/" + progress.totalSteps() + "] " : "";
        var message = progress.message()
            .isBlank() ? "" : progress.message();
        return (counter + message).trim();
    }

    public void printFinal(TaskProgress progress) {
        out.printf("Task %s: %s (%d%%)%n", progress.taskId(), progress.status(), progress.percent());
        if (!progress.message()
            .isBlank()) {
            out.println(progress.message());
        }
        if (!progress.errorMessage()
            .isBlank()) {
            out.println("Error: " + progress.errorMessage());
        }
    }

    public static List<String> lines(TaskProgress progress) {
        var lines = new ArrayList<String>();
        lines.add("Vempain task monitor (press c to cancel, Esc to leave)");
        lines.add(progress.title()
            .isBlank() ? progress.taskId() : progress.title());
        var remaining = progress.remainingTimeText()
            .isEmpty() ? "" : "    Remaining: " + progress.remainingTimeText();
        lines.add("Status: " + progress.status() + "    Progress: " + progress.percent() + "%" + remaining);
        lines.add("Steps: "
                + progress.completedSteps()
                + "/"
                + progress.totalSteps()
                + "    Failed: "
                + progress.failedSteps()
                + "    Reverted: "
                + progress.revertedSteps());
        if (!progress.message()
            .isBlank()) {
            lines.add("Message: " + progress.message());
        }
        if (!progress.errorMessage()
            .isBlank()) {
            lines.add("Error: " + progress.errorMessage());
        }
        return lines;
    }

    private String truncate(String value, int width) {
        if (width <= 0
            || value.length() <= width) {
            return value;
        }
        return value.substring(0, Math.max(0, width - 1)) + "…";
    }
}
