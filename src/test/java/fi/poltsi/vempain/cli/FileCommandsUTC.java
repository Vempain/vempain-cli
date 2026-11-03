package fi.poltsi.vempain.cli;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jline.reader.Candidate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fi.poltsi.vempain.cli.core.HttpTransport;
import fi.poltsi.vempain.cli.core.SessionStore;
import fi.poltsi.vempain.cli.file.FileCommands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Direct calls of {@link FileCommands}; the command line paths are covered by {@link CliIntegrationUTC}.
 */
class FileCommandsUTC {

    @TempDir
    Path tempHome;

    private String previousHome;
    private CliIntegrationUTC.MockApiServer server;
    private SessionStore sessionStore;
    private ByteArrayOutputStream outBytes;
    private FileCommands commands;

    @BeforeEach
    void setUp() throws Exception {
        previousHome = System.getProperty("user.home");
        System.setProperty("user.home", tempHome.toString());
        server = new CliIntegrationUTC.MockApiServer();
        sessionStore = new SessionStore();
        sessionStore.save(server.baseUrl(), "jwt");
        outBytes = new ByteArrayOutputStream();
        commands = new FileCommands(sessionStore, new HttpTransport(), new PrintStream(outBytes, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void tearDown() {
        server.close();
        System.setProperty("user.home", previousHome);
    }

    @Test
    void publishMusic_withoutArguments_submitsWithoutMonitoring() throws Exception {
        server.registerJson("/api/data-publish/music", "{\"identifier\":\"music_library\"}");

        commands.publishMusic();

        assertEquals(List.of("POST /api/data-publish/music"), server.requests());
        assertTrue(outBytes.toString(StandardCharsets.UTF_8)
            .contains("music_library"));
    }

    @Test
    void updateMetadata_requiresTheCurrentValue() {
        var command = new FileCommands.MetadataUpdateCommand();
        command.group = "XMP-dc";
        command.key = "Creator";
        command.newValue = "x";

        var exception = assertThrows(IllegalArgumentException.class, () -> commands.updateMetadata(command));

        assertEquals("--value is required", exception.getMessage());
        assertTrue(server.requests()
            .isEmpty());
    }

    @Test
    void completeScanPath_coversWordPositionsAndCompletionShapes() {
        server.registerPathCompletionByPath(Map.of("/", "{\"completions\":[\"music\",\"\",\"/video/\"]}", "abc", "{\"completions\":[\"abc-dir\"]}", "/music/G",
                "{\"completions\":[\"Green_Day\"]}", "/x", "{}"));
        var silent = new PrintStream(ByteArrayOutputStream.nullOutputStream());

        var candidates = new ArrayList<Candidate>();
        commands.completeScanPath(List.of("scan"), 0, candidates, "", false, silent);
        assertTrue(candidates.isEmpty(), "the command word itself is never completed");

        commands.completeScanPath(List.of("scan", "--monitor", "abc"), 2, candidates, "abc", false, silent);
        assertTrue(candidates.isEmpty(), "a path is only completed after a directory option");

        commands.completeScanPath(List.of("scan", "-o", ""), 2, candidates, "", false, silent);
        assertEquals(List.of("/music/", "", "/video/"), values(candidates), "relative, blank and directory completions of the root");

        candidates.clear();
        commands.completeScanPath(List.of("scan", "-o", "abc"), 2, candidates, "abc", false, silent);
        assertEquals(List.of("/abc-dir/"), values(candidates), "a word without slash is queried as typed");

        candidates.clear();
        commands.completeScanPath(List.of("scan", "-o", "/music/", "G"), 3, candidates, "G", false, silent);
        assertEquals(List.of("Green_Day/"), values(candidates), "a relative completion is resolved against the query directory");

        candidates.clear();
        commands.completeScanPath(List.of("scan", "-e", "/x"), 2, candidates, "/x", false, silent);
        assertTrue(candidates.isEmpty(), "a response without completions yields nothing");

        var requests = server.requests()
            .size();
        commands.completeScanPath(List.of("scan", "-o", "--m"), 2, candidates, "--m", false, silent);
        assertTrue(candidates.isEmpty());
        assertEquals(requests, server.requests()
            .size(), "an option being typed is not looked up as a path");
    }

    @Test
    void completeScanPath_ignoresBackendFailures() throws Exception {
        try (var failing = new CliIntegrationUTC.MockApiServer()) {
            failing.registerJson("/api/path-completion", 500, "{\"error\":\"down\"}");
            sessionStore.save(failing.baseUrl(), "jwt");
            var candidates = new ArrayList<Candidate>();

            commands.completeScanPath(List.of("scan", "-o", "/mu"), 2, candidates, "/mu", true, new PrintStream(outBytes, true, StandardCharsets.UTF_8));

            assertTrue(candidates.isEmpty());
            assertTrue(outBytes.toString(StandardCharsets.UTF_8)
                .contains("[completion-debug] path-completion request:"), "the request is still logged");
            assertEquals(List.of("POST /api/path-completion"), failing.requests());
        }
    }

    private static List<String> values(List<Candidate> candidates) {
        return candidates.stream()
            .map(Candidate::value)
            .toList();
    }

    @Test
    void parseAclSpecsTurnsTheOptionValuesIntoGranteeRows() {
        // no values: no list, the grantees of an existing data set stay
        assertNull(FileCommands.parseAclSpecs(null));
        assertNull(FileCommands.parseAclSpecs(List.of()));
        // none: an empty list removes every additional grantee
        assertTrue(FileCommands.parseAclSpecs(List.of("none"))
            .isEmpty());
        assertTrue(FileCommands.parseAclSpecs(List.of(" NONE "))
            .isEmpty());

        var acls = FileCommands.parseAclSpecs(List.of("user:12:rcmd", "unit:3:R", " user:7:m "));
        assertEquals(3, acls.length());
        var full = acls.getJSONObject(0);
        assertEquals(12L, full.getLong("user_id"));
        assertTrue(full.isNull("unit_id"));
        assertTrue(full.getBoolean("read_privilege")
                   && full.getBoolean("create_privilege")
                   && full.getBoolean("modify_privilege")
                   && full.getBoolean("delete_privilege"));
        var unit = acls.getJSONObject(1);
        assertTrue(unit.isNull("user_id"));
        assertEquals(3L, unit.getLong("unit_id"));
        assertTrue(unit.getBoolean("read_privilege"));
        assertFalse(unit.getBoolean("modify_privilege"));
        var modifyOnly = acls.getJSONObject(2);
        assertEquals(7L, modifyOnly.getLong("user_id"));
        assertFalse(modifyOnly.getBoolean("read_privilege"));
        assertTrue(modifyOnly.getBoolean("modify_privilege"));

        for (var invalid : List.of("user:12", "user:12:r:x", "group:3:r", "user:abc:r", "user:0:r", "user:-1:r", "user:12:", "user:12:rz", "none:1:r")) {
            var error = assertThrows(IllegalArgumentException.class, () -> FileCommands.parseAclSpecs(List.of(invalid)), invalid);
            assertTrue(error.getMessage()
                .startsWith("Invalid --acl value '" + invalid + "'"), error.getMessage());
        }
        // 'none' only stands alone
        assertThrows(IllegalArgumentException.class, () -> FileCommands.parseAclSpecs(List.of("none", "user:12:r")));
    }
}
