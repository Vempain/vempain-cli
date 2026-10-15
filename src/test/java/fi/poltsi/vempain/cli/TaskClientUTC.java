package fi.poltsi.vempain.cli;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import fi.poltsi.vempain.cli.core.HttpTransport;
import fi.poltsi.vempain.cli.core.SessionStore;
import fi.poltsi.vempain.cli.core.TaskClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskClientUTC {

    @Test
    void getsCancelsAndDismissesTaskUsingTaskApiPaths() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        try {
            server.createContext("/api/tasks/task-1", exchange -> {
                var body = exchange.getRequestMethod()
                    .equals("DELETE") ? ""
                            : exchange.getRequestMethod()
                                .equals("POST") ? "{\"task_id\":\"task-1\",\"status\":\"CANCELLED\"}"
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
            var session = new SessionStore.Session("file", "http://localhost:"
                    + server.getAddress()
                        .getPort()
                    + "/api", "token");
            var client = new TaskClient(new HttpTransport());

            assertEquals("RUNNING", client.get(session, "task-1")
                .status());
            assertEquals("CANCELLED", client.cancel(session, "task-1")
                .status());
            client.dismiss(session, "task-1");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void list_acceptsItemsTasksAndEmptyResponses() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        try {
            server.createContext("/items/api/tasks", exchange -> respond(exchange, "[{\"task_id\":\"a\"},{\"task_id\":\"b\"}]"));
            server.createContext("/tasks/api/tasks", exchange -> respond(exchange, "{\"tasks\":[{\"task_id\":\"c\"}]}"));
            server.createContext("/none/api/tasks", exchange -> respond(exchange, "{\"page\":0}"));
            server.start();
            var client = new TaskClient(new HttpTransport());

            assertEquals(java.util.List.of("a", "b"), client.list(session(server, "items"))
                .stream()
                .map(task -> task.taskId())
                .toList());
            assertEquals(java.util.List.of("c"), client.list(session(server, "tasks"))
                .stream()
                .map(task -> task.taskId())
                .toList());
            assertTrue(client.list(session(server, "none"))
                .isEmpty());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void taskId_readsSnakeAndCamelCaseAndIgnoresBlankIds() {
        assertEquals("t-1", TaskClient.taskId(new org.json.JSONObject().put("task_id", "t-1")));
        assertEquals("t-2", TaskClient.taskId(new org.json.JSONObject().put("taskId", "t-2")));
        assertNull(TaskClient.taskId(new org.json.JSONObject().put("task_id", " ")));
        assertNull(TaskClient.taskId(new org.json.JSONObject()));
    }

    private static SessionStore.Session session(HttpServer server, String prefix) {
        return new SessionStore.Session("file", "http://localhost:"
                + server.getAddress()
                    .getPort()
                + "/"
                + prefix
                + "/api", "token");
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws java.io.IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody()
            .write(bytes);
        exchange.close();
    }
}
