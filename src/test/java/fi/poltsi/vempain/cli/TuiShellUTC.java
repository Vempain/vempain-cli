package fi.poltsi.vempain.cli;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.terminal.Terminal;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;
import com.sun.net.httpserver.HttpServer;

import fi.poltsi.vempain.cli.core.CommandHistory;
import fi.poltsi.vempain.cli.core.HttpTransport;
import fi.poltsi.vempain.cli.core.PasswordReader;
import fi.poltsi.vempain.cli.core.SessionStore;
import fi.poltsi.vempain.cli.core.TaskClient;
import fi.poltsi.vempain.cli.tui.TuiShell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class TuiShellUTC {

    private static final int COLUMNS = 120;
    private static final int ROWS = 30;
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int TRACKS = 40;
    private static final String LAST = "total elements: " + TRACKS;

    @TempDir
    Path tempHome;

    private final AtomicReference<Throwable> shellFailure = new AtomicReference<>();
    private final AtomicReference<String> loginBody = new AtomicReference<>();

    private String previousHome;
    private HttpServer server;
    private DefaultVirtualTerminal terminal;
    private Thread shellThread;

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
        server.createContext("/many/api/tasks", exchange -> {
            var tasks = new org.json.JSONArray();
            for (int id = 1; id <= 12; id++) {
                tasks.put(new JSONObject().put("task_id", "many-" + id)
                    .put("title", id == 1 ? "A very long title ".repeat(8) : "Task " + id)
                    .put("status", "RUNNING")
                    .put("percent", id));
            }
            send(exchange, tasks.toString());
        });
        server.createContext("/api/tags/all/replace", exchange -> {
            exchange.getRequestBody()
                .readAllBytes();
            send(exchange, "{\"task_id\":\"task-1\",\"type\":\"TAG_REPLACE_ACROSS_ALL\",\"status\":\"QUEUED\",\"total_steps\":3}");
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
        if (shellThread != null
            && shellThread.isAlive()) {
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

        var loggedIn = awaitScreen(screen -> shows(screen, "[file] RUNNING")
                                             && "logged in".equals(stateOf(screen, "File backend")));
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
        awaitScreen(screen -> shows(screen, "Task task-1 is followed in the running tasks window.")
                              && shows(screen, "Task task-1: RUNNING (40%)"));

        type("exit");
        press(KeyType.Enter);
        shellThread.join(TIMEOUT.toMillis());
        assertFalse(shellThread.isAlive(), "exit closes the TUI");
        assertNull(shellFailure.get());
    }

    @Test
    void shell_followsTagReplaceInTheRunningTasksWindow() throws Exception {
        var sessionStore = new SessionStore();
        sessionStore.save(baseUrl(), "file-jwt");
        startShell(sessionStore, prompt -> fail("no password is asked"));
        awaitScreen(screen -> shows(screen, "vempain-file> "));

        type("tag-replace --tag old --with new");
        press(KeyType.Enter);

        var screen = awaitScreen(current -> shows(current, "Submitted replacement of tag 'old' with 'new'.")
                                            && shows(current, "Task task-1 is followed in the running tasks window.")
                                            && shows(current, "[file] RUNNING"));
        assertFalse(screen.stream()
            .anyMatch(line -> line.contains("Vempain task monitor")), "the shell never opens the full-screen monitor");

        type("exit");
        press(KeyType.Enter);
        shellThread.join(TIMEOUT.toMillis());
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
        awaitScreen(screen -> shows(screen, "vempain-file> files-list --type music")
                              && shows(screen, track(1)));

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
        var stdout = new java.io.ByteArrayOutputStream();
        var original = System.out;
        System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
        try {
            startShell(new SessionStore(), prompt -> fail("no password expected"));
            awaitScreen(screen -> shows(screen, "vempain-unauthicated> "));

            type("files-list --unknown");
            press(KeyType.Enter);
            awaitScreen(screen -> shows(screen, "Usage: vempain-file-cli")
                                  || pageUp());

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
        startShell(sessionStore, passwordReader, new CommandHistory());
    }

    private void startShell(SessionStore sessionStore, PasswordReader passwordReader, CommandHistory commandHistory) {
        var silent = new PrintStream(OutputStream.nullOutputStream());
        var router = new CommandRouter(sessionStore, new HttpTransport(), silent, silent, passwordReader, commandHistory);
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
        assertTrue(top.indexOf("Session") >= 0
                   && top.indexOf("Session") < 5,
                "session window in the upper left corner: " + top);
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
                var end = rest.indexOf('│');
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
        var deadline = System.nanoTime() + TIMEOUT.toNanos();
        List<String> screen = List.of();
        while (System.nanoTime() < deadline) {
            if (shellFailure.get() != null) {
                fail("TUI shell failed", shellFailure.get());
            }
            var previous = screen;
            screen = screen();
            // Only a screen read twice in a row counts, so a read taken while Lanterna redraws is not mistaken for the result.
            if (screen.equals(previous)
                && condition.test(screen)) {
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
        return "http://localhost:"
                + server.getAddress()
                    .getPort()
                + "/api";
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

    @Test
    void shell_startsWithoutHistoryWhenTheHistoryFileCannotBeRead() throws Exception {
        var blocker = tempHome.resolve("blocker");
        Files.writeString(blocker, "not a directory");
        startShell(new SessionStore(), prompt -> fail("no password expected"), new CommandHistory(blocker.resolve("history")));
        awaitScreen(screen -> shows(screen, "vempain-unauthicated> "));

        press(KeyType.ArrowUp);
        type("exit");
        press(KeyType.Enter);
        shellThread.join(TIMEOUT.toMillis());

        assertFalse(shellThread.isAlive(), "an unreadable history does not stop the shell");
        assertNull(shellFailure.get());
    }

    @Test
    void tui_reportsUiErrorsKeepsRunningAndIgnoresOutputAfterItHasClosed() throws Exception {
        var promptCalls = new AtomicInteger();
        Supplier<String> prompt = () -> {
            if (promptCalls.incrementAndGet() == 2) {
                throw new IllegalStateException("prompt exploded");
            }
            return "p> ";
        };
        var tui = tui(new SessionStore());
        startTui(tui, line -> !"exit".equals(line), prompt, null);
        awaitScreen(screen -> shows(screen, "p> "));

        type("first");
        press(KeyType.Enter);
        awaitScreen(screen -> shows(screen, "UI error: prompt exploded"));

        type("exit");
        press(KeyType.Enter);
        shellThread.join(TIMEOUT.toMillis());
        assertFalse(shellThread.isAlive(), "the UI error does not stop the shell");
        assertNull(shellFailure.get());

        var session = new SessionStore.Session(SessionStore.BACKEND_FILE, baseUrl(), "file-jwt");
        var progress = tui.taskMonitor(new TaskClient(new HttpTransport()))
            .monitor(session, "task-1", Duration.ofSeconds(1));
        assertEquals("RUNNING", progress.status(), "the task monitor still answers after the TUI has closed");
    }

    @Test
    void tui_refusesInputWhileACommandRunsAndIgnoresEmptyLines() throws Exception {
        var release = new CountDownLatch(1);
        var lines = Collections.synchronizedList(new ArrayList<String>());
        Predicate<String> handler = line -> {
            lines.add(line);
            if ("wait".equals(line)) {
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread()
                        .interrupt();
                }
            }
            return !"exit".equals(line);
        };
        startTui(tui(new SessionStore()), handler, () -> "p> ", null);
        awaitScreen(screen -> shows(screen, "p> "));

        type("wait");
        press(KeyType.Enter);
        awaitScreen(screen -> shows(screen, "running... "));
        type("again");
        press(KeyType.Enter);
        awaitScreen(screen -> shows(screen, "A command is still running, wait for it to finish."));
        release.countDown();
        awaitScreen(screen -> !shows(screen, "running... "));

        press(KeyType.Enter);
        awaitScreen(screen -> shows(screen, "p> again"));
        press(KeyType.Enter);
        type("done");
        press(KeyType.Enter);
        var screen = awaitScreen(current -> shows(current, "p> done"));
        assertTrue(screen.stream()
            .anyMatch(line -> line.matches(".*│p> +│.*")), "an empty line is echoed but not run:\n" + String.join("\n", screen));

        type("exit");
        press(KeyType.Enter);
        shellThread.join(TIMEOUT.toMillis());
        assertEquals(List.of("wait", "again", "done", "exit"), lines);
        assertNull(shellFailure.get());
    }

    @Test
    void tui_showsCommandFailuresAndPartialOutputLines() throws Exception {
        var tui = tui(new SessionStore());
        Predicate<String> handler = line -> {
            if ("boom".equals(line)) {
                throw new IllegalStateException("kaboom");
            }
            if ("partial".equals(line)) {
                tui.out()
                    .print("no newline at the end");
            }
            return !"exit".equals(line);
        };
        startTui(tui, handler, () -> "p> ", null);
        awaitScreen(screen -> shows(screen, "p> "));

        type("boom");
        press(KeyType.Enter);
        awaitScreen(screen -> shows(screen, "Error: kaboom"));
        type("partial");
        press(KeyType.Enter);
        awaitScreen(screen -> shows(screen, "no newline at the end"));

        type("exit");
        press(KeyType.Enter);
        shellThread.join(TIMEOUT.toMillis());
        assertNull(shellFailure.get());
    }

    @Test
    void tui_tabCompletionFillsCommonPrefixesSingleMatchesAndReportsFailures() throws Exception {
        var lines = Collections.synchronizedList(new ArrayList<String>());
        Completer completer = (reader, line, candidates) -> {
            if ("boom".equals(line.word())) {
                throw new IllegalStateException("nope");
            }
            for (var command : List.of("publish-music", "publish-gps", "publish-files", "exit")) {
                if (command.startsWith(line.word())) {
                    candidates.add(new Candidate(command));
                }
            }
        };
        startTui(tui(new SessionStore()), line -> {
            lines.add(line);
            return !"exit".equals(line);
        }, () -> "p> ", completer);
        awaitScreen(screen -> shows(screen, "p> "));

        type("pu");
        press(KeyType.Tab);
        awaitScreen(screen -> shows(screen, "publish-music  publish-gps  publish-files"));
        type("m");
        press(KeyType.Tab);
        press(KeyType.Enter);
        awaitScreen(screen -> shows(screen, "p> publish-music"));

        type("zzz");
        press(KeyType.Tab);
        press(KeyType.Enter);
        awaitScreen(screen -> shows(screen, "p> zzz"));

        type("boom");
        press(KeyType.Tab);
        awaitScreen(screen -> shows(screen, "Completion failed: nope"));
        press(KeyType.Backspace, 4);

        type("exit");
        press(KeyType.Enter);
        shellThread.join(TIMEOUT.toMillis());
        assertEquals(List.of("publish-music", "zzz", "exit"), lines, "the common prefix and the single match were filled in");
        assertNull(shellFailure.get());
    }

    @Test
    void tui_withoutCompleterTabDoesNothingAndCtrlDOnAnEmptyLineExits() throws Exception {
        var lines = Collections.synchronizedList(new ArrayList<String>());
        startTui(tui(new SessionStore()), line -> {
            lines.add(line);
            return !"exit".equals(line);
        }, () -> "p> ", null);
        awaitScreen(screen -> shows(screen, "p> "));

        press(KeyType.Tab);
        terminal.addInput(new KeyStroke('d', true, false));
        shellThread.join(TIMEOUT.toMillis());

        assertFalse(shellThread.isAlive(), "Ctrl+D on an empty line leaves the shell");
        assertEquals(List.of("exit"), lines);
        assertNull(shellFailure.get());
    }

    @Test
    void tui_reportsAnInterruptedPasswordPrompt() throws Exception {
        var tui = tui(new SessionStore());
        Predicate<String> handler = line -> {
            if ("login".equals(line)) {
                Thread.currentThread()
                    .interrupt();
                tui.passwordReader()
                    .readPassword("Password: ");
            }
            return !"exit".equals(line);
        };
        startTui(tui, handler, () -> "p> ", null);
        awaitScreen(screen -> shows(screen, "p> "));

        type("login");
        press(KeyType.Enter);
        awaitScreen(screen -> shows(screen, "Error: Password prompt interrupted"));
        press(KeyType.Escape);
        awaitScreen(screen -> !shows(screen, "Enter: log in   Esc: cancel"));

        type("exit");
        press(KeyType.Enter);
        shellThread.join(TIMEOUT.toMillis());
        assertNull(shellFailure.get());
    }

    @Test
    void tui_showsTheActiveAdminBackendOverflowingTasksAndRefreshFailures() throws Exception {
        var failing = new AtomicBoolean();
        var sessionStore = new SessionStore() {

            @Override
            public Session load(String backend) {
                if (failing.get()) {
                    throw new IllegalStateException("store exploded");
                }
                return super.load(backend);
            }
        };
        sessionStore.save(SessionStore.BACKEND_ADMIN, "http://localhost:"
                + server.getAddress()
                    .getPort()
                + "/many/api", "admin-jwt");
        startTui(tui(sessionStore), line -> !"exit".equals(line), () -> "p> ", null);

        var screen = awaitScreen(current -> shows(current, "[admin] RUNNING")
                                            && "admin".equals(stateOf(current, "Active backend")));
        assertEquals("not logged in", stateOf(screen, "File backend"));
        assertEquals("logged in", stateOf(screen, "Admin backend"));
        assertTrue(screen.stream()
            .anyMatch(line -> line.contains("A very long title")
                              && line.contains("...")),
                "long task lines are truncated");
        assertTrue(screen.stream()
            .anyMatch(line -> line.matches(".*\\.\\.\\. and \\d+ more.*")), "the tasks window reports the lines it cannot show:\n" + String.join("\n", screen));

        failing.set(true);
        awaitScreen(current -> shows(current, "Task refresh failed: store exploded"));
        failing.set(false);

        type("exit");
        press(KeyType.Enter);
        shellThread.join(TIMEOUT.toMillis());
        assertNull(shellFailure.get());
    }

    @Test
    void tui_keepsOnlyTheLastTwoThousandOutputLines() throws Exception {
        var tui = tui(new SessionStore());
        Predicate<String> handler = line -> {
            if ("big".equals(line)) {
                for (int i = 1; i <= 2100; i++) {
                    tui.out()
                        .println("line-" + i);
                }
            }
            return !"exit".equals(line);
        };
        startTui(tui, handler, () -> "p> ", null);
        awaitScreen(screen -> shows(screen, "p> "));

        type("big");
        press(KeyType.Enter);
        awaitScreen(screen -> shows(screen, "line-2100"));
        press(KeyType.PageUp, 200);
        var top = awaitScreen(screen -> shows(screen, "line-101"));
        assertFalse(shows(top, "line-100"), "the oldest lines were dropped");
        assertFalse(shows(top, "p> big"), "the echoed command was the first line to go");

        type("exit");
        press(KeyType.Enter);
        shellThread.join(TIMEOUT.toMillis());
        assertNull(shellFailure.get());
    }

    @Test
    void tui_ignoresAnIoErrorOfTheFirstDraw() throws Exception {
        var failed = new AtomicBoolean();
        var flaky = (Terminal) Proxy.newProxyInstance(Terminal.class.getClassLoader(), new Class[]{ Terminal.class }, (proxy, method, args) -> {
            if ("flush".equals(method.getName())
                && "LanternaGUI".equals(Thread.currentThread()
                    .getName())
                && failed.compareAndSet(false, true)) {
                throw new IOException("terminal went away");
            }
            try {
                return method.invoke(terminal, args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause();
            }
        });
        var tui = new TuiShell(flaky, new SessionStore(), new TaskClient(new HttpTransport()), List.of(), Duration.ofMillis(200));
        startTui(tui, line -> !"exit".equals(line), () -> "p> ", null);

        awaitScreen(screen -> shows(screen, "p> "));
        assertTrue(failed.get(), "the first draw of the GUI thread failed");

        type("exit");
        press(KeyType.Enter);
        shellThread.join(TIMEOUT.toMillis());
        assertFalse(shellThread.isAlive(), "the shell survives the failed first draw");
        assertNull(shellFailure.get());
    }

    /**
     * The text terminal needs the controlling tty ({@code stty} is run on {@code /dev/tty}); without one the shell cannot open its TUI.
     */
    @Test
    void terminalHelpers_withoutAControllingTerminal_reportNoInteractiveTerminalAndFailToOpenOne() throws Exception {
        assumeTrue(System.console() == null, "needs a JVM without console");
        try (var tty = new java.io.FileInputStream("/dev/tty")) {
            assumeTrue(tty == null, "needs a JVM without a controlling terminal");
        } catch (IOException expected) {
            // no controlling terminal: the case under test
        }
        assertFalse(TuiShell.isInteractiveTerminal());

        var originalIn = System.in;
        var originalOut = System.out;
        System.setIn(new ByteArrayInputStream(new byte[0]));
        System.setOut(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        try {
            var exception = assertThrows(IOException.class, TuiShell::createTerminal);
            assertTrue(exception.getMessage()
                .contains("/dev/tty"), exception.getMessage());
        } finally {
            System.setIn(originalIn);
            System.setOut(originalOut);
        }
    }

    private TuiShell tui(SessionStore sessionStore) throws IOException {
        return new TuiShell(terminal, sessionStore, new TaskClient(new HttpTransport()), List.of(), Duration.ofMillis(200));
    }

    private void startTui(TuiShell tui, Predicate<String> lineHandler, Supplier<String> prompt, Completer completer) {
        shellThread = Thread.ofPlatform()
            .name("tui-test")
            .start(() -> {
                try {
                    tui.run(lineHandler, prompt, completer);
                } catch (Throwable e) {
                    shellFailure.set(e);
                }
            });
    }
}
