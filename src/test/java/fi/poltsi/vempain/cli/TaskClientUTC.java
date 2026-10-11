package fi.poltsi.vempain.cli;

import com.sun.net.httpserver.HttpServer;
import fi.poltsi.vempain.cli.core.HttpTransport;
import fi.poltsi.vempain.cli.core.SessionStore;
import fi.poltsi.vempain.cli.core.TaskClient;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskClientUTC {

	@Test
	void getsCancelsAndDismissesTaskUsingTaskApiPaths() throws Exception {
		var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		try {
			server.createContext("/api/tasks/task-1", exchange -> {
				var body = exchange.getRequestMethod()
				                   .equals("DELETE") ? "" :
						   exchange.getRequestMethod()
				                   .equals("POST")
						   ? "{\"task_id\":\"task-1\",\"status\":\"CANCELLED\"}"
						   : "{\"task_id\":\"task-1\",\"status\":\"RUNNING\",\"percent\":25}";
				var bytes = body.getBytes(StandardCharsets.UTF_8);
				exchange.sendResponseHeaders(exchange.getRequestMethod()
				                                     .equals("DELETE") ? 204 : 200, bytes.length);
				if (bytes.length > 0) {
					exchange.getResponseBody()
					        .write(bytes);
				}
				exchange.close();
			});
			server.start();
			var session = new SessionStore.Session("file", "http://localhost:" + server.getAddress()
			                                                                           .getPort() + "/api", "token");
			var client  = new TaskClient(new HttpTransport());

			assertEquals("RUNNING", client.get(session, "task-1")
			                              .status());
			assertEquals("CANCELLED", client.cancel(session, "task-1")
			                                .status());
			client.dismiss(session, "task-1");
		} finally {
			server.stop(0);
		}
	}
}
