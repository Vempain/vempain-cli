package fi.poltsi.vempain.cli;

import fi.poltsi.vempain.cli.admin.AdminCommands;
import fi.poltsi.vempain.cli.core.HttpTransport;
import fi.poltsi.vempain.cli.core.SessionStore;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Direct calls of {@link AdminCommands}; the command line paths are covered by {@link CliIntegrationUTC}.
 */
class AdminCommandsUTC {

	@Test
	void listGroups_withDefaultPagingAndNoSearch_usesTheBackendDefaults() throws Exception {
		try (var server = new CliIntegrationUTC.MockApiServer()) {
			server.registerJson("/api/content-management/galleries/paged-list", "{\"content\":[{\"id\":3,\"short_name\":\"Autumn\",\"description\":\"Leaves\"}]}");
			var outBytes = new ByteArrayOutputStream();
			var commands = new AdminCommands(new HttpTransport(), new PrintStream(outBytes, true, StandardCharsets.UTF_8));
			var command  = new AdminCommands.AdminListGroupsCommand();
			command.details = null;
			command.search  = " ";

			commands.listGroups(command, new SessionStore.Session(SessionStore.BACKEND_ADMIN, server.baseUrl(), "admin-jwt"));

			var request = server.lastRequestBody();
			assertEquals(0, request.getInt("page"));
			assertEquals(25, request.getInt("size"));
			assertFalse(request.has("search"), "a blank search is not sent");
			var output = outBytes.toString(StandardCharsets.UTF_8);
			assertTrue(output.contains("Autumn"), output);
			assertTrue(output.contains("Leaves"), output);
		}
	}
}
