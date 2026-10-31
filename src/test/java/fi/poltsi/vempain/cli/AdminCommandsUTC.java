package fi.poltsi.vempain.cli;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fi.poltsi.vempain.cli.admin.AdminCommands;
import fi.poltsi.vempain.cli.core.HttpTransport;
import fi.poltsi.vempain.cli.core.SessionStore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Direct calls of {@link AdminCommands}; the command line paths are covered by {@link CliIntegrationUTC}.
 */
class AdminCommandsUTC {

    @TempDir
    Path tempDir;

    @Test
    void listGroups_withDefaultPagingAndNoSearch_usesTheBackendDefaults() throws Exception {
        try (var server = new CliIntegrationUTC.MockApiServer()) {
            server.registerJson("/api/content-management/galleries/paged-list",
                    "{\"content\":[{\"id\":3,\"short_name\":\"Autumn\",\"description\":\"Leaves\",\"published\":null},"
                            + "{\"id\":4,\"short_name\":\"Spring\",\"description\":\"Buds\",\"published\":\"2026-01-05T08:00:00Z\"}]}");
            var outBytes = new ByteArrayOutputStream();
            var commands = new AdminCommands(new HttpTransport(), new PrintStream(outBytes, true, StandardCharsets.UTF_8));
            var command = new AdminCommands.AdminListGroupsCommand();
            command.details = null;
            command.search = " ";

            commands.listGroups(command, new SessionStore.Session(SessionStore.BACKEND_ADMIN, server.baseUrl(), "admin-jwt"));

            var request = server.lastRequestBody();
            assertEquals(0, request.getInt("page"));
            assertEquals(25, request.getInt("size"));
            assertFalse(request.has("search"), "a blank search is not sent");
            assertFalse(request.has("modified_after_publish"), "the release filter is only sent when asked for");
            var output = outBytes.toString(StandardCharsets.UTF_8);
            assertTrue(output.contains("Autumn"), output);
            assertTrue(output.contains("Leaves"), output);
            // The release time column: never for a gallery that was not released to the website, the timestamp to the minute otherwise
            assertTrue(output.contains("published"), output);
            assertTrue(output.lines()
                .anyMatch(line -> line.contains("Autumn")
                                  && line.endsWith("never")),
                    output);
            assertTrue(output.lines()
                .anyMatch(line -> line.contains("Spring")
                                  && line.endsWith("2026-01-05 08:00")),
                    output);
        }
    }

    @Test
    void listGroups_withModifiedAfterPublish_sendsTheReleaseFilter() throws Exception {
        try (var server = new CliIntegrationUTC.MockApiServer()) {
            server.registerJson("/api/content-management/galleries/paged-list", "{\"content\":[]}");
            var outBytes = new ByteArrayOutputStream();
            var commands = new AdminCommands(new HttpTransport(), new PrintStream(outBytes, true, StandardCharsets.UTF_8));
            var command = new AdminCommands.AdminListGroupsCommand();
            command.modifiedAfterPublish = true;

            commands.listGroups(command, new SessionStore.Session(SessionStore.BACKEND_ADMIN, server.baseUrl(), "admin-jwt"));

            assertTrue(server.lastRequestBody()
                .getBoolean("modified_after_publish"));
            assertTrue(outBytes.toString(StandardCharsets.UTF_8)
                .contains("No admin groups found."));
        }
    }

    @Test
    void publishedColumn_readsTheReleaseTimeOrNever() {
        assertEquals("never", AdminCommands.publishedColumn(new JSONObject("{\"id\":1}")));
        assertEquals("never", AdminCommands.publishedColumn(new JSONObject("{\"published\":null}")));
        assertEquals("never", AdminCommands.publishedColumn(new JSONObject("{\"published\":\"\"}")));
        assertEquals("2026-01-05 08:00", AdminCommands.publishedColumn(new JSONObject("{\"published\":\"2026-01-05T08:00:00Z\"}")));
        assertEquals("2026-01-05", AdminCommands.publishedColumn(new JSONObject("{\"published\":\"2026-01-05\"}")));
    }

    @Test
    void publishSiteFiles_readsIdsAndSubmitsBackgroundTask() throws Exception {
        try (var server = new CliIntegrationUTC.MockApiServer()) {
            server.registerJson("/api/content-management/file/site-files/publish-selected", "{\"task_id\":\"site-publish-1\",\"status\":\"QUEUED\"}");
            var idsFile = tempDir.resolve("site-file-ids.txt");
            Files.writeString(idsFile, "# files to publish\n12\n12\n 13 \n");
            var outBytes = new ByteArrayOutputStream();
            var commands = new AdminCommands(new HttpTransport(), new PrintStream(outBytes, true, StandardCharsets.UTF_8));
            var command = new AdminCommands.AdminPublishSiteFilesCommand();
            command.idsFile = idsFile.toString();

            commands.publishSiteFiles(command, new SessionStore.Session(SessionStore.BACKEND_ADMIN, server.baseUrl(), "admin-jwt"));

            assertEquals(List.of("POST /api/content-management/file/site-files/publish-selected"), server.requests());
            assertEquals(List.of(12, 13), server.lastRequestBody()
                .getJSONArray("file_ids")
                .toList());
            assertTrue(outBytes.toString(StandardCharsets.UTF_8)
                .contains("Submitted 2 site file IDs"));
        }
    }

    @Test
    void publishSiteFiles_rejectsInvalidIdWithLineNumber() throws Exception {
        var idsFile = tempDir.resolve("site-file-ids.txt");
        Files.writeString(idsFile, "12\nnot-an-id\n");
        var commands = new AdminCommands(new HttpTransport(), new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        var command = new AdminCommands.AdminPublishSiteFilesCommand();
        command.idsFile = idsFile.toString();

        var exception = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> commands.publishSiteFiles(command, new SessionStore.Session(SessionStore.BACKEND_ADMIN, "http://localhost", "admin-jwt")));

        assertTrue(exception.getMessage()
            .contains(":2: not-an-id"));
    }
}
