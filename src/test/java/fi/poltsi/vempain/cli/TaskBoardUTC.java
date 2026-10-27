package fi.poltsi.vempain.cli;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

import fi.poltsi.vempain.cli.core.HttpTransport;
import fi.poltsi.vempain.cli.core.SessionStore;
import fi.poltsi.vempain.cli.core.TaskClient;
import fi.poltsi.vempain.cli.core.TaskProgress;
import fi.poltsi.vempain.cli.tui.TaskBoard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    private String previousHome;
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
        assertEquals("session no longer valid, log in again (HTTP 401)", admin.error());

        var lines = TaskBoard.lines(snapshot);
        assertEquals(3, lines.size());
        assertEquals("[file] RUNNING     40%  Scanning originals (4/10)", lines.get(0));
        assertEquals("[file] QUEUED       0%  PUBLISH_FILE", lines.get(1));
        assertEquals("[admin] tasks unavailable: session no longer valid, log in again (HTTP 401)", lines.get(2));
    }

    @Test
    void refresh_pollsAsBackgroundRequestsThatDoNotRenewTheSession() throws Exception {
        var backgroundHeader = new java.util.concurrent.atomic.AtomicReference<String>();
        server.createContext("/file/api/tasks", exchange -> {
            backgroundHeader.set(exchange.getRequestHeaders()
                .getFirst(HttpTransport.BACKGROUND_REQUEST_HEADER));
            var bytes = "[]".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody()
                .write(bytes);
            exchange.close();
        });
        var sessionStore = new SessionStore();
        sessionStore.save(SessionStore.BACKEND_FILE, baseUrl("file"), "file-jwt");

        new TaskBoard(sessionStore, new TaskClient(new HttpTransport())).refresh();

        assertEquals("true", backgroundHeader.get());
    }

    @Test
    void refresh_stopsPollingABackendThatAnsweredForbiddenUntilANewLogin() throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/admin/api/tasks", exchange -> {
            calls.incrementAndGet();
            var token = exchange.getRequestHeaders()
                .getFirst("Authorization");
            var ok = "Bearer fresh-jwt".equals(token);
            var bytes = (ok ? "[]" : "{\"error\":\"Forbidden\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(ok ? 200 : 403, bytes.length);
            exchange.getResponseBody()
                .write(bytes);
            exchange.close();
        });
        respond("/file/api/tasks", 500, "{\"error\":\"down\"}");
        var sessionStore = new SessionStore();
        sessionStore.save(SessionStore.BACKEND_FILE, baseUrl("file"), "file-jwt");
        sessionStore.save(SessionStore.BACKEND_ADMIN, baseUrl("admin"), "stale-jwt");
        var board = new TaskBoard(sessionStore, new TaskClient(new HttpTransport()));

        var first = board.refresh();
        assertEquals(1, calls.get());
        assertEquals("session no longer valid, log in again (HTTP 403)", first.get(1)
            .error());
        assertTrue(board.isSessionRejected(SessionStore.BACKEND_ADMIN));
        assertFalse(board.isSessionRejected(SessionStore.BACKEND_FILE), "other failures are not a rejected session");
        assertTrue(first.getFirst()
            .error()
            .contains("HTTP 500"),
                "other failures keep their message: "
                        + first.getFirst()
                            .error());

        var second = board.refresh();
        assertEquals(1, calls.get(), "the rejected session is not sent again");
        assertEquals("session no longer valid, log in again", second.get(1)
            .error());
        assertEquals("[admin] tasks unavailable: session no longer valid, log in again", TaskBoard.lines(second)
            .get(1));

        sessionStore.save(SessionStore.BACKEND_ADMIN, baseUrl("admin"), "fresh-jwt");
        var third = board.refresh();
        assertEquals(2, calls.get(), "a new login resumes the polling");
        assertNull(third.get(1)
            .error());
        assertFalse(board.isSessionRejected(SessionStore.BACKEND_ADMIN));

        sessionStore.clear(SessionStore.BACKEND_ADMIN);
        assertFalse(board.isSessionRejected(SessionStore.BACKEND_ADMIN), "no session, nothing rejected");
        assertEquals(1, board.refresh()
            .size());
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
        return "http://localhost:"
                + server.getAddress()
                    .getPort()
                + "/"
                + prefix
                + "/api";
    }

    @Test
    void refresh_stopsWhenInterruptedAndKeepsTheInterruptFlag() throws Exception {
        respond("/file/api/tasks", 200, MIXED_TASKS);
        var sessionStore = new SessionStore();
        sessionStore.save(SessionStore.BACKEND_FILE, baseUrl("file"), "file-jwt");
        var board = new TaskBoard(sessionStore, new TaskClient(new HttpTransport()));

        Thread.currentThread()
            .interrupt();
        List<TaskBoard.BackendTasks> snapshot;
        try {
            snapshot = board.refresh();
        } finally {
            assertTrue(Thread.interrupted(), "the interrupt flag is restored for the caller");
        }

        assertTrue(snapshot.isEmpty(), "an interrupted poll returns what was collected so far");
    }

    @Test
    void refresh_namesTheExceptionClassWhenItHasNoMessage() throws Exception {
        var sessionStore = new SessionStore();
        sessionStore.save(SessionStore.BACKEND_FILE, baseUrl("file"), "file-jwt");
        var failing = new TaskClient(new HttpTransport()) {

            @Override
            public List<TaskProgress> list(SessionStore.Session session, boolean background) {
                throw new IllegalStateException();
            }
        };

        var lines = TaskBoard.lines(new TaskBoard(sessionStore, failing).refresh());

        assertEquals(List.of("[file] tasks unavailable: IllegalStateException"), lines);
    }

    @Test
    void lines_fallBackToTheTaskIdWithoutTitleAndType() throws Exception {
        respond("/file/api/tasks", 200, "[{\"task_id\":\"t-9\",\"status\":\"RUNNING\",\"percent\":5}]");
        var sessionStore = new SessionStore();
        sessionStore.save(SessionStore.BACKEND_FILE, baseUrl("file"), "file-jwt");

        var lines = TaskBoard.lines(new TaskBoard(sessionStore, new TaskClient(new HttpTransport())).refresh());

        assertEquals(List.of("[file] RUNNING      5%  t-9"), lines);
    }

    @Test
    void lines_showTheRemainingTimeEstimateOfARunningTask() throws Exception {
        respond("/file/api/tasks", 200, "[{\"task_id\":\"t-9\",\"title\":\"Scan\",\"status\":\"RUNNING\",\"percent\":25,\"total_steps\":4,"
                + "\"completed_steps\":1,\"estimated_remaining_seconds\":95}]");
        var sessionStore = new SessionStore();
        sessionStore.save(SessionStore.BACKEND_FILE, baseUrl("file"), "file-jwt");

        var lines = TaskBoard.lines(new TaskBoard(sessionStore, new TaskClient(new HttpTransport())).refresh());

        assertEquals(List.of("[file] RUNNING     25%  Scan (1/4)  ~2 min left"), lines);
    }
}
