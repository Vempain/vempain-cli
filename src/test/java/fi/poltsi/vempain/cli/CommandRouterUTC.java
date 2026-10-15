package fi.poltsi.vempain.cli;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.TerminalBuilder;
import org.jline.terminal.impl.DumbTerminal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fi.poltsi.vempain.cli.CliIntegrationUTC.StubParsedLine;
import fi.poltsi.vempain.cli.core.CommandHistory;
import fi.poltsi.vempain.cli.core.HttpTransport;
import fi.poltsi.vempain.cli.core.SessionStore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandRouterUTC {

    @TempDir
    Path tempHome;

    private String previousHome;
    private ByteArrayOutputStream outBytes;
    private ByteArrayOutputStream errBytes;

    @BeforeEach
    void setUp() {
        previousHome = System.getProperty("user.home");
        System.setProperty("user.home", tempHome.toString());
        outBytes = new ByteArrayOutputStream();
        errBytes = new ByteArrayOutputStream();
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.home", previousHome);
    }

    @Test
    void constructorWithoutPasswordReader_usesTheConsoleReader() {
        var router = new CommandRouter(new SessionStore(), new HttpTransport(), new PrintStream(outBytes), new PrintStream(errBytes));

        router.run(new String[]{ "session", "show" }, false);

        assertTrue(out().contains("Stored sessions:"), out());
    }

    @Test
    void runShellLine_skipsBlankLinesAndWarnsWhenTheHistoryCannotBeWritten() throws Exception {
        try (var server = new CliIntegrationUTC.MockApiServer()) {
            server.registerJson("/api/metadata/value", "{\"task_id\":\"task-meta\",\"status\":\"QUEUED\"}");
            var sessionStore = new SessionStore();
            sessionStore.save(server.baseUrl(), "jwt");
            var router = new CommandRouter(sessionStore, new HttpTransport(), new PrintStream(outBytes), new PrintStream(errBytes),
                    prompt -> "secret".toCharArray(), unwritableHistory());

            assertTrue(router.runShellLine("   ", false));
            assertTrue(router.runShellLine(null, false));
            assertTrue(err().isBlank(), "blank lines are not recorded");

            assertTrue(router.runShellLine("metadata-update --group \"XMP dc\" --key 'Cre ator' --value x --new-value y", false));
            var request = server.lastRequestBody();
            assertEquals("XMP dc", request.getString("metadata_group"), "double quoted arguments stay one token");
            assertEquals("Cre ator", request.getString("metadata_key"), "single quoted arguments stay one token");
            assertTrue(err().contains("Warning: could not write command history:"), err());

            assertFalse(router.runShellLine("QUIT", false));
        }
    }

    @Test
    void runShell_ignoresInterruptsAndEndsAtEndOfFile() {
        var router = new CommandRouter(new SessionStore(), new HttpTransport(), new PrintStream(outBytes), new PrintStream(errBytes), prompt -> new char[0]);
        var prompts = new ArrayList<String>();
        var inputs = new ArrayDeque<Object>(List.of(new UserInterruptException(""), "session show", new EndOfFileException()));
        LineReader reader = (LineReader) Proxy.newProxyInstance(LineReader.class.getClassLoader(), new Class[]{ LineReader.class }, (proxy, method, args) -> {
            if ("readLine".equals(method.getName())) {
                prompts.add((String) args[0]);
                var next = inputs.poll();
                if (next instanceof RuntimeException e) {
                    throw e;
                }
                return next;
            }
            return method.getReturnType()
                .equals(boolean.class) ? false
                        : method.getReturnType()
                            .equals(int.class) ? 0 : null;
        });

        router.runShell(reader, false);

        assertEquals(3, prompts.size(), "Ctrl+C only discards the line, end of file leaves the shell");
        assertTrue(out().contains("Stored sessions:"), out());
    }

    @Test
    void shellPrompt_namesTheAdminBackendWhenOnlyItIsLoggedIn() throws Exception {
        var sessionStore = new SessionStore();
        sessionStore.save(SessionStore.BACKEND_ADMIN, "http://admin.example.com/api", "admin-jwt");
        var router = new CommandRouter(sessionStore, new HttpTransport(), new PrintStream(outBytes), new PrintStream(errBytes), prompt -> new char[0]);
        var prompts = new ArrayList<String>();

        router.runShell(scriptedReader(List.of("exit"), prompts), false);

        assertEquals(List.of("vempain-admin> "), prompts);
    }

    @Test
    @SuppressWarnings("deprecation")
    // TerminalBuilder.setTerminalOverride is JLine's hook for handing a scripted terminal to LineReaderBuilder.build()
    void shellCommand_withoutATerminal_runsTheLineBasedShell() throws Exception {
        var sessionStore = new SessionStore();
        sessionStore.save("http://file.example.com/api", "file-jwt");
        var terminal = dumbTerminal("session show\nexit\n");
        TerminalBuilder.setTerminalOverride(terminal);
        try {
            var router = new CommandRouter(sessionStore, new HttpTransport(), new PrintStream(outBytes), new PrintStream(errBytes), prompt -> new char[0]);
            router.run(new String[]{ "shell" }, false);
        } finally {
            TerminalBuilder.setTerminalOverride(null);
            terminal.close();
        }

        assertTrue(out().contains("Interactive shell started. Type 'exit' to quit."), out());
        assertTrue(out().contains("  file:  http://file.example.com/api"), out());
        assertTrue(err().isBlank(), err());
        assertTrue(Files.readString(tempHome.resolve(".vempain")
            .resolve("history"))
            .contains("\tsession show"), "shell lines are recorded in the history file");
    }

    @Test
    @SuppressWarnings("deprecation")
    // TerminalBuilder.setTerminalOverride is JLine's hook for handing a scripted terminal to LineReaderBuilder.build()
    void shellCommand_warnsWhenTheHistoryCannotBeLoaded() throws Exception {
        var terminal = dumbTerminal("exit\n");
        TerminalBuilder.setTerminalOverride(terminal);
        try {
            var router = new CommandRouter(new SessionStore(), new HttpTransport(), new PrintStream(outBytes), new PrintStream(errBytes), prompt -> new char[0],
                    unwritableHistory());
            router.run(new String[]{ "shell" }, false);
        } finally {
            TerminalBuilder.setTerminalOverride(null);
            terminal.close();
        }

        assertTrue(err().contains("Warning: could not load command history:"), err());
        assertTrue(out().contains("Interactive shell started"), out());
    }

    @Test
    void completer_completesAdminSubcommandsAndTheirOptions() {
        var completer = completer();

        assertEquals(Set.of("list-groups"), complete(completer, List.of("admin", "li"), 1, "li"));
        assertEquals(Set.of("--details", "--search", "--page", "--size"), complete(completer, List.of("admin", "list-groups", ""), 2, ""));
        assertEquals(Set.of("--message", "--monitor"), complete(completer, List.of("admin", "publish-group", "--m"), 2, "--m"));
        assertEquals(Set.of("--ids", "--monitor"), complete(completer, List.of("admin", "publish-groups", "--"), 2, "--"));
        assertEquals(Set.of("--ids", "--monitor"), complete(completer, List.of("admin", "publish-groups", "--", ""), 3, ""),
                "a lone dash prefix asks for the options");
        assertEquals(Set.of(), complete(completer, List.of("admin", "list-groups", "--search", "x"), 3, "x"), "option values are not completed");
        assertEquals(Set.of(), complete(completer, List.of("admin", "unknown", ""), 2, ""));
    }

    @Test
    void completer_completesSessionSubcommandsAndBackends() {
        var completer = completer();

        assertEquals(Set.of("use"), complete(completer, List.of("session", "u"), 1, "u"));
        assertEquals(Set.of(), complete(completer, List.of("session", "show", ""), 2, ""));
        assertEquals(Set.of(), complete(completer, List.of("session", "use", "--backend"), 2, "--backend"));
        assertEquals(Set.of("--backend", "-b"), complete(completer, List.of("session", "use", ""), 2, ""));
        assertEquals(Set.of("--backend", "-b"), complete(completer, List.of("session", "use", "-"), 2, "-"));
        assertEquals(Set.of("admin"), complete(completer, List.of("session", "use", "--backend", "a"), 3, "a"));
        assertEquals(Set.of("file", "admin"), complete(completer, List.of("session", "use", "-b", ""), 3, ""));
        assertEquals(Set.of(), complete(completer, List.of("session", "use", "x", "y"), 3, "y"));
    }

    @Test
    void completer_toleratesMissingWordsAndOddCursorPositions() {
        var completer = completer();

        assertTrue(complete(completer, List.of("session", "l"), 0, null).containsAll(Set.of("login", "session", "exit")),
                "a missing word completes like an empty one");
        assertEquals(Set.of(), complete(completer, List.of(), 1, ""));
        assertEquals(Set.of(), complete(completer, List.of("scan", "x"), -1, "x"));
        assertEquals(Set.of(), complete(completer, List.of("files-list", "x"), -1, "x"));
        assertEquals(Set.of(), complete(completer, List.of("files-list", "--type", "music", ""), 3, ""), "after a value no options are suggested");
    }

    private Completer completer() {
        var router = new CommandRouter(new SessionStore(), new HttpTransport(), new PrintStream(outBytes), new PrintStream(errBytes), prompt -> new char[0]);
        return router.createCompleter(true, false);
    }

    private Set<String> complete(Completer completer, List<String> words, int wordIndex, String word) {
        var candidates = new ArrayList<Candidate>();
        completer.complete(null, new StubParsedLine(words, wordIndex, word), candidates);
        return candidates.stream()
            .map(Candidate::value)
            .collect(Collectors.toSet());
    }

    /**
     * @return the terminal JLine hands to the line based shell: a dumb terminal reading the given lines, as when input is piped
     */
    private static DumbTerminal dumbTerminal(String input) throws Exception {
        return new DumbTerminal("test", "dumb", new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), new ByteArrayOutputStream(),
                StandardCharsets.UTF_8);
    }

    /**
     * @return a history whose directory is blocked by a regular file, so every read and write fails
     */
    private CommandHistory unwritableHistory() throws Exception {
        var blocker = tempHome.resolve("blocker");
        Files.writeString(blocker, "not a directory");
        return new CommandHistory(blocker.resolve("history"));
    }

    private LineReader scriptedReader(List<String> lines, List<String> prompts) {
        var inputs = new ArrayDeque<>(lines);
        return (LineReader) Proxy.newProxyInstance(LineReader.class.getClassLoader(), new Class[]{ LineReader.class }, (proxy, method, args) -> {
            if ("readLine".equals(method.getName())) {
                prompts.add((String) args[0]);
                var next = inputs.poll();
                if (next == null) {
                    throw new EndOfFileException();
                }
                return next;
            }
            return method.getReturnType()
                .equals(boolean.class) ? false
                        : method.getReturnType()
                            .equals(int.class) ? 0 : null;
        });
    }

    private String out() {
        return outBytes.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return errBytes.toString(StandardCharsets.UTF_8);
    }
}
