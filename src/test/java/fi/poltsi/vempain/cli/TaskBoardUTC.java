package fi.poltsi.vempain.cli;

import com.sun.net.httpserver.HttpServer;
import fi.poltsi.vempain.cli.core.HttpTransport;
import fi.poltsi.vempain.cli.core.SessionStore;
import fi.poltsi.vempain.cli.core.TaskClient;
import fi.poltsi.vempain.cli.tui.TaskBoard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskBoardUTC {

	private static final String MIXED_TASKS = """
			[
			  {"task_id":"t-1","type":"SCAN","title":"Scanning originals","status":"RUNNING","percent":40,"total_steps":10,"completed_steps":4},
			  {"task_id":"t-2","type":"PUBLISH_FILE","title":"","status":"QUEUED","percent":0},
			  {"task_id":"t-3","type":"PUBLISH_MUSIC","title":"Music data set","status":"COMPLETED","percent":100},
			  {"task_id":"t-4","type":"SCAN","title":"Old scan","status":"FAILED","percent":20}
			]
			""";

	@TempDir
	Path tempHome;

	private String     previousHome;
	private HttpServer server;

	@BeforeEach
	void setUp() throws IOException {
		previousHome = System.getProperty("user.home");
		System.setProperty("user.home", tempHome.toString());
		server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		server.start();
	}

	@AfterEach
	void tearDown() {
		server.stop(0);
		System.setProperty("user.home", previousHome);
	}

	@Test
	void refresh_withoutSessions_pollsNothingAndAsksForLogin() {
		var board = new TaskBoard(new SessionStore(), new TaskClient(new HttpTransport()));

		var snapshot = board.refresh();

		assertTrue(snapshot.isEmpty());
		assertEquals(List.of("Log in to the file or admin backend to follow its tasks."), TaskBoard.lines(snapshot));
	}

	@Test
	void refresh_keepsOnlyUnfinishedTasksAndReportsUnreadableBackend() throws Exception {
		respond("/file/api/tasks", 200, MIXED_TASKS);
		respond("/admin/api/tasks", 401, "{\"error\":\"Unauthorized\"}");
		var sessionStore = new SessionStore();
		sessionStore.save(SessionStore.BACKEND_FILE, baseUrl("file"), "file-jwt");
		sessionStore.save(SessionStore.BACKEND_ADMIN, baseUrl("admin"), "admin-jwt");

		var snapshot = new TaskBoard(sessionStore, new TaskClient(new HttpTransport())).refresh();

		assertEquals(2, snapshot.size());
		var file = snapshot.getFirst();
		assertEquals(SessionStore.BACKEND_FILE, file.backend());
		assertNull(file.error());
		assertEquals(List.of("t-1", "t-2"), file.tasks()
												.stream()
												.map(task -> task.taskId())
												.toList());
		var admin = snapshot.get(1);
		assertEquals(SessionStore.BACKEND_ADMIN, admin.backend());
		assertTrue(admin.error()
						.contains("HTTP 401"));

		var lines = TaskBoard.lines(snapshot);
		assertEquals(3, lines.size());
		assertEquals("[file] RUNNING     40%  Scanning originals (4/10)", lines.get(0));
		assertEquals("[file] QUEUED       0%  PUBLISH_FILE", lines.get(1));
		assertTrue(lines.get(2)
						.startsWith("[admin] tasks unavailable: GET "));
	}

	@Test
	void lines_reportIdleBackendsWhenNothingRuns() throws Exception {
		respond("/file/api/tasks", 200, "[{\"task_id\":\"t-3\",\"status\":\"COMPLETED\",\"percent\":100}]");
		respond("/admin/api/tasks", 200, "[]");
		var sessionStore = new SessionStore();
		sessionStore.save(SessionStore.BACKEND_FILE, baseUrl("file"), "file-jwt");
		sessionStore.save(SessionStore.BACKEND_ADMIN, baseUrl("admin"), "admin-jwt");

		var lines = TaskBoard.lines(new TaskBoard(sessionStore, new TaskClient(new HttpTransport())).refresh());

		assertEquals(List.of("No running tasks on file or admin."), lines);
	}

	private void respond(String path, int status, String body) {
		server.createContext(path, exchange -> {
			var bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders()
					.add("Content-Type", "application/json");
			exchange.sendResponseHeaders(status, bytes.length);
			exchange.getResponseBody()
					.write(bytes);
			exchange.close();
		});
	}

	private String baseUrl(String prefix) {
		return "http://localhost:" + server.getAddress()
										   .getPort() + "/" + prefix + "/api";
	}
}
