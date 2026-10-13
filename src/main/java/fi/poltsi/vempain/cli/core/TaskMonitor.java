package fi.poltsi.vempain.cli.core;

import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import com.googlecode.lanterna.terminal.Terminal;

import java.io.IOException;
import java.io.PrintStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class TaskMonitor {

	private final TaskClient  taskClient;
	private final PrintStream out;

	public TaskMonitor(TaskClient taskClient, PrintStream out) {
		this.taskClient = taskClient;
		this.out        = out;
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
			if (key != null && key.getKeyType() == KeyType.Character && (key.getCharacter() == 'c' || key.getCharacter() == 'C')) {
				progress = taskClient.cancel(session, taskId);
				continue;
			}
			if (key != null && key.getKeyType() == KeyType.Escape) {
				return progress;
			}
			Thread.sleep(Math.max(50, interval.toMillis()));
			progress = taskClient.get(session, taskId);
		}
	}

	private void render(TerminalScreen screen, TaskProgress progress) throws IOException {
		screen.clear();
		var graphics = screen.newTextGraphics();
		graphics.setForegroundColor(TextColor.ANSI.WHITE);
		var lines = lines(progress);
		var size  = screen.getTerminalSize();
		for (int i = 0; i < Math.min(lines.size(), size.getRows()); i++) {
			graphics.putString(0, i, truncate(lines.get(i), size.getColumns()));
		}
		screen.refresh();
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
		lines.add("Status: " + progress.status() + "    Progress: " + progress.percent() + "%");
		lines.add("Steps: " + progress.completedSteps() + "/" + progress.totalSteps() + "    Failed: " + progress.failedSteps()
				  + "    Reverted: " + progress.revertedSteps());
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
		if (width <= 0 || value.length() <= width) {
			return value;
		}
		return value.substring(0, Math.max(0, width - 1)) + "…";
	}
}
