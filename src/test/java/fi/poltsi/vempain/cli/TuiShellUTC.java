package fi.poltsi.vempain.cli;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;
import com.sun.net.httpserver.HttpServer;
import fi.poltsi.vempain.cli.core.HttpTransport;
import fi.poltsi.vempain.cli.core.PasswordReader;
import fi.poltsi.vempain.cli.core.SessionStore;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class TuiShellUTC {

	private static final int      COLUMNS = 120;
	private static final int      ROWS    = 30;
	private static final Duration TIMEOUT = Duration.ofSeconds(10);
	private static final int      TRACKS  = 40;
	private static final String   LAST    = "total elements: " + TRACKS;

	@TempDir
	Path tempHome;

	private final AtomicReference<Throwable> shellFailure = new AtomicReference<>();
	private final AtomicReference<String>    loginBody    = new AtomicReference<>();

	private String                 previousHome;
	private HttpServer             server;
	private DefaultVirtualTerminal terminal;
	private Thread                 shellThread;

	@BeforeEach
	void setUp() throws IOException {
		previousHome = System.getProperty("user.home");
		System.setProperty("user.home", tempHome.toString());
		server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		server.createContext("/api/login", exchange -> {
			loginBody.set(new String(exchange.getRequestBody()
											 .readAllBytes(), StandardCharsets.UTF_8));
			send(exchange, "[{\"token\":\"file-jwt\"}]");
		});
		server.createContext("/api/version", exchange -> send(exchange, "{\"version\":\"0.0.1-SNAPSHOT\",\"service\":\"test\"}"));
		server.createContext("/api/tasks", exchange -> {
			if (exchange.getRequestURI()
						.getPath()
						.equals("/api/tasks")) {
				send(exchange, """
						[
						  {"task_id":"task-1","type":"SCAN","title":"Scanning originals","status":"RUNNING","percent":40},
						  {"task_id":"task-0","type":"PUBLISH_MUSIC","title":"Music data set","status":"COMPLETED","percent":100}
						]
						""");
				return;
			}
			send(exchange, "{\"task_id\":\"task-1\",\"title\":\"Scanning originals\",\"status\":\"RUNNING\",\"percent\":40}");
		});
		server.createContext("/api/files/music/paged", exchange -> {
			var content = new org.json.JSONArray();
			for (int id = 1; id <= TRACKS; id++) {
				content.put(new JSONObject().put("id", id)
											.put("filename", track(id))
											.put("file_type", "MUSIC")
											.put("filesize", 1000)
											.put("created", "2026-10-12T10:00:00Z"));
			}
			send(exchange, new JSONObject().put("content", content)
										   .put("page", 0)
										   .put("total_pages", 1)
										   .put("total_elements", TRACKS)
										   .toString());
		});
		server.start();
		terminal = new DefaultVirtualTerminal(new TerminalSize(COLUMNS, ROWS));
	}

	@AfterEach
	void tearDown() throws InterruptedException {
		if (shellThread != null && shellThread.isAlive()) {
			shellThread.interrupt();
			shellThread.join(TIMEOUT.toMillis());
		}
		server.stop(0);
		System.setProperty("user.home", previousHome);
	}

	@Test
	void shell_showsSessionAndRunningTasksWindowsAndFollowsLogin() throws Exception {
		PasswordReader consoleReader = prompt -> fail("The TUI must ask the password in its own dialog");
		startShell(new SessionStore(), consoleReader);

		var initial = awaitScreen(screen -> shows(screen, "Log in to the file or admin backend"));
		assertWindowCorners(initial);
		assertEquals("not logged in", stateOf(initial, "File backend"));
		assertEquals("not logged in", stateOf(initial, "Admin backend"));
		assertTrue(initial.stream()
						  .anyMatch(line -> line.contains("vempain-unauthicated> ")));

		type("files-l");
		press(KeyType.Tab);
		awaitScreen(screen -> shows(screen, "vempain-unauthicated> files-list "));
		press(KeyType.Backspace, "files-list ".length());

		type("login --backend file --url " + baseUrl() + " --username admin");
		press(KeyType.Enter);
		awaitScreen(screen -> shows(screen, "Enter: log in   Esc: cancel"));
		type("secret");
		press(KeyType.Enter);

		var loggedIn = awaitScreen(screen -> shows(screen, "[file] RUNNING") && "logged in".equals(stateOf(screen, "File backend")));
		assertWindowCorners(loggedIn);
		assertEquals("not logged in", stateOf(loggedIn, "Admin backend"));
		assertTrue(loggedIn.stream()
						   .anyMatch(line -> line.contains(baseUrl())), "the session window shows the file backend URL");
		assertTrue(loggedIn.stream()
						   .anyMatch(line -> line.contains("Login successful")), "command output is shown in the output window");
		assertTrue(loggedIn.stream()
						   .anyMatch(line -> line.contains("[file] RUNNING     40%  Scanning originals")));
		assertFalse(loggedIn.stream()
							.anyMatch(line -> line.contains("Music data set")), "finished tasks are not listed");
		assertEquals("secret", new JSONObject(loginBody.get()).getString("password"));
		assertNotNull(new SessionStore().load(SessionStore.BACKEND_FILE));

		press(KeyType.ArrowUp);
		awaitScreen(screen -> shows(screen, "vempain-file> login --backend file"));
		press(KeyType.ArrowDown);

		type("task-monitor --task-id task-1");
		press(KeyType.Enter);
		awaitScreen(screen -> shows(screen, "Task task-1 is followed in the running tasks window.") && shows(screen, "Task task-1: RUNNING (40%)"));

		type("exit");
		press(KeyType.Enter);
		shellThread.join(TIMEOUT.toMillis());
		assertFalse(shellThread.isAlive(), "exit closes the TUI");
		assertNull(shellFailure.get());
	}

	@Test
	void shell_cancelledPasswordDialogReportsErrorAndKeepsRunning() throws Exception {
		startShell(new SessionStore(), prompt -> fail("The TUI must ask the password in its own dialog"));
		awaitScreen(screen -> shows(screen, "Log in to the file or admin backend"));

		type("login --backend file --url " + baseUrl() + " --username admin");
		press(KeyType.Enter);
		awaitScreen(screen -> shows(screen, "Enter: log in   Esc: cancel"));
		press(KeyType.Escape);

		var screen = awaitScreen(lines -> shows(lines, "Error: Login cancelled"));
		assertEquals("not logged in", stateOf(screen, "File backend"));
		assertNull(loginBody.get());

		type("quit");
		press(KeyType.Enter);
		shellThread.join(TIMEOUT.toMillis());
		assertFalse(shellThread.isAlive());
	}

	@Test
	void shell_outputFollowsNewLinesAndPageKeysScrollIt() throws Exception {
		var sessionStore = new SessionStore();
		sessionStore.save(SessionStore.BACKEND_FILE, baseUrl(), "file-jwt");
		startShell(sessionStore, prompt -> fail("no password expected"));
		awaitScreen(screen -> shows(screen, "vempain-file> "));

		type("files-list --type music");
		press(KeyType.Enter);
		var end = awaitScreen(screen -> shows(screen, LAST));
		assertFalse(shows(end, track(1)), "the output is longer than its window, so the first lines are scrolled out of view");
		var firstAtEnd = firstVisibleTrack(end);

		press(KeyType.PageUp);
		var pageUp = awaitScreen(screen -> firstVisibleTrack(screen) < firstAtEnd);
		assertFalse(shows(pageUp, LAST), "PgUp moves the view away from the last line");
		assertFalse(shows(pageUp, track(TRACKS)));

		press(KeyType.PageUp, 5);
		awaitScreen(screen -> shows(screen, "vempain-file> files-list --type music") && shows(screen, track(1)));

		press(KeyType.PageDown, 5);
		var back = awaitScreen(screen -> shows(screen, LAST));
		assertEquals(firstAtEnd, firstVisibleTrack(back), "PgDn returns to the end of the output");

		type("exit");
		press(KeyType.Enter);
		shellThread.join(TIMEOUT.toMillis());
		assertFalse(shellThread.isAlive());
	}

	@Test
	void shell_usageIsShownInOutputWindowNotOnTheTerminal() throws Exception {
		var stdout   = new java.io.ByteArrayOutputStream();
		var original = System.out;
		System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
		try {
			startShell(new SessionStore(), prompt -> fail("no password expected"));
			awaitScreen(screen -> shows(screen, "vempain-unauthicated> "));

			type("files-list --unknown");
			press(KeyType.Enter);
			awaitScreen(screen -> shows(screen, "Usage: vempain-file-cli") || pageUp());

			type("exit");
			press(KeyType.Enter);
			shellThread.join(TIMEOUT.toMillis());
			assertFalse(shellThread.isAlive());
		} finally {
			System.setOut(original);
		}
		assertFalse(stdout.toString(StandardCharsets.UTF_8)
						  .contains("Usage"), "usage must not be written over the TUI");
	}

	/**
	 * Scrolls the output one page up; used inside a screen condition to search earlier output.
	 */
	private boolean pageUp() {
		press(KeyType.PageUp);
		return false;
	}

	private static int firstVisibleTrack(List<String> screen) {
		for (int id = 1; id <= TRACKS; id++) {
			if (shows(screen, track(id))) {
				return id;
			}
		}
		return Integer.MAX_VALUE;
	}

	private static String track(int id) {
		return String.format("track-%02d.mp3", id);
	}

	private void startShell(SessionStore sessionStore, PasswordReader passwordReader) {
		var silent = new PrintStream(OutputStream.nullOutputStream());
		var router = new CommandRouter(sessionStore, new HttpTransport(), silent, silent, passwordReader);
		shellThread = Thread.ofPlatform()
							.name("tui-shell-test")
							.start(() -> {
								try {
									router.runTuiShell(terminal, false, Duration.ofMillis(200));
								} catch (Throwable e) {
									shellFailure.set(e);
								}
							});
	}

	/**
	 * The session window must sit in the upper left corner and the running tasks window right of it, up to the right edge.
	 */
	private void assertWindowCorners(List<String> screen) {
		var top = screen.getFirst();
		assertTrue(top.indexOf("Session") >= 0 && top.indexOf("Session") < 5, "session window in the upper left corner: " + top);
		var sessionEnd = top.indexOf('┐');
		assertTrue(sessionEnd > 30, "session window border: " + top);
		assertEquals('┌', top.charAt(sessionEnd + 1), "running tasks window starts right of the session window: " + top);
		assertTrue(top.indexOf("Running tasks") > sessionEnd, "running tasks window in the upper right part: " + top);
		assertEquals('┐', top.charAt(COLUMNS - 1), "running tasks window reaches the right edge: " + top);
	}

	/**
	 * @return the login state printed right of {@code backendName} in the session window
	 */
	private static String stateOf(List<String> screen, String backendName) {
		for (var line : screen) {
			var index = line.indexOf(backendName);
			if (index >= 0) {
				var rest = line.substring(index + backendName.length());
				var end  = rest.indexOf('│');
				return (end >= 0 ? rest.substring(0, end) : rest).trim();
			}
		}
		return null;
	}

	private static boolean shows(List<String> screen, String text) {
		return screen.stream()
					 .anyMatch(line -> line.contains(text));
	}

	private List<String> awaitScreen(Predicate<List<String>> condition) throws InterruptedException {
		var          deadline = System.nanoTime() + TIMEOUT.toNanos();
		List<String> screen   = List.of();
		while (System.nanoTime() < deadline) {
			if (shellFailure.get() != null) {
				fail("TUI shell failed", shellFailure.get());
			}
			var previous = screen;
			screen = screen();
			// Only a screen read twice in a row counts, so a read taken while Lanterna redraws is not mistaken for the result.
			if (screen.equals(previous) && condition.test(screen)) {
				return screen;
			}
			Thread.sleep(25);
		}
		fail("Screen did not reach the expected state:\n" + String.join("\n", screen));
		return screen;
	}

	private List<String> screen() {
		var lines = new ArrayList<String>(ROWS);
		for (int row = 0; row < ROWS; row++) {
			var line = new StringBuilder(COLUMNS);
			for (int column = 0; column < COLUMNS; column++) {
				line.append(terminal.getCharacter(column, row)
									.getCharacterString());
			}
			lines.add(line.toString());
		}
		return lines;
	}

	private void type(String text) {
		text.chars()
			.forEach(character -> terminal.addInput(new KeyStroke((char) character, false, false)));
	}

	private void press(KeyType key) {
		press(key, 1);
	}

	private void press(KeyType key, int times) {
		for (int i = 0; i < times; i++) {
			terminal.addInput(new KeyStroke(key));
		}
	}

	private String baseUrl() {
		return "http://localhost:" + server.getAddress()
										   .getPort() + "/api";
	}

	private static void send(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
		var bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders()
				.add("Content-Type", "application/json");
		exchange.sendResponseHeaders(200, bytes.length);
		exchange.getResponseBody()
				.write(bytes);
		exchange.close();
	}
}
