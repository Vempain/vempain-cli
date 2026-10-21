package fi.poltsi.vempain.cli;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.jline.reader.Candidate;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.ParsedLine;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.ClosedException;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

import fi.poltsi.vempain.cli.core.CommandHistory;
import fi.poltsi.vempain.cli.core.HttpTransport;
import fi.poltsi.vempain.cli.core.PasswordReader;
import fi.poltsi.vempain.cli.core.SessionStore;
import fi.poltsi.vempain.cli.core.TaskClient;
import fi.poltsi.vempain.cli.core.TaskMonitor;
import fi.poltsi.vempain.cli.core.TaskProgress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
            server.registerJson("/api/files/music/paged", """
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
            PasswordReader mockReader = prompt -> "secret".toCharArray();
            var app = new VempainFileCliApplication(new SessionStore(), new HttpTransport(), new PrintStream(outBytes), new PrintStream(errBytes), mockReader);

            app.run(new String[]{ "login", "--url", server.baseUrl(), "--username", "admin" });
            app.run(new String[]{ "files-list", "--type", "music", "--size", "10" });

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
            server.registerJson("/api/files/music/42", """
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
            var app = new VempainFileCliApplication(sessionStore, new HttpTransport(), new PrintStream(outBytes), System.err);

            app.run(new String[]{ "file-show", "--type", "music", "--id", "42", "--content-limit", "10" });
            var truncatedOutput = outBytes.toString(StandardCharsets.UTF_8);
            assertTrue(truncatedOutput.contains("Metadata:"));
            assertTrue(truncatedOutput.contains("Content (truncated to 10 chars):"));

            outBytes.reset();
            app.run(new String[]{ "file-show", "--type", "music", "--id", "42", "--raw", "--content-limit", "10" });
            var rawOutput = outBytes.toString(StandardCharsets.UTF_8);
            assertTrue(rawOutput.contains("\"filename\": \"song.txt\""));
            assertFalse(rawOutput.contains("Content (truncated to 10 chars):"));
            assertTrue(rawOutput.contains("abcdefghijklmnopqrstuvwxyz"));
        }
    }

    @Test
    void shellCompletion_completesFileTypeAndPath() throws Exception {
        try (var server = new MockApiServer()) {
            server.registerPathCompletionByPath(Map.of("/mu", "{\"completions\":[\"/music\",\"/museum\"]}"));

            var sessionStore = new SessionStore();
            sessionStore.save(server.baseUrl(), "jwt-3");

            var app = new VempainFileCliApplication(sessionStore, new HttpTransport(), System.out, System.err);
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
            server.registerPathCompletionByPath(Map.of("/m", "{\"completions\":[\"/music\"]}"));

            var sessionStore = new SessionStore();
            sessionStore.save(server.baseUrl(), "jwt-3a");

            var app = new VempainFileCliApplication(sessionStore, new HttpTransport(), System.out, System.err);
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
            server.registerPathCompletionByPath(
                    Map.of("/mu", "{\"completions\":[\"/music\",\"/museum\"]}", "/music/", "{\"completions\":[\"/music/Aavikko\",\"/music/Abbath\"]}"));

            var sessionStore = new SessionStore();
            sessionStore.save(server.baseUrl(), "jwt-3b");

            var app = new VempainFileCliApplication(sessionStore, new HttpTransport(), System.out, System.err);
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
            server.registerPathCompletionByPath(Map.of("/", "{\"completions\":[\"/archive\",\"/music\",\"/video\"]}", "/m", "{\"completions\":[\"/music\"]}",
                    "/music/", "{\"completions\":[\"/music/Aavikko\",\"/music/Green_Day\"]}"));

            var sessionStore = new SessionStore();
            sessionStore.save(server.baseUrl(), "jwt-3seq");

            var app = new VempainFileCliApplication(sessionStore, new HttpTransport(), System.out, System.err);
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
            var app = new VempainFileCliApplication(sessionStore, new HttpTransport(), new PrintStream(outBytes), new PrintStream(errBytes));

            app.run(new String[]{ "publish-music" });
            app.run(new String[]{ "publish-gps", "--file-group-id", "10", "--time-series-name", "demo" });
            app.run(new String[]{ "scan", "--original-directory", "/music" });
            app.run(new String[]{ "logout" });

            var output = outBytes.toString(StandardCharsets.UTF_8);
            assertTrue(output.contains("music_library"));
            assertTrue(output.contains("gps_timeseries_demo"));
            assertTrue(output.contains("newFilesCount"));
            assertTrue(output.contains("Session removed for backend"));
            assertNull(sessionStore.load());
            assertTrue(errBytes.toString(StandardCharsets.UTF_8)
                .isBlank());
        }
    }

    @Test
    void tagRenameReplaceAndMetadataUpdate_submitBackgroundTasks() throws Exception {
        try (var server = new MockApiServer()) {
            server.registerJson("/api/tags/paged",
                    """
                            {"content":[{"id":3,"tag_name":"old","tag_name_de":"alt","tag_name_en":"old","tag_name_es":null,"tag_name_fi":"vanha","tag_name_sv":"gammal"},
                                        {"id":4,"tag_name":"older","tag_name_de":"älter"}],"total_elements":2}
                            """);
            server.registerJson("/api/tags/all/rename",
                    "{\"task_id\":\"task-rename\",\"type\":\"TAG_RENAME_ACROSS_ALL\",\"status\":\"QUEUED\",\"total_steps\":3}");
            server.registerJson("/api/tags/all/replace",
                    "{\"task_id\":\"task-replace\",\"type\":\"TAG_REPLACE_ACROSS_ALL\",\"status\":\"QUEUED\",\"total_steps\":2}");
            server.registerJson("/api/metadata/value",
                    "{\"task_id\":\"task-meta\",\"type\":\"METADATA_UPDATE_VALUE\",\"status\":\"QUEUED\",\"total_steps\":5}");

            var sessionStore = new SessionStore();
            sessionStore.save(server.baseUrl(), "jwt-tags");
            var outBytes = new ByteArrayOutputStream();
            var errBytes = new ByteArrayOutputStream();
            var app = new VempainFileCliApplication(sessionStore, new HttpTransport(), new PrintStream(outBytes), new PrintStream(errBytes));

            app.run(new String[]{ "tag-rename", "--tag", "old", "--to", "new", "--name-en", "new" });
            var rename = server.lastRequestBody();
            assertEquals("old", rename.getString("tag_name"));
            assertEquals("new", rename.getString("replacement_tag_name"));
            assertEquals("new", rename.getString("tag_name_en"), "an explicit translation overrides the current one");
            assertEquals("alt", rename.getString("tag_name_de"), "omitted translations are carried over from the current tag");
            assertEquals("vanha", rename.getString("tag_name_fi"));
            assertEquals("gammal", rename.getString("tag_name_sv"));
            assertFalse(rename.has("tag_name_es"), "a null translation stays unset");
            assertEquals("[]", rename.getJSONArray("file_ids")
                .toString());

            app.run(new String[]{ "tag-replace", "--tag", "old", "--with", "new" });
            var replace = server.lastRequestBody();
            assertEquals("old", replace.getString("tag_name"));
            assertEquals("new", replace.getString("replacement_tag_name"));

            app.run(new String[]{ "metadata-update", "--group", "XMP-dc", "--key", "Creator", "--value", "Jane Doe", "--new-value", "Jane A. Doe" });
            var update = server.lastRequestBody();
            assertEquals("XMP-dc", update.getString("metadata_group"));
            assertEquals("Creator", update.getString("metadata_key"));
            assertEquals("Jane Doe", update.getString("metadata_value"));
            assertEquals("Jane A. Doe", update.getString("new_value"));

            app.run(new String[]{ "metadata-update", "--group", "XMP-dc", "--key", "Creator", "--value", "Jane A. Doe", "--remove" });
            assertEquals("", server.lastRequestBody()
                .getString("new_value"), "--remove sends an empty new value");

            var output = outBytes.toString(StandardCharsets.UTF_8);
            assertTrue(output.contains("Submitted rename of tag 'old' to 'new'."));
            assertTrue(output.contains("task-rename"));
            assertTrue(output.contains("Submitted replacement of tag 'old' with 'new'."));
            assertTrue(output.contains("task-replace"));
            assertTrue(output.contains("Submitted rewrite of XMP-dc:Creator from 'Jane Doe' to 'Jane A. Doe'."));
            assertTrue(output.contains("Submitted removal of XMP-dc:Creator = 'Jane A. Doe' from the files."));
            assertTrue(output.contains("task-meta"));
            assertTrue(errBytes.toString(StandardCharsets.UTF_8)
                .isBlank(), errBytes.toString(StandardCharsets.UTF_8));
        }
    }

    @Test
    void tagAndMetadataCommands_rejectInvalidArgumentsBeforeCallingTheBackend() throws Exception {
        try (var server = new MockApiServer()) {
            var sessionStore = new SessionStore();
            sessionStore.save(server.baseUrl(), "jwt-tags");
            var outBytes = new ByteArrayOutputStream();
            var errBytes = new ByteArrayOutputStream();
            var app = new VempainFileCliApplication(sessionStore, new HttpTransport(), new PrintStream(outBytes), new PrintStream(errBytes));

            app.run(new String[]{ "tag-replace", "--tag", "same", "--with", "same" });
            app.run(new String[]{ "metadata-update", "--group", "XMP-dc", "--key", "Creator", "--value", "x" });
            app.run(new String[]{ "metadata-update", "--group", "XMP-dc", "--key", "Creator", "--value", "x", "--new-value", "y", "--remove" });
            app.run(new String[]{ "tag-rename", "--tag", " ", "--to", "new" });

            var err = errBytes.toString(StandardCharsets.UTF_8);
            assertTrue(err.contains("--with must name a different tag than --tag"), err);
            assertTrue(err.contains("Provide --new-value <value> or --remove"), err);
            assertTrue(err.contains("--new-value and --remove exclude each other"), err);
            assertTrue(err.contains("--tag is required and must not be blank"), err);
            assertNull(server.lastRequestBody(), "no request reached the backend");
        }
    }

    @Test
    void publishFiles_readsIdsFileAndSubmitsOneBulkTask() throws Exception {
        try (var server = new MockApiServer()) {
            server.registerJson("/api/publish/files", "{\"task_id\":\"task-42\",\"status\":\"QUEUED\"}");
            var idsFile = tempHome.resolve("file-ids.txt");
            Files.writeString(idsFile, "# repaired files\n42\n\n17\n42\n");

            var sessionStore = new SessionStore();
            sessionStore.save(server.baseUrl(), "jwt-bulk");
            var outBytes = new ByteArrayOutputStream();
            var app = new VempainFileCliApplication(sessionStore, new HttpTransport(), new PrintStream(outBytes), System.err);

            app.run(new String[]{ "publish-files", "--ids-file", idsFile.toString() });

            var output = outBytes.toString(StandardCharsets.UTF_8);
            assertTrue(output.contains("Submitted 2 file IDs"));
            assertTrue(output.contains("task-42"));
            assertEquals("[42,17]", server.lastRequestBody()
                .getJSONArray("file_ids")
                .toString());
        }
    }

    @Test
    void errorPaths_areReportedToStderr() throws Exception {
        var outBytes = new ByteArrayOutputStream();
        var errBytes = new ByteArrayOutputStream();
        var app = new VempainFileCliApplication(new SessionStore(), new HttpTransport(), new PrintStream(outBytes), new PrintStream(errBytes));

        app.run(new String[]{ "files-list", "--type", "music" });
        app.run(new String[]{ "files-list", "--type", "notatype" });

        var sessionStore = new SessionStore();
        sessionStore.save("http://localhost:8080/api", "jwt-x");
        var appWithSession = new VempainFileCliApplication(sessionStore, new HttpTransport(), new PrintStream(outBytes), new PrintStream(errBytes));
        appWithSession.run(new String[]{ "scan" });

        var err = errBytes.toString(StandardCharsets.UTF_8);
        assertTrue(err.contains("Not logged in"));
        assertTrue(err.contains("Invalid --type"));
        assertTrue(err.contains("Provide --original-directory and/or --export-directory"));
    }

    @Test
    void adminWorkflow_loginListPublishAndSessionSwitch_workEndToEnd() throws Exception {
        try (var adminServer = new MockApiServer()) {
            adminServer.registerJson("/api/login", "[{\"token\":\"admin-jwt\"}]");
            adminServer.registerJson("/api/content-management/galleries/paged-list", """
                    {"content": [
                      {"id": 7, "short_name": "Summer", "description": "Summer photos"}
                    ], "page": 0, "total_pages": 1, "total_elements": 1}
                                    """);
            adminServer.registerJson("/api/content-management/galleries/publish", "[{\"result\":\"OK\",\"message\":\"done\"}]");
            adminServer.registerJson("/api/content-management/galleries/publish-selected", "[{\"result\":\"OK\",\"message\":\"done\"}]");

            var outBytes = new ByteArrayOutputStream();
            var errBytes = new ByteArrayOutputStream();
            PasswordReader mockReader = prompt -> "secret".toCharArray();
            var app = new VempainFileCliApplication(new SessionStore(), new HttpTransport(), new PrintStream(outBytes), new PrintStream(errBytes), mockReader);

            app.run(new String[]{ "login", "--backend", "admin", "--url", adminServer.baseUrl(), "--username", "admin" });
            app.run(new String[]{ "session", "show" });
            app.run(new String[]{ "admin", "list-groups" });
            app.run(new String[]{ "admin", "publish-group", "--id", "7", "--message", "publish now" });
            app.run(new String[]{ "admin", "publish-groups", "--ids", "7,8" });

            var output = outBytes.toString(StandardCharsets.UTF_8);
            assertTrue(output.contains("backend 'admin'"));
            assertTrue(output.contains("Active backend: admin (logged in)"));
            assertTrue(output.contains("short_name"));
            assertTrue(output.contains("Summer"));
            assertTrue(output.contains("\"result\": \"OK\""));
            assertTrue(errBytes.toString(StandardCharsets.UTF_8)
                .isBlank());
        }
    }

    @Test
    void runShell_processesLineAndExits() throws Exception {
        try (var server = new MockApiServer()) {
            server.registerJson("/api/files/music/paged", "{\"content\":[],\"page\":0,\"total_pages\":0,\"total_elements\":0}");

            var sessionStore = new SessionStore();
            sessionStore.save(server.baseUrl(), "jwt-5");

            var outBytes = new ByteArrayOutputStream();
            var app = new VempainFileCliApplication(sessionStore, new HttpTransport(), new PrintStream(outBytes), System.err);

            var inputs = new ArrayDeque<String>();
            inputs.add("files-list --type music");
            inputs.add("exit");
            LineReader reader = (LineReader) Proxy.newProxyInstance(LineReader.class.getClassLoader(), new Class[]{ LineReader.class },
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
                    });

            app.runShell(reader);
            var output = outBytes.toString(StandardCharsets.UTF_8);
            assertTrue(output.contains("Interactive shell started"));
            assertTrue(output.contains("No files found."));
        }
    }

    @Test
    void runShell_promptShowsUnauthicatedWhenNoSession() {
        var app = new VempainFileCliApplication(new SessionStore(), new HttpTransport(), System.out, System.err);
        var prompts = new ArrayList<String>();
        var reader = scriptedReader(List.of("exit"), prompts);

        app.runShell(reader);

        assertEquals(List.of("vempain-unauthicated> "), prompts);
    }

    @Test
    void runShell_promptTracksLoggedInBackendAndSwitch() throws Exception {
        try (var fileServer = new MockApiServer(); var adminServer = new MockApiServer()) {
            fileServer.registerJson("/api/login", "[{\"token\":\"file-jwt\"}]");
            adminServer.registerJson("/api/login", "[{\"token\":\"admin-jwt\"}]");

            var sessionStore = new SessionStore();
            PasswordReader mockReader = prompt -> "secret".toCharArray();
            var app = new VempainFileCliApplication(sessionStore, new HttpTransport(), System.out, System.err, mockReader);
            var prompts = new ArrayList<String>();
            var reader = scriptedReader(List.of("login --backend file --url " + fileServer.baseUrl() + " --username admin",
                    "login --backend admin --url " + adminServer.baseUrl() + " --username admin", "exit"), prompts);

            app.runShell(reader);

            assertEquals(List.of("vempain-unauthicated> ", "vempain-file> ", "vempain-admin> "), prompts);
            assertNotNull(sessionStore.load(SessionStore.BACKEND_FILE));
            assertNotNull(sessionStore.load(SessionStore.BACKEND_ADMIN));
        }
    }

    @Test
    void login_toAnotherBackend_keepsOlderBackendSession() throws Exception {
        try (var fileServer = new MockApiServer(); var adminServer = new MockApiServer()) {
            fileServer.registerJson("/api/login", "[{\"token\":\"file-jwt\"}]");
            adminServer.registerJson("/api/login", "[{\"token\":\"admin-jwt\"}]");

            var sessionStore = new SessionStore();
            PasswordReader mockReader = prompt -> "secret".toCharArray();
            var app = new VempainFileCliApplication(sessionStore, new HttpTransport(), System.out, System.err, mockReader);

            app.run(new String[]{ "login", "--backend", "file", "--url", fileServer.baseUrl(), "--username", "admin" });
            assertNotNull(sessionStore.load(SessionStore.BACKEND_FILE));

            app.run(new String[]{ "login", "--backend", "admin", "--url", adminServer.baseUrl(), "--username", "admin" });

            assertNotNull(sessionStore.load(SessionStore.BACKEND_FILE));
            assertNotNull(sessionStore.load(SessionStore.BACKEND_ADMIN));
            assertEquals(SessionStore.BACKEND_ADMIN, sessionStore.getActiveBackend());
        }
    }

    @Test
    void commandCompletion_suggestsCommandNames() {
        var app = new VempainFileCliApplication(new SessionStore(), new HttpTransport(), System.out, System.err);
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
        var app = new VempainFileCliApplication(new SessionStore(), new HttpTransport(), new PrintStream(outBytes), new PrintStream(errBytes));

        app.run(new String[]{});
        app.run(new String[]{ "--help" });
        app.run(new String[]{ "files-list", "--unknown" });

        var err = errBytes.toString(StandardCharsets.UTF_8);
        assertTrue(err.contains("Was passed main parameter")
                   || err.contains("Unknown option"));
        assertTrue(outBytes.toString(StandardCharsets.UTF_8)
            .contains("Usage: vempain-file-cli"), "usage is printed to the router's output stream");
    }

    @Test
    void fileShow_binaryContentMessageIsPrinted() throws Exception {
        try (var server = new MockApiServer()) {
            server.registerJson("/api/files/music/99",
                    "{\"id\":99,\"filename\":\"bin.dat\",\"file_type\":\"MUSIC\",\"mimetype\":\"application/octet-stream\"}");
            server.registerBytes("/api/files/99/content", "application/octet-stream", new byte[]{ 1, 2, 3, 4 });

            var sessionStore = new SessionStore();
            sessionStore.save(server.baseUrl(), "jwt-6");

            var outBytes = new ByteArrayOutputStream();
            var app = new VempainFileCliApplication(sessionStore, new HttpTransport(), new PrintStream(outBytes), System.err);
            app.run(new String[]{ "file-show", "--type", "music", "--id", "99" });

            assertTrue(outBytes.toString(StandardCharsets.UTF_8)
                .contains("Binary content (4 bytes"));
        }
    }

    @Test
    void scanCompletion_withoutSessionProducesNoCandidates() {
        var app = new VempainFileCliApplication(new SessionStore(), new HttpTransport(), System.out, System.err);
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
            PasswordReader mockReader = prompt -> "pw".toCharArray();
            var app = new VempainFileCliApplication(new SessionStore(), new HttpTransport(), new PrintStream(outBytes), new PrintStream(errBytes), mockReader);
            app.run(new String[]{ "login", "--url", server.baseUrl(), "--username", "admin" });

            assertTrue(errBytes.toString(StandardCharsets.UTF_8)
                .contains("Login response did not include token"));
        }
    }

    @Test
    void shellCompletion_suggestsCommandOptions() {
        var app = new VempainFileCliApplication(new SessionStore(), new HttpTransport(), System.out, System.err);
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
        assertFalse(loginOptionCandidates.stream()
            .anyMatch(c -> "--password".equals(c.value())));
    }

    @Test
    void shellModeCommandCompletion_excludesShellCommand() {
        var app = new VempainFileCliApplication(new SessionStore(), new HttpTransport(), System.out, System.err);
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
        var app = new VempainFileCliApplication(new SessionStore(), new HttpTransport(), new PrintStream(outBytes), new PrintStream(errBytes));

        var inputs = new ArrayDeque<String>();
        inputs.add("shell");
        inputs.add("exit");
        LineReader reader = (LineReader) Proxy.newProxyInstance(LineReader.class.getClassLoader(), new Class[]{ LineReader.class }, (proxy, method, args) -> {
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
        });

        app.runShell(reader);
        assertTrue(errBytes.toString(StandardCharsets.UTF_8)
            .contains("Already in shell-mode"));
    }

    @Test
    void shellCompletion_optionSets_areExactPerCommand() {
        var app = new VempainFileCliApplication(new SessionStore(), new HttpTransport(), System.out, System.err);
        var completer = app.createShellCompleterForTests();

        assertEquals(Set.of("--backend", "-b", "--url", "--username"), optionValues(completer, "login"));
        assertEquals(Set.of("-t", "--type", "-p", "--page", "-s", "--size", "--sort-by", "--direction", "--search", "--case-sensitive"),
                optionValues(completer, "files-list"));
        assertEquals(Set.of("-t", "--type", "-i", "--id", "--raw", "--content-limit"), optionValues(completer, "file-show"));
        assertEquals(Set.of("--monitor"), optionValues(completer, "publish-music"));
        assertEquals(Set.of("--file-group-id", "--time-series-name", "--monitor"), optionValues(completer, "publish-gps"));
        assertEquals(Set.of("--ids-file", "--monitor"), optionValues(completer, "publish-files"));
        assertEquals(Set.of("--file-id", "--file-group-id", "--raw"), optionValues(completer, "publish-status"));
        assertEquals(Set.of("-o", "--original-directory", "-e", "--export-directory", "--monitor"), optionValues(completer, "scan"));
        assertEquals(Set.of("--tag", "--to", "--name-de", "--name-en", "--name-es", "--name-fi", "--name-sv", "--monitor"),
                optionValues(completer, "tag-rename"));
        assertEquals(Set.of("--tag", "--with", "--monitor"), optionValues(completer, "tag-replace"));
        assertEquals(Set.of("--group", "--key", "--value", "--new-value", "--remove", "--monitor"), optionValues(completer, "metadata-update"));
        assertEquals(Set.of("list-groups", "publish-group", "publish-groups"), optionValues(completer, "admin"));
        assertEquals(Set.of("show", "use"), optionValues(completer, "session"));
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

    private LineReader scriptedReader(List<String> lines, List<String> prompts) {
        var inputs = new ArrayDeque<>(lines);
        return (LineReader) Proxy.newProxyInstance(LineReader.class.getClassLoader(), new Class[]{ LineReader.class }, (proxy, method, args) -> {
            if ("readLine".equals(method.getName())) {
                if (args != null
                    && args.length > 0
                    && args[0] instanceof String prompt) {
                    prompts.add(prompt);
                }
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
        });
    }

    static class StubParsedLine implements ParsedLine {

        private final List<String> words;
        private final int wordIndex;
        private final String word;

        StubParsedLine(List<String> words, int wordIndex, String word) {
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

    /**
     * In-process stub of a Vempain backend. Shared by the other {@code *UTC} classes of this package.
     */
    static class MockApiServer implements AutoCloseable {

        private final HttpServer server;
        private final List<String> requests = Collections.synchronizedList(new ArrayList<>());
        private JSONObject lastRequestBody;

        MockApiServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress(0), 0);
            registerJson("/api/version", "{\"version\":\"0.0.1-SNAPSHOT\",\"service\":\"test\"}");
            server.start();
        }

        void registerJson(String path, String responseBody) {
            registerJson(path, 200, responseBody);
        }

        void registerJson(String path, int status, String responseBody) {
            registerResponder(path, request -> new Reply(status, responseBody));
        }

        /**
         * Answers the calls to {@code path} with the given bodies in order; the last body is repeated.
         */
        void registerJsonSequence(String path, String... responseBodies) {
            var calls = new AtomicInteger();
            registerResponder(path, request -> new Reply(200, responseBodies[Math.min(calls.getAndIncrement(), responseBodies.length - 1)]));
        }

        /**
         * @param responder maps the JSON request body (an empty object for requests without a body) to the reply
         */
        void registerResponder(String path, Function<JSONObject, Reply> responder) {
            server.createContext(path, exchange -> {
                var body = new String(exchange.getRequestBody()
                    .readAllBytes(), StandardCharsets.UTF_8);
                requests.add(exchange.getRequestMethod()
                        + " "
                        + exchange.getRequestURI()
                            .getPath());
                var requestJson = body.isBlank() ? new JSONObject() : new JSONObject(body);
                if (!body.isBlank()) {
                    lastRequestBody = requestJson;
                }
                var reply = responder.apply(requestJson);
                var bytes = reply.body()
                    .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders()
                    .add("Content-Type", "application/json");
                exchange.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            });
        }

        void registerBytes(String path, String contentType, byte[] responseBody) {
            server.createContext(path, exchange -> {
                exchange.getRequestBody()
                    .readAllBytes();
                requests.add(exchange.getRequestMethod()
                        + " "
                        + exchange.getRequestURI()
                            .getPath());
                exchange.getResponseHeaders()
                    .add("Content-Type", contentType);
                exchange.sendResponseHeaders(200, responseBody.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(responseBody);
                }
            });
        }

        JSONObject lastRequestBody() {
            return lastRequestBody;
        }

        /**
         * @return the requests received so far as {@code METHOD /path}, oldest first
         */
        List<String> requests() {
            return List.copyOf(requests);
        }

        void registerPathCompletionByPath(Map<String, String> responsesByPath) {
            registerResponder("/api/path-completion",
                    request -> new Reply(200, responsesByPath.getOrDefault(request.optString("path", ""), "{\"completions\":[]}")));
        }

        String baseUrl() {
            return "http://localhost:"
                    + server.getAddress()
                        .getPort()
                    + "/api";
        }

        @Override
        public void close() {
            server.stop(0);
        }

        record Reply(int status, String body) {}
    }

    @Test
    void shellCompletion_scanPathCompletion_uniqueMatch_isCompletableWithoutSpaceSuffix() throws Exception {
        try (var server = new MockApiServer()) {
            server.registerPathCompletionByPath(Map.of("/music/G", "{\"completions\":[\"/Green_Day\"]}"));

            var sessionStore = new SessionStore();
            sessionStore.save(server.baseUrl(), "jwt-3c");

            var app = new VempainFileCliApplication(sessionStore, new HttpTransport(), System.out, System.err);
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
            server.registerPathCompletionByPath(Map.of("/music/G", "{\"completions\":[\"/Green_Day\"]}"));

            var sessionStore = new SessionStore();
            sessionStore.save(server.baseUrl(), "jwt-3d");

            var app = new VempainFileCliApplication(sessionStore, new HttpTransport(), System.out, System.err);
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
            server.registerPathCompletionByPath(Map.of("/m", "{\"completions\":[\"/music\"]}", "/music/G", "{\"completions\":[\"/Green_Day\"]}", "/music/",
                    "{\"completions\":[\"/music/Aavikko\",\"/music/Green_Day\"]}"));
            var sessionStore = new SessionStore();
            sessionStore.save(server.baseUrl(), "jwt-3e");
            var app = new VempainFileCliApplication(sessionStore, new HttpTransport(), System.out, System.err);

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
            if (!(cause instanceof EndOfFileException)
                && !(cause instanceof ClosedException)) {
                throw e;
            }
        }
    }

    @Test
    void shellCompletion_debugLogs_includePathCompletionRequestAndResponse_forMusicG() throws Exception {
        try (var server = new MockApiServer()) {
            server.registerPathCompletionByPath(Map.of("/music/G", "{\"completions\":[\"/Green_Day\"]}"));

            var sessionStore = new SessionStore();
            sessionStore.save(server.baseUrl(), "jwt-debug-1");

            var outBytes = new ByteArrayOutputStream();
            var errBytes = new ByteArrayOutputStream();
            var app = new VempainFileCliApplication(sessionStore, new HttpTransport(), new PrintStream(outBytes), new PrintStream(errBytes));

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

    @Test
    void tasksAndTaskCancel_useTheSessionOfTheRequestedBackend() throws Exception {
        try (var server = new MockApiServer()) {
            server.registerJson("/api/tasks", "[{\"task_id\":\"t-1\",\"status\":\"RUNNING\",\"percent\":40,\"title\":\"Scanning\"}]");
            server.registerJson("/api/tasks/t-1/cancel",
                    "{\"task_id\":\"t-1\",\"status\":\"CANCELLED\",\"percent\":40,\"error_message\":\"Cancelled by user\"}");
            var sessionStore = new SessionStore();
            sessionStore.save(server.baseUrl(), "jwt-tasks");
            var outBytes = new ByteArrayOutputStream();
            var errBytes = new ByteArrayOutputStream();
            var app = new VempainFileCliApplication(sessionStore, new HttpTransport(), new PrintStream(outBytes), new PrintStream(errBytes));

            app.run(new String[]{ "tasks" });
            assertTrue(outBytes.toString(StandardCharsets.UTF_8)
                .contains("t-1 RUNNING       40% Scanning"), outBytes.toString(StandardCharsets.UTF_8));

            outBytes.reset();
            app.run(new String[]{ "tasks", "--raw" });
            var raw = new org.json.JSONArray(outBytes.toString(StandardCharsets.UTF_8));
            assertEquals("t-1", raw.getJSONObject(0)
                .getString("task_id"));

            outBytes.reset();
            app.run(new String[]{ "task-cancel", "--task-id", "t-1" });
            var cancelled = outBytes.toString(StandardCharsets.UTF_8);
            assertTrue(cancelled.contains("Task t-1: CANCELLED (40%)"), cancelled);
            assertTrue(cancelled.contains("Error: Cancelled by user"), cancelled);
            assertTrue(server.requests()
                .contains("POST /api/tasks/t-1/cancel"));

            app.run(new String[]{ "tasks", "--backend", "admin" });
            assertTrue(errBytes.toString(StandardCharsets.UTF_8)
                .contains("Not logged in for backend 'admin'"));
        }
    }

    @Test
    void adminCommands_coverOptionsEmptyResultsPagingAndScheduling() throws Exception {
        try (var server = new MockApiServer()) {
            server.registerJsonSequence("/api/content-management/galleries/paged-list",
                    "[{\"id\":1,\"short_name\":\"Spring\",\"description\":\"Spring photos\"}]", "{\"content\":[],\"page\":0}",
                    "{\"content\":[{\"id\":2,\"short_name\":\"Winter\"}],\"pageNumber\":1,\"totalPages\":3,\"totalElements\":30}");
            server.registerJson("/api/content-management/galleries/publish", "{\"task_id\":\"task-pub\",\"status\":\"QUEUED\"}");
            server.registerJson("/api/content-management/galleries/publish-selected", "{\"status\":\"QUEUED\"}");
            var sessionStore = new SessionStore();
            sessionStore.save(SessionStore.BACKEND_ADMIN, server.baseUrl(), "admin-jwt");
            var outBytes = new ByteArrayOutputStream();
            var errBytes = new ByteArrayOutputStream();
            var app = new VempainFileCliApplication(sessionStore, new HttpTransport(), new PrintStream(outBytes), new PrintStream(errBytes));

            app.run(new String[]{ "admin", "list-groups", "--details", "basic", "--search", "spr", "--page", "1", "--size", "5" });
            var request = server.lastRequestBody();
            assertEquals(1, request.getInt("page"));
            assertEquals(5, request.getInt("size"));
            assertEquals("spr", request.getString("search"));
            assertTrue(outBytes.toString(StandardCharsets.UTF_8)
                .contains("Spring"), "an array response is listed through its items");
            assertFalse(outBytes.toString(StandardCharsets.UTF_8)
                .contains("Page "), "no paging line without paging keys");

            outBytes.reset();
            app.run(new String[]{ "admin", "list-groups" });
            assertTrue(outBytes.toString(StandardCharsets.UTF_8)
                .contains("No admin groups found."));

            outBytes.reset();
            app.run(new String[]{ "admin", "list-groups" });
            assertTrue(outBytes.toString(StandardCharsets.UTF_8)
                .contains("Page 1/3, total elements: 30"), outBytes.toString(StandardCharsets.UTF_8));

            app.run(new String[]{ "admin", "publish-group", "--id", "7", "--publish-datetime", "2026-12-24T18:00:00" });
            var scheduled = server.lastRequestBody();
            assertTrue(scheduled.getBoolean("publish_schedule"));
            assertEquals("2026-12-24T18:00:00", scheduled.getString("publish_date_time"));
            assertFalse(scheduled.has("publish_message"));

            app.run(new String[]{ "admin", "publish-groups", "--ids", " , " });
            app.run(new String[]{ "admin", "publish-groups", "--ids", "7", "--monitor" });
            outBytes.reset();
            app.run(new String[]{ "admin" });
            assertTrue(outBytes.toString(StandardCharsets.UTF_8)
                .contains("Usage: "), "admin without a subcommand prints its usage");

            var err = errBytes.toString(StandardCharsets.UTF_8);
            assertTrue(err.contains("Provide at least one gallery id with --ids"), err);
            assertTrue(err.contains("The backend did not return a task_id; cannot monitor the request."), err);
        }
    }

    @Test
    void adminCommands_withoutAdminSession_explainHowToLogIn() {
        var outBytes = new ByteArrayOutputStream();
        var errBytes = new ByteArrayOutputStream();
        var app = new VempainFileCliApplication(new SessionStore(), new HttpTransport(), new PrintStream(outBytes), new PrintStream(errBytes));

        app.run(new String[]{ "admin", "list-groups" });

        assertTrue(errBytes.toString(StandardCharsets.UTF_8)
            .contains("Not logged in for backend 'admin'. Run: login --backend admin"));
    }

    @Test
    void sessionCommands_showRawAndTextAndSwitchTheActiveBackend() throws Exception {
        var sessionStore = new SessionStore();
        sessionStore.save(SessionStore.BACKEND_FILE, "http://file.example.com/api", "file-jwt");
        sessionStore.save(SessionStore.BACKEND_ADMIN, "http://admin.example.com/api", "admin-jwt");
        var outBytes = new ByteArrayOutputStream();
        var errBytes = new ByteArrayOutputStream();
        var app = new VempainFileCliApplication(sessionStore, new HttpTransport(), new PrintStream(outBytes), new PrintStream(errBytes));

        app.run(new String[]{ "session", "show", "--raw" });
        var raw = new JSONObject(outBytes.toString(StandardCharsets.UTF_8));
        assertEquals("admin", raw.getString("active_backend"));
        assertEquals("http://file.example.com/api", raw.getJSONObject("backends")
            .getJSONObject("file")
            .getString("base_url"));
        assertEquals("http://admin.example.com/api", raw.getJSONObject("backends")
            .getJSONObject("admin")
            .getString("base_url"));
        assertFalse(outBytes.toString(StandardCharsets.UTF_8)
            .contains("jwt"), "tokens are never printed");

        outBytes.reset();
        app.run(new String[]{ "session", "use", "--backend", "file" });
        assertTrue(outBytes.toString(StandardCharsets.UTF_8)
            .contains("Active backend set to 'file'."));
        assertEquals(SessionStore.BACKEND_FILE, sessionStore.getActiveBackend());

        outBytes.reset();
        app.run(new String[]{ "session", "show" });
        var text = outBytes.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("Active backend: file (logged in)"), text);
        assertTrue(text.contains("  file:  http://file.example.com/api"), text);
        assertTrue(text.contains("  admin: http://admin.example.com/api"), text);

        outBytes.reset();
        app.run(new String[]{ "session" });
        assertTrue(outBytes.toString(StandardCharsets.UTF_8)
            .contains("Usage: "), "session without a subcommand prints its usage");

        outBytes.reset();
        app.run(new String[]{ "--debug-completion" });
        assertTrue(outBytes.toString(StandardCharsets.UTF_8)
            .contains("Usage: vempain-file-cli"), "root options without a command print the usage");
        assertTrue(errBytes.toString(StandardCharsets.UTF_8)
            .isBlank());
    }

    @Test
    void fileCommands_coverSearchCamelCasePagingScanBranchesAndTagLookupFailures() throws Exception {
        try (var server = new MockApiServer()) {
            server.registerJson("/api/files/image/paged",
                    "{\"content\":[{\"id\":1,\"filename\":\"a.jpg\",\"file_type\":\"IMAGE\",\"filesize\":1,\"created\":\"x\"}],"
                            + "\"page\":2,\"totalPages\":5,\"totalElements\":101}");
            server.registerJson("/api/scan-files", "{\"task_id\":\"scan-1\",\"status\":\"QUEUED\"}");
            server.registerJson("/api/data-publish/gps-timeseries", "{\"identifier\":\"gps\"}");
            server.registerJsonSequence("/api/tags/paged", "{}", "{\"content\":[{\"id\":9,\"tag_name\":\"other\"}]}");
            server.registerJson("/api/tags/all/rename", "{\"task_id\":\"task-rename\",\"status\":\"QUEUED\"}");
            var sessionStore = new SessionStore();
            sessionStore.save(server.baseUrl(), "jwt-files");
            var outBytes = new ByteArrayOutputStream();
            var errBytes = new ByteArrayOutputStream();
            var app = new VempainFileCliApplication(sessionStore, new HttpTransport(), new PrintStream(outBytes), new PrintStream(errBytes));

            app.run(new String[]{ "files-list", "--type", "image", "--search", "holiday", "--direction", "desc" });
            assertEquals("holiday", server.lastRequestBody()
                .getString("search"));
            assertEquals("DESC", server.lastRequestBody()
                .getString("direction"));
            assertTrue(outBytes.toString(StandardCharsets.UTF_8)
                .contains("Page 2/5, total elements: 101"), "camelCase paging keys are understood");

            outBytes.reset();
            app.run(new String[]{ "scan", "-e", "/export" });
            assertEquals("/export", server.lastRequestBody()
                .getString("export_directory"));
            assertFalse(server.lastRequestBody()
                .has("original_directory"));
            assertTrue(outBytes.toString(StandardCharsets.UTF_8)
                .contains("\"task_id\": \"scan-1\""), "an object response is printed as such");

            app.run(new String[]{ "publish-gps", "--file-group-id", "1", "--time-series-name", "gps", "--monitor" });
            assertTrue(errBytes.toString(StandardCharsets.UTF_8)
                .contains("The backend did not return a task_id; cannot monitor the request."));

            app.run(new String[]{ "tag-rename", "--tag", "old", "--to", "new" });
            assertFalse(server.lastRequestBody()
                .has("tag_name_en"), "no translations are carried over when the lookup returns no content");
            app.run(new String[]{ "tag-rename", "--tag", "old", "--to", "new" });
            assertEquals("new", server.lastRequestBody()
                .getString("replacement_tag_name"), "a lookup that lists other tags only still renames");
        }
        try (var server = new MockApiServer()) {
            server.registerJson("/api/tags/paged", 500, "{\"error\":\"boom\"}");
            server.registerJson("/api/tags/all/rename", "{\"task_id\":\"task-rename\",\"status\":\"QUEUED\"}");
            var sessionStore = new SessionStore();
            sessionStore.save(server.baseUrl(), "jwt-files");
            var outBytes = new ByteArrayOutputStream();
            var app = new VempainFileCliApplication(sessionStore, new HttpTransport(), new PrintStream(outBytes), System.err);

            app.run(new String[]{ "tag-rename", "--tag", "old", "--to", "new" });

            var output = outBytes.toString(StandardCharsets.UTF_8);
            assertTrue(output.contains("Could not look up the current translations of tag 'old'"), output);
            assertTrue(output.contains("Submitted rename of tag 'old' to 'new'."), "the rename is still sent");
        }
    }

    @Test
    void publishStatus_printsThePublicationStateOfFilesAndGroups() throws Exception {
        try (var server = new MockApiServer()) {
            server.registerJson("/api/publish/file/55/status", "{\"file_id\":55,\"published\":true,\"site_file_id\":500,\"acls\":["
                    + "{\"user_id\":12,\"unit_id\":null,\"read_privilege\":true,\"create_privilege\":false,\"modify_privilege\":true,\"delete_privilege\":false},"
                    + "{\"user_id\":null,\"unit_id\":3,\"read_privilege\":true,\"create_privilege\":false,\"modify_privilege\":false,\"delete_privilege\":false}]}");
            server.registerJson("/api/publish/file/56/status", "{\"file_id\":56,\"published\":false,\"site_file_id\":null,\"acls\":[]}");
            server.registerJson("/api/publish/file-group/11/status",
                    "{\"file_group_id\":11,\"published\":true,\"gallery_id\":1001," + "\"gallery_name\":\"Summer\",\"site_file_ids\":[500,501],\"acls\":[]}");
            server.registerJson("/api/publish/file-group/12/status",
                    "{\"file_group_id\":12,\"published\":false,\"gallery_id\":null," + "\"gallery_name\":null,\"site_file_ids\":[],\"acls\":[]}");

            var sessionStore = new SessionStore();
            sessionStore.save(server.baseUrl(), "jwt-status");
            var outBytes = new ByteArrayOutputStream();
            var errBytes = new ByteArrayOutputStream();
            var app = new VempainFileCliApplication(sessionStore, new HttpTransport(), new PrintStream(outBytes), new PrintStream(errBytes));

            app.run(new String[]{ "publish-status", "--file-id", "55" });
            var published = outBytes.toString(StandardCharsets.UTF_8);
            assertTrue(published.contains("File 55 is published as site file 500."), published);
            assertTrue(published.contains("12       -        true   false  true   false"), published);
            assertTrue(published.contains("-        3        true   false  false  false"), published);

            outBytes.reset();
            app.run(new String[]{ "publish-status", "--file-id", "56" });
            assertTrue(outBytes.toString(StandardCharsets.UTF_8)
                .contains("File 56 has not been published to Vempain Admin."));

            outBytes.reset();
            app.run(new String[]{ "publish-status", "--file-group-id", "11" });
            var group = outBytes.toString(StandardCharsets.UTF_8);
            assertTrue(group.contains("File group 11 is published as gallery 1001 (Summer) with 2 site files: 500, 501"), group);
            assertTrue(group.contains("No additional admin users or units hold privileges on it."), group);

            outBytes.reset();
            app.run(new String[]{ "publish-status", "--file-group-id", "12" });
            assertTrue(outBytes.toString(StandardCharsets.UTF_8)
                .contains("File group 12 has not been published to Vempain Admin."));

            outBytes.reset();
            app.run(new String[]{ "publish-status", "--file-group-id", "11", "--raw" });
            assertTrue(outBytes.toString(StandardCharsets.UTF_8)
                .contains("\"gallery_name\": \"Summer\""));
            outBytes.reset();
            app.run(new String[]{ "publish-status", "--file-id", "55", "--raw" });
            assertTrue(outBytes.toString(StandardCharsets.UTF_8)
                .contains("\"site_file_id\": 500"));

            // Exactly one of the two IDs
            app.run(new String[]{ "publish-status" });
            app.run(new String[]{ "publish-status", "--file-id", "55", "--file-group-id", "11" });
            var errors = errBytes.toString(StandardCharsets.UTF_8);
            assertEquals(2, errors.split("Give exactly one of --file-id and --file-group-id", -1).length - 1, errors);
            assertEquals(6, server.requests()
                .size(), "the rejected invocations made no request");
        }
    }

    @Test
    void publishFiles_rejectsInvalidIdFiles() throws Exception {
        var invalid = tempHome.resolve("invalid.txt");
        Files.writeString(invalid, "42\nabc\n");
        var zero = tempHome.resolve("zero.txt");
        Files.writeString(zero, "0\n");
        var empty = tempHome.resolve("empty.txt");
        Files.writeString(empty, "# nothing\n\n");
        var sessionStore = new SessionStore();
        sessionStore.save("http://localhost:1/api", "jwt-bulk");
        var errBytes = new ByteArrayOutputStream();
        var app = new VempainFileCliApplication(sessionStore, new HttpTransport(), new PrintStream(OutputStream.nullOutputStream()), new PrintStream(errBytes));

        app.run(new String[]{ "publish-files", "--ids-file", invalid.toString() });
        app.run(new String[]{ "publish-files", "--ids-file", zero.toString() });
        app.run(new String[]{ "publish-files", "--ids-file", empty.toString() });

        var err = errBytes.toString(StandardCharsets.UTF_8);
        assertTrue(err.contains("Invalid file ID at " + invalid + ":2: abc"), err);
        assertTrue(err.contains("Invalid file ID at " + zero + ":1: 0"), err);
        assertTrue(err.contains("The file contains no positive file IDs: " + empty), err);
    }

    @Test
    void publishFiles_fallsBackToPerFilePublishingWhenBulkPublishingIsMissing() throws Exception {
        try (var server = new MockApiServer()) {
            server.registerJson("/api/publish/files", 404, "{\"error\":\"not found\"}");
            server.registerResponder("/api/publish/file", request -> switch (request.getInt("file_id")) {
                case 42 -> new MockApiServer.Reply(200, "{\"task_id\":\"t-42\",\"status\":\"QUEUED\"}");
                case 17 -> new MockApiServer.Reply(200, "{\"status\":\"QUEUED\"}");
                default -> new MockApiServer.Reply(500, "{\"error\":\"broken\"}");
            });
            var sessionStore = new SessionStore();
            sessionStore.save(server.baseUrl(), "jwt-bulk");
            var outBytes = new ByteArrayOutputStream();
            var errBytes = new ByteArrayOutputStream();
            var monitored = new ArrayList<String>();
            var router = routerWithStubMonitor(sessionStore, outBytes, errBytes, monitored);

            router.run(new String[]{ "publish-files", "--ids-file", idsFile("fallback.txt", "42\n17\n") }, false);
            var output = outBytes.toString(StandardCharsets.UTF_8);
            assertTrue(output.contains("Bulk publishing is not available on this file backend; falling back to POST /publish/file for each ID."), output);
            assertTrue(output.contains("Submitted 2 of 2 file IDs individually."), output);
            assertEquals(List.of("POST /api/publish/files", "POST /api/publish/file", "POST /api/publish/file"), server.requests());
            assertTrue(monitored.isEmpty());

            outBytes.reset();
            router.run(new String[]{ "publish-files", "--ids-file", idsFile("partial.txt", "42\n99\n") }, false);
            output = outBytes.toString(StandardCharsets.UTF_8);
            assertTrue(output.contains("Submitted 1 of 2 file IDs individually."), output);
            assertTrue(output.contains("Failed file IDs:"), output);
            assertTrue(output.contains("99: POST " + server.baseUrl() + "/publish/file failed: HTTP 500"), output);
            assertTrue(errBytes.toString(StandardCharsets.UTF_8)
                .contains("Error: Some file IDs could not be submitted"));

            outBytes.reset();
            router.run(new String[]{ "publish-files", "--ids-file", idsFile("monitored.txt", "42\n"), "--monitor" }, false);
            assertEquals(List.of("t-42"), monitored, "the fallback tasks are monitored one by one");
            assertTrue(outBytes.toString(StandardCharsets.UTF_8)
                .contains("Task t-42: COMPLETED (100%)"));

            errBytes.reset();
            router.run(new String[]{ "publish-files", "--ids-file", idsFile("no-task.txt", "17\n"), "--monitor" }, false);
            assertTrue(errBytes.toString(StandardCharsets.UTF_8)
                .contains("The backend did not return task_id for the fallback submissions; cannot monitor them."));
        }
        try (var server = new MockApiServer()) {
            server.registerJson("/api/publish/files", 500, "{\"error\":\"server down\"}");
            var sessionStore = new SessionStore();
            sessionStore.save(server.baseUrl(), "jwt-bulk");
            var errBytes = new ByteArrayOutputStream();
            var app = new VempainFileCliApplication(sessionStore, new HttpTransport(), new PrintStream(OutputStream.nullOutputStream()),
                    new PrintStream(errBytes));

            app.run(new String[]{ "publish-files", "--ids-file", idsFile("other-error.txt", "42\n") });

            assertTrue(errBytes.toString(StandardCharsets.UTF_8)
                .contains("HTTP 500"), "other errors are not turned into a fallback");
            assertEquals(List.of("POST /api/publish/files"), server.requests());
        }
    }

    @Test
    void monitorFlag_opensTheTaskMonitorForScanAndAdminPublishing() throws Exception {
        try (var server = new MockApiServer()) {
            server.registerJson("/api/scan-files", "{\"task_id\":\"scan-7\",\"status\":\"QUEUED\"}");
            server.registerJson("/api/content-management/galleries/publish", "{\"task_id\":\"gallery-7\",\"status\":\"QUEUED\"}");
            var sessionStore = new SessionStore();
            sessionStore.save(SessionStore.BACKEND_FILE, server.baseUrl(), "file-jwt");
            sessionStore.save(SessionStore.BACKEND_ADMIN, server.baseUrl(), "admin-jwt");
            var outBytes = new ByteArrayOutputStream();
            var errBytes = new ByteArrayOutputStream();
            var monitored = new ArrayList<String>();
            var router = routerWithStubMonitor(sessionStore, outBytes, errBytes, monitored);

            router.run(new String[]{ "scan", "-o", "/music", "--monitor" }, false);
            router.run(new String[]{ "admin", "publish-group", "--id", "7", "--monitor" }, false);

            assertEquals(List.of("scan-7", "gallery-7"), monitored);
            var output = outBytes.toString(StandardCharsets.UTF_8);
            assertTrue(output.contains("Task scan-7: COMPLETED (100%)"), output);
            assertTrue(output.contains("Task gallery-7: COMPLETED (100%)"), output);
            assertTrue(errBytes.toString(StandardCharsets.UTF_8)
                .isBlank(), errBytes.toString(StandardCharsets.UTF_8));
        }
    }

    private String idsFile(String name, String content) throws IOException {
        var file = tempHome.resolve(name);
        Files.writeString(file, content);
        return file.toString();
    }

    /**
     * A router whose task monitor records the monitored task ids and reports them completed instead of opening a terminal.
     */
    private CommandRouter routerWithStubMonitor(SessionStore sessionStore, ByteArrayOutputStream outBytes, ByteArrayOutputStream errBytes,
            List<String> monitored) {
        var out = new PrintStream(outBytes);
        Function<TaskClient, TaskMonitor> monitorFactory = taskClient -> new TaskMonitor(taskClient, out) {

            @Override
            public TaskProgress monitor(SessionStore.Session session, String taskId, Duration interval) {
                monitored.add(taskId);
                return TaskProgress.fromJson(new JSONObject().put("task_id", taskId)
                    .put("status", "COMPLETED")
                    .put("percent", 100));
            }
        };
        return new CommandRouter(sessionStore, new HttpTransport(), out, new PrintStream(errBytes), prompt -> "secret".toCharArray(),
                new CommandHistory(tempHome.resolve("history")), monitorFactory);
    }
}
