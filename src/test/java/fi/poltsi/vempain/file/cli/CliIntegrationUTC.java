package fi.poltsi.vempain.file.cli;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.jline.reader.*;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.ClosedException;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class CliIntegrationUTC {

    @TempDir
    Path tempHome;

    private String previousHome;

    @BeforeEach
    void setUp() {
        previousHome = System.getProperty("user.home");
        System.setProperty("user.home", tempHome.toString());
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.home", previousHome);
    }

    @Test
    void loginAndFilesList_formatsOutputTable() throws Exception {
        try (var server = new MockApiServer()) {
            server.registerJson("/api/login", "[{\"token\":\"jwt-1\"}]");
            server.registerJson("/api/files/music/paged",
                    """
                            {
                              "content": [
                                {
                                  "id": 42,
                                  "filename": "this-is-a-very-long-file-name-that-needs-truncation.mp3",
                                  "file_type": "MUSIC",
                                  "filesize": 123456,
                                  "created": "2026-05-14T12:00:00Z"
                                }
                              ],
                              "page": 0,
                              "total_pages": 1,
                              "total_elements": 1
                            }
                            """);

            var outBytes = new ByteArrayOutputStream();
            var errBytes = new ByteArrayOutputStream();
            var app = new VempainFileCliApplication(new SessionStore(), new BackendClient(), new PrintStream(outBytes), new PrintStream(errBytes));

            app.run(new String[]{"login", "--url", server.baseUrl(), "--username", "admin", "--password", "secret"});
            app.run(new String[]{"files-list", "--type", "music", "--size", "10"});

            var output = outBytes.toString(StandardCharsets.UTF_8);
            assertTrue(output.contains("Login successful"));
            assertTrue(output.contains("id       filename"));
            assertTrue(output.contains("Page 0/1, total elements: 1"));
            assertTrue(output.contains("..."));
            assertFalse(errBytes.toString(StandardCharsets.UTF_8)
                    .contains("Error:"));
        }
    }

    @Test
    void fileShow_supportsContentLimitAndRaw() throws Exception {
        try (var server = new MockApiServer()) {
            server.registerJson("/api/files/music/42",
                    """
                            {
                              "id": 42,
                              "filename": "song.txt",
                              "file_type": "MUSIC",
                              "mimetype": "text/plain",
                              "filesize": 20,
                              "created": "2026-05-14T12:00:00Z"
                            }
                            """);
            server.registerBytes("/api/files/42/content", "text/plain", "0123456789abcdefghijklmnopqrstuvwxyz".getBytes(StandardCharsets.UTF_8));

            var sessionStore = new SessionStore();
            sessionStore.save(server.baseUrl(), "jwt-2");

            var outBytes = new ByteArrayOutputStream();
            var app = new VempainFileCliApplication(sessionStore, new BackendClient(), new PrintStream(outBytes), System.err);

            app.run(new String[]{"file-show", "--type", "music", "--id", "42", "--content-limit", "10"});
            var truncatedOutput = outBytes.toString(StandardCharsets.UTF_8);
            assertTrue(truncatedOutput.contains("Metadata:"));
            assertTrue(truncatedOutput.contains("Content (truncated to 10 chars):"));

            outBytes.reset();
            app.run(new String[]{"file-show", "--type", "music", "--id", "42", "--raw", "--content-limit", "10"});
            var rawOutput = outBytes.toString(StandardCharsets.UTF_8);
            assertTrue(rawOutput.contains("\"filename\": \"song.txt\""));
            assertFalse(rawOutput.contains("Content (truncated to 10 chars):"));
            assertTrue(rawOutput.contains("abcdefghijklmnopqrstuvwxyz"));
        }
    }

    @Test
    void shellCompletion_completesFileTypeAndPath() throws Exception {
        try (var server = new MockApiServer()) {
            server.registerPathCompletionByPath(Map.of(
                    "/mu", "{\"completions\":[\"/music\",\"/museum\"]}"
            ));

            var sessionStore = new SessionStore();
            sessionStore.save(server.baseUrl(), "jwt-3");

            var app = new VempainFileCliApplication(sessionStore, new BackendClient(), System.out, System.err);
            var completer = app.createCompleterForTests();

            var typeCandidates = new ArrayList<Candidate>();
            completer.complete(null, new StubParsedLine(List.of("file-show", "--type", "m"), 2, "m"), typeCandidates);
            assertTrue(typeCandidates.stream()
                    .anyMatch(c -> "music".equals(c.value())));

            var pathCandidates = new ArrayList<Candidate>();
            completer.complete(null, new StubParsedLine(List.of("scan", "--original-directory", "/mu"), 2, "/mu"), pathCandidates);
            assertTrue(pathCandidates.stream()
                    .anyMatch(c -> "/music/".equals(c.value())));
        }
    }

    @Test
    void shellCompletion_scanPathCompletion_uniqueDirectory_doesNotAppendSpace() throws Exception {
        try (var server = new MockApiServer()) {
            server.registerPathCompletionByPath(Map.of(
                    "/m", "{\"completions\":[\"/music\"]}"
            ));

            var sessionStore = new SessionStore();
            sessionStore.save(server.baseUrl(), "jwt-3a");

            var app = new VempainFileCliApplication(sessionStore, new BackendClient(), System.out, System.err);
            var completer = app.createShellCompleterForTests();

            var candidates = new ArrayList<Candidate>();
            completer.complete(null, new StubParsedLine(List.of("scan", "-o", "/m"), 2, "/m"), candidates);

            assertEquals(1, candidates.size());
            assertEquals("/music/", candidates.get(0)
                    .value());
            assertNull(candidates.get(0)
                    .suffix());
            assertFalse(candidates.get(0)
                    .complete());
        }
    }

    @Test
    void shellCompletion_scanPathCompletion_supportsNestedDirectories() throws Exception {
        try (var server = new MockApiServer()) {
            server.registerPathCompletionByPath(Map.of(
                    "/mu", "{\"completions\":[\"/music\",\"/museum\"]}",
                    "/music/", "{\"completions\":[\"/music/Aavikko\",\"/music/Abbath\"]}"
            ));

            var sessionStore = new SessionStore();
            sessionStore.save(server.baseUrl(), "jwt-3b");

            var app = new VempainFileCliApplication(sessionStore, new BackendClient(), System.out, System.err);
            var completer = app.createShellCompleterForTests();

            var firstLevel = new ArrayList<Candidate>();
            completer.complete(null, new StubParsedLine(List.of("scan", "-o", "/mu"), 2, "/mu"), firstLevel);
            assertTrue(firstLevel.stream()
                    .anyMatch(c -> "/music/".equals(c.value())));

            var secondLevel = new ArrayList<Candidate>();
            completer.complete(null, new StubParsedLine(List.of("scan", "-o", "/music/"), 2, "/music/"), secondLevel);
            assertTrue(secondLevel.stream()
                    .anyMatch(c -> "/music/Aavikko/".equals(c.value())));
            assertTrue(secondLevel.stream()
                    .anyMatch(c -> "/music/Abbath/".equals(c.value())));
        }
    }

    @Test
    void shellCompletion_scanPathCompletion_explicitThreeStepSequence_regression() throws Exception {
        try (var server = new MockApiServer()) {
            server.registerPathCompletionByPath(Map.of(
                    "/", "{\"completions\":[\"/archive\",\"/music\",\"/video\"]}",
                    "/m", "{\"completions\":[\"/music\"]}",
                    "/music/", "{\"completions\":[\"/music/Aavikko\",\"/music/Green_Day\"]}"
            ));

            var sessionStore = new SessionStore();
            sessionStore.save(server.baseUrl(), "jwt-3seq");

            var app = new VempainFileCliApplication(sessionStore, new BackendClient(), System.out, System.err);
            var completer = app.createShellCompleterForTests();

            var rootCandidates = new ArrayList<Candidate>();
            completer.complete(null, new StubParsedLine(List.of("scan", "-o", ""), 2, ""), rootCandidates);
            assertTrue(rootCandidates.stream()
                    .anyMatch(c -> "/archive/".equals(c.value())));
            assertTrue(rootCandidates.stream()
                    .anyMatch(c -> "/music/".equals(c.value())));
            assertTrue(rootCandidates.stream()
                    .anyMatch(c -> "/video/".equals(c.value())));

            var narrowedCandidates = new ArrayList<Candidate>();
            completer.complete(null, new StubParsedLine(List.of("scan", "-o", "/m"), 2, "/m"), narrowedCandidates);
            assertEquals(1, narrowedCandidates.size());
            assertEquals("/music/", narrowedCandidates.get(0)
                    .value());

            var childCandidates = new ArrayList<Candidate>();
            completer.complete(null, new StubParsedLine(List.of("scan", "-o", "/music/"), 2, "/music/"), childCandidates);
            assertTrue(childCandidates.stream()
                    .anyMatch(c -> "/music/Aavikko/".equals(c.value())));
            assertTrue(childCandidates.stream()
                    .anyMatch(c -> "/music/Green_Day/".equals(c.value())));
        }
    }

    @Test
    void publishScanAndLogout_workEndToEnd() throws Exception {
        try (var server = new MockApiServer()) {
            server.registerJson("/api/data-publish/music", "{\"identifier\":\"music_library\",\"status\":\"ok\"}");
            server.registerJson("/api/data-publish/gps-timeseries", "{\"identifier\":\"gps_timeseries_demo\",\"status\":\"ok\"}");
            server.registerJson("/api/scan-files", "[{\"success\":true,\"newFilesCount\":1}]");

            var sessionStore = new SessionStore();
            sessionStore.save(server.baseUrl(), "jwt-4");

            var outBytes = new ByteArrayOutputStream();
            var errBytes = new ByteArrayOutputStream();
            var app = new VempainFileCliApplication(sessionStore, new BackendClient(), new PrintStream(outBytes), new PrintStream(errBytes));

            app.run(new String[]{"publish-music"});
            app.run(new String[]{"publish-gps", "--file-group-id", "10", "--time-series-name", "demo"});
            app.run(new String[]{"scan", "--original-directory", "/music"});
            app.run(new String[]{"logout"});

            var output = outBytes.toString(StandardCharsets.UTF_8);
            assertTrue(output.contains("music_library"));
            assertTrue(output.contains("gps_timeseries_demo"));
            assertTrue(output.contains("newFilesCount"));
            assertTrue(output.contains("Session removed."));
            assertNull(sessionStore.load());
            assertTrue(errBytes.toString(StandardCharsets.UTF_8)
                    .isBlank());
        }
    }

    @Test
    void errorPaths_areReportedToStderr() throws Exception {
        var outBytes = new ByteArrayOutputStream();
        var errBytes = new ByteArrayOutputStream();
        var app = new VempainFileCliApplication(new SessionStore(), new BackendClient(), new PrintStream(outBytes), new PrintStream(errBytes));

        app.run(new String[]{"files-list", "--type", "music"});
        app.run(new String[]{"files-list", "--type", "notatype"});

        var sessionStore = new SessionStore();
        sessionStore.save("http://localhost:8080/api", "jwt-x");
        var appWithSession = new VempainFileCliApplication(sessionStore, new BackendClient(), new PrintStream(outBytes), new PrintStream(errBytes));
        appWithSession.run(new String[]{"scan"});

        var err = errBytes.toString(StandardCharsets.UTF_8);
        assertTrue(err.contains("Not logged in"));
        assertTrue(err.contains("Invalid --type"));
        assertTrue(err.contains("Provide --original-directory and/or --export-directory"));
    }

    @Test
    void runShell_processesLineAndExits() throws Exception {
        try (var server = new MockApiServer()) {
            server.registerJson("/api/files/music/paged", "{\"content\":[],\"page\":0,\"total_pages\":0,\"total_elements\":0}");

            var sessionStore = new SessionStore();
            sessionStore.save(server.baseUrl(), "jwt-5");

            var outBytes = new ByteArrayOutputStream();
            var app = new VempainFileCliApplication(sessionStore, new BackendClient(), new PrintStream(outBytes), System.err);

            var inputs = new ArrayDeque<String>();
            inputs.add("files-list --type music");
            inputs.add("exit");
            LineReader reader = (LineReader) Proxy.newProxyInstance(
                    LineReader.class.getClassLoader(),
                    new Class[]{LineReader.class},
                    (proxy, method, args) -> {
                        if ("readLine".equals(method.getName())) {
                            var next = inputs.poll();
                            if (next == null) {
                                throw new EndOfFileException();
                            }
                            return next;
                        }
                        if (method.getReturnType()
                                .equals(boolean.class)) {
                            return false;
                        }
                        if (method.getReturnType()
                                .equals(int.class)) {
                            return 0;
                        }
                        return null;
                    }
            );

            app.runShell(reader);
            var output = outBytes.toString(StandardCharsets.UTF_8);
            assertTrue(output.contains("Interactive shell started"));
            assertTrue(output.contains("No files found."));
        }
    }

    @Test
    void commandCompletion_suggestsCommandNames() {
        var app = new VempainFileCliApplication(new SessionStore(), new BackendClient(), System.out, System.err);
        var completer = app.createCompleterForTests();
        var candidates = new ArrayList<Candidate>();
        completer.complete(null, new StubParsedLine(List.of(""), 0, ""), candidates);
        assertTrue(candidates.stream()
                .anyMatch(c -> "file-show".equals(c.value())));
    }

    @Test
    void usageAndParseErrors_areHandled() {
        var outBytes = new ByteArrayOutputStream();
        var errBytes = new ByteArrayOutputStream();
        var app = new VempainFileCliApplication(new SessionStore(), new BackendClient(), new PrintStream(outBytes), new PrintStream(errBytes));

        app.run(new String[]{});
        app.run(new String[]{"--help"});
        app.run(new String[]{"files-list", "--unknown"});

        var err = errBytes.toString(StandardCharsets.UTF_8);
        assertTrue(err.contains("Was passed main parameter") || err.contains("Unknown option"));
    }

    @Test
    void fileShow_binaryContentMessageIsPrinted() throws Exception {
        try (var server = new MockApiServer()) {
            server.registerJson("/api/files/music/99", "{\"id\":99,\"filename\":\"bin.dat\",\"file_type\":\"MUSIC\",\"mimetype\":\"application/octet-stream\"}");
            server.registerBytes("/api/files/99/content", "application/octet-stream", new byte[]{1, 2, 3, 4});

            var sessionStore = new SessionStore();
            sessionStore.save(server.baseUrl(), "jwt-6");

            var outBytes = new ByteArrayOutputStream();
            var app = new VempainFileCliApplication(sessionStore, new BackendClient(), new PrintStream(outBytes), System.err);
            app.run(new String[]{"file-show", "--type", "music", "--id", "99"});

            assertTrue(outBytes.toString(StandardCharsets.UTF_8)
                    .contains("Binary content (4 bytes"));
        }
    }

    @Test
    void scanCompletion_withoutSessionProducesNoCandidates() {
        var app = new VempainFileCliApplication(new SessionStore(), new BackendClient(), System.out, System.err);
        var completer = app.createCompleterForTests();
        var candidates = new ArrayList<Candidate>();
        completer.complete(null, new StubParsedLine(List.of("scan", "--export-directory", "/ex"), 2, "/ex"), candidates);
        assertTrue(candidates.isEmpty());
    }

    @Test
    void loginWithoutToken_reportsError() throws Exception {
        try (var server = new MockApiServer()) {
            server.registerJson("/api/login", "{}");

            var outBytes = new ByteArrayOutputStream();
            var errBytes = new ByteArrayOutputStream();
            var app = new VempainFileCliApplication(new SessionStore(), new BackendClient(), new PrintStream(outBytes), new PrintStream(errBytes));
            app.run(new String[]{"login", "--url", server.baseUrl(), "--username", "admin", "--password", "pw"});

            assertTrue(errBytes.toString(StandardCharsets.UTF_8)
                    .contains("Login response did not include token"));
        }
    }

    @Test
    void shellCompletion_suggestsCommandOptions() {
        var app = new VempainFileCliApplication(new SessionStore(), new BackendClient(), System.out, System.err);
        var completer = app.createShellCompleterForTests();

        var scanOptionCandidates = new ArrayList<Candidate>();
        completer.complete(null, new StubParsedLine(List.of("scan", ""), 1, ""), scanOptionCandidates);
        assertTrue(scanOptionCandidates.stream()
                .anyMatch(c -> "--original-directory".equals(c.value())));
        assertTrue(scanOptionCandidates.stream()
                .anyMatch(c -> "--export-directory".equals(c.value())));

        var loginOptionCandidates = new ArrayList<Candidate>();
        completer.complete(null, new StubParsedLine(List.of("login", "--u"), 1, "--u"), loginOptionCandidates);
        assertTrue(loginOptionCandidates.stream()
                .anyMatch(c -> "--url".equals(c.value())));
        assertTrue(loginOptionCandidates.stream()
                .anyMatch(c -> "--username".equals(c.value())));
    }

    @Test
    void shellModeCommandCompletion_excludesShellCommand() {
        var app = new VempainFileCliApplication(new SessionStore(), new BackendClient(), System.out, System.err);
        var completer = app.createShellCompleterForTests();
        var candidates = new ArrayList<Candidate>();

        completer.complete(null, new StubParsedLine(List.of(""), 0, ""), candidates);

        assertFalse(candidates.stream()
                .anyMatch(c -> "shell".equals(c.value())));
        assertTrue(candidates.stream()
                .anyMatch(c -> "scan".equals(c.value())));
    }

    @Test
    void runShell_rejectsNestedShellCommand() {
        var outBytes = new ByteArrayOutputStream();
        var errBytes = new ByteArrayOutputStream();
        var app = new VempainFileCliApplication(new SessionStore(), new BackendClient(), new PrintStream(outBytes), new PrintStream(errBytes));

        var inputs = new ArrayDeque<String>();
        inputs.add("shell");
        inputs.add("exit");
        LineReader reader = (LineReader) Proxy.newProxyInstance(
                LineReader.class.getClassLoader(),
                new Class[]{LineReader.class},
                (proxy, method, args) -> {
                    if ("readLine".equals(method.getName())) {
                        var next = inputs.poll();
                        if (next == null) {
                            throw new EndOfFileException();
                        }
                        return next;
                    }
                    if (method.getReturnType()
                            .equals(boolean.class)) {
                        return false;
                    }
                    if (method.getReturnType()
                            .equals(int.class)) {
                        return 0;
                    }
                    return null;
                }
        );

        app.runShell(reader);
        assertTrue(errBytes.toString(StandardCharsets.UTF_8)
                .contains("Already in shell-mode"));
    }

    @Test
    void shellCompletion_optionSets_areExactPerCommand() {
        var app = new VempainFileCliApplication(new SessionStore(), new BackendClient(), System.out, System.err);
        var completer = app.createShellCompleterForTests();

        assertEquals(Set.of("--url", "--username", "--password"),
                optionValues(completer, "login"));
        assertEquals(Set.of("-t", "--type", "-p", "--page", "-s", "--size", "--sort-by", "--direction", "--search", "--case-sensitive"),
                optionValues(completer, "files-list"));
        assertEquals(Set.of("-t", "--type", "-i", "--id", "--raw", "--content-limit"),
                optionValues(completer, "file-show"));
        assertEquals(Set.of(), optionValues(completer, "publish-music"));
        assertEquals(Set.of("--file-group-id", "--time-series-name"),
                optionValues(completer, "publish-gps"));
        assertEquals(Set.of("-o", "--original-directory", "-e", "--export-directory"),
                optionValues(completer, "scan"));
        assertEquals(Set.of(), optionValues(completer, "logout"));
        assertEquals(Set.of(), optionValues(completer, "exit"));
        assertEquals(Set.of(), optionValues(completer, "quit"));
    }

    private Set<String> optionValues(org.jline.reader.Completer completer, String command) {
        var candidates = new ArrayList<Candidate>();
        completer.complete(null, new StubParsedLine(List.of(command, ""), 1, ""), candidates);
        return candidates.stream()
                .map(Candidate::value)
                .collect(Collectors.toSet());
    }

    private static class StubParsedLine implements ParsedLine {
        private final List<String> words;
        private final int wordIndex;
        private final String word;

        private StubParsedLine(List<String> words, int wordIndex, String word) {
            this.words = words;
            this.wordIndex = wordIndex;
            this.word = word;
        }

        @Override
        public String word() {
            return word;
        }

        @Override
        public int wordCursor() {
            return word.length();
        }

        @Override
        public int wordIndex() {
            return wordIndex;
        }

        @Override
        public List<String> words() {
            return words;
        }

        @Override
        public String line() {
            return String.join(" ", words);
        }

        @Override
        public int cursor() {
            return line().length();
        }
    }

    private static class MockApiServer implements AutoCloseable {
        private final HttpServer server;

        private MockApiServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress(0), 0);
            server.start();
        }

        private void registerJson(String path, String responseBody) {
            register(path, "application/json", responseBody.getBytes(StandardCharsets.UTF_8));
        }

        private void registerBytes(String path, String contentType, byte[] responseBody) {
            register(path, contentType, responseBody);
        }

        private void register(String path, String contentType, byte[] responseBody) {
            server.createContext(path, new HttpHandler() {
                @Override
                public void handle(HttpExchange exchange) throws IOException {
                    exchange.getResponseHeaders()
                            .add("Content-Type", contentType);
                    exchange.sendResponseHeaders(200, responseBody.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(responseBody);
                    }
                }
            });
        }

        private void registerPathCompletionByPath(Map<String, String> responsesByPath) {
            server.createContext("/api/path-completion", exchange -> {
                var requestBody = new String(exchange.getRequestBody()
                        .readAllBytes(), StandardCharsets.UTF_8);
                var requestJson = new JSONObject(requestBody.isBlank() ? "{}" : requestBody);
                var path = requestJson.optString("path", "");
                var responseBody = responsesByPath.getOrDefault(path, "{\"completions\":[]}");
                var bytes = responseBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders()
                        .add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            });
        }

        private String baseUrl() {
            return "http://localhost:" + server.getAddress()
                    .getPort() + "/api";
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    @Test
    void shellCompletion_scanPathCompletion_uniqueMatch_isCompletableWithoutSpaceSuffix() throws Exception {
        try (var server = new MockApiServer()) {
            server.registerPathCompletionByPath(Map.of(
                    "/music/G", "{\"completions\":[\"/Green_Day\"]}"
            ));

            var sessionStore = new SessionStore();
            sessionStore.save(server.baseUrl(), "jwt-3c");

            var app = new VempainFileCliApplication(sessionStore, new BackendClient(), System.out, System.err);
            var completer = app.createShellCompleterForTests();

            var candidates = new ArrayList<Candidate>();
            completer.complete(null, new StubParsedLine(List.of("scan", "--original-directory", "/music/G"), 2, "/music/G"), candidates);

            assertEquals(1, candidates.size());
            assertEquals("/music/Green_Day/", candidates.get(0)
                    .value());
            assertFalse(candidates.get(0)
                    .complete());
            assertNull(candidates.get(0)
                    .suffix());
        }
    }

    @Test
    void shellCompletion_scanPathCompletion_continuesWhenPathWasSplitBySpace() throws Exception {
        try (var server = new MockApiServer()) {
            server.registerPathCompletionByPath(Map.of(
                    "/music/G", "{\"completions\":[\"/Green_Day\"]}"
            ));

            var sessionStore = new SessionStore();
            sessionStore.save(server.baseUrl(), "jwt-3d");

            var app = new VempainFileCliApplication(sessionStore, new BackendClient(), System.out, System.err);
            var completer = app.createShellCompleterForTests();

            var candidates = new ArrayList<Candidate>();
            completer.complete(null, new StubParsedLine(List.of("scan", "--original-directory", "/music/", "G"), 3, "G"), candidates);

            assertEquals(1, candidates.size());
            assertEquals("Green_Day/", candidates.get(0)
                    .value());
            assertNull(candidates.get(0)
                    .suffix());
        }
    }

    @Test
    void shellCompletion_realLineReader_appliesExpectedBufferTextOnTab() throws Exception {
        try (var server = new MockApiServer()) {
            server.registerPathCompletionByPath(Map.of(
                    "/m", "{\"completions\":[\"/music\"]}",
                    "/music/G", "{\"completions\":[\"/Green_Day\"]}",
                    "/music/", "{\"completions\":[\"/music/Aavikko\",\"/music/Green_Day\"]}"
            ));
            var sessionStore = new SessionStore();
            sessionStore.save(server.baseUrl(), "jwt-3e");
            var app = new VempainFileCliApplication(sessionStore, new BackendClient(), System.out, System.err);

            try (var terminal = TerminalBuilder.builder()
                    .dumb(true)
                    .streams(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream())
                    .build()) {
                var reader = (LineReaderImpl) LineReaderBuilder.builder()
                        .terminal(terminal)
                        .completer(app.createShellCompleterForTests())
                        .build();
                reader.setKeyMap(LineReader.MAIN);

                var setBuffer = LineReaderImpl.class.getDeclaredMethod("setBuffer", String.class);
                setBuffer.setAccessible(true);
                var completionTypeClass = Class.forName("org.jline.reader.impl.LineReaderImpl$CompletionType");
                @SuppressWarnings("unchecked")
                var completeType = Enum.valueOf((Class<Enum>) completionTypeClass, "Complete");
                var doComplete = LineReaderImpl.class.getDeclaredMethod("doComplete", completionTypeClass, boolean.class, boolean.class, boolean.class);
                doComplete.setAccessible(true);

                setBuffer.invoke(reader, "scan -o /m");
                invokeCompletion(doComplete, reader, completeType);
                assertEquals("scan -o /music/", reader.getBuffer()
                        .toString());

                setBuffer.invoke(reader, "scan -o /music/");
                invokeCompletion(doComplete, reader, completeType);
                assertEquals("scan -o /music/", reader.getBuffer()
                        .toString());

                setBuffer.invoke(reader, "scan -o /music/G");
                invokeCompletion(doComplete, reader, completeType);
                assertEquals("scan -o /music/Green_Day/", reader.getBuffer()
                        .toString());
            }
        }
    }

    private void invokeCompletion(java.lang.reflect.Method doComplete, LineReaderImpl reader, Object completeType) throws Exception {
        try {
            doComplete.invoke(reader, completeType, false, false, false);
        } catch (InvocationTargetException e) {
            var cause = e.getCause();
            if (!(cause instanceof EndOfFileException) && !(cause instanceof ClosedException)) {
                throw e;
            }
        }
    }

    @Test
    void shellCompletion_debugLogs_includePathCompletionRequestAndResponse_forMusicG() throws Exception {
        try (var server = new MockApiServer()) {
            server.registerPathCompletionByPath(Map.of(
                    "/music/G", "{\"completions\":[\"/Green_Day\"]}"
            ));

            var sessionStore = new SessionStore();
            sessionStore.save(server.baseUrl(), "jwt-debug-1");

            var outBytes = new ByteArrayOutputStream();
            var errBytes = new ByteArrayOutputStream();
            var app = new VempainFileCliApplication(sessionStore, new BackendClient(), new PrintStream(outBytes), new PrintStream(errBytes));

            var debugField = VempainFileCliApplication.class.getDeclaredField("debugCompletion");
            debugField.setAccessible(true);
            debugField.setBoolean(app, true);

            var completer = app.createShellCompleterForTests();
            var candidates = new ArrayList<Candidate>();
            completer.complete(null, new StubParsedLine(List.of("scan", "--original-directory", "/music/G"), 2, "/music/G"), candidates);

            var debugOutput = errBytes.toString(StandardCharsets.UTF_8);
            assertTrue(debugOutput.contains("[completion-debug] path-completion request:"));
            assertTrue(debugOutput.contains("\"path\":\"/music/G\""));
            assertTrue(debugOutput.contains("[completion-debug] path-completion response:"));
            assertTrue(debugOutput.contains("\"completions\":[\"/Green_Day\"]"));
        }
    }


}
