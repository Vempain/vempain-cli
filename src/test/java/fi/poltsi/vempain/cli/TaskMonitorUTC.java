package fi.poltsi.vempain.cli;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.terminal.Terminal;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;

import fi.poltsi.vempain.cli.core.HttpTransport;
import fi.poltsi.vempain.cli.core.SessionStore;
import fi.poltsi.vempain.cli.core.TaskClient;
import fi.poltsi.vempain.cli.core.TaskMonitor;
import fi.poltsi.vempain.cli.core.TaskProgress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskMonitorUTC {

    private static final int COLUMNS = 40;
    private static final int ROWS = 8;

    private static final String RUNNING = "{\"task_id\":\"task-1\",\"title\":\"Scanning originals\",\"status\":\"RUNNING\",\"percent\":40,\"total_steps\":10,"
            + "\"completed_steps\":4,\"message\":\"Working on /music\"}";
    private static final String COMPLETED = "{\"task_id\":\"task-1\",\"title\":\"Scanning originals\",\"status\":\"COMPLETED\",\"percent\":100,\"total_steps\":10,"
            + "\"completed_steps\":10,\"message\":\"Done\"}";
    private static final String CANCELLED = "{\"task_id\":\"task-1\",\"title\":\"Scanning originals\",\"status\":\"CANCELLED\",\"percent\":40,"
            + "\"error_message\":\"Cancelled by user\"}";

    private CliIntegrationUTC.MockApiServer server;
    private DefaultVirtualTerminal terminal;
    private ByteArrayOutputStream outBytes;
    private SessionStore.Session session;

    @BeforeEach
    void setUp() throws Exception {
        server = new CliIntegrationUTC.MockApiServer();
        terminal = new DefaultVirtualTerminal(new TerminalSize(COLUMNS, ROWS));
        outBytes = new ByteArrayOutputStream();
        session = new SessionStore.Session(SessionStore.BACKEND_FILE, server.baseUrl(), "jwt");
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void monitor_rendersTheProgressAndReturnsWhenTheTaskFinishes() throws Exception {
        server.registerJsonSequence("/api/tasks/task-1", RUNNING, COMPLETED);

        var progress = monitor().monitor(session, "task-1", Duration.ofMillis(10));

        assertEquals("COMPLETED", progress.status());
        assertEquals(List.of("GET /api/tasks/task-1", "GET /api/tasks/task-1"), server.requests());
        var screen = screen();
        assertTrue(screen.getFirst()
            .startsWith("Vempain task monitor (press c to cancel"), screen.getFirst());
        assertTrue(screen.getFirst()
            .endsWith("…"), "the header is longer than the terminal, so it is truncated: " + screen.getFirst());
        assertEquals("Scanning originals", screen.get(1)
            .trim());
        assertEquals("Status: COMPLETED    Progress: 100%", screen.get(2)
            .trim());
        assertEquals("Steps: 10/10    Failed: 0    Reverted: 0", screen.get(3)
            .trim());
        assertEquals("Message: Done", screen.get(4)
            .trim());

        monitor().printFinal(progress);
        var output = outBytes.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("Task task-1: COMPLETED (100%)"), output);
        assertTrue(output.contains("Done"), output);
        assertFalse(output.contains("Error:"), output);
    }

    @Test
    void monitor_cKeyCancelsTheTask() throws Exception {
        server.registerJson("/api/tasks/task-1", RUNNING);
        server.registerJson("/api/tasks/task-1/cancel", CANCELLED);
        terminal.addInput(new KeyStroke('c', false, false));

        var monitor = monitor();
        var progress = monitor.monitor(session, "task-1", Duration.ofMillis(10));
        monitor.printFinal(progress);

        assertEquals("CANCELLED", progress.status());
        assertEquals(List.of("GET /api/tasks/task-1", "POST /api/tasks/task-1/cancel"), server.requests());
        assertEquals("Error: Cancelled by user", screen().get(4)
            .trim());
        var output = outBytes.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("Task task-1: CANCELLED (40%)"), output);
        assertTrue(output.contains("Error: Cancelled by user"), output);
    }

    @Test
    void monitor_escapeLeavesWithoutCancelling() throws Exception {
        server.registerJson("/api/tasks/task-1", RUNNING);
        terminal.addInput(new KeyStroke(KeyType.Escape));

        var progress = monitor().monitor(session, "task-1", Duration.ofMillis(10));

        assertEquals("RUNNING", progress.status());
        assertEquals(List.of("GET /api/tasks/task-1"), server.requests());
        assertEquals("Message: Working on /music", screen().get(4)
            .trim());
    }

    @Test
    void lines_fallBackToTheTaskIdWithoutTitleAndOmitBlankMessages() {
        var lines = TaskMonitor.lines(TaskProgress.fromJson(new JSONObject().put("task_id", "task-9")
            .put("status", "QUEUED")));

        assertEquals(List.of("Vempain task monitor (press c to cancel, Esc to leave)", "task-9", "Status: QUEUED    Progress: 0%",
                "Steps: 0/0    Failed: 0    Reverted: 0"), lines);
    }

    /**
     * The monitor draws on the virtual terminal; leaving private mode is suppressed so that the drawn screen can still be read after the monitor has returned.
     */
    private TaskMonitor monitor() {
        var keepPrivateMode = (Terminal) Proxy.newProxyInstance(Terminal.class.getClassLoader(), new Class[]{ Terminal.class }, (proxy, method, args) -> {
            if ("exitPrivateMode".equals(method.getName())
                || "close".equals(method.getName())) {
                return null;
            }
            return method.invoke(terminal, args);
        });
        return new TaskMonitor(new TaskClient(new HttpTransport()), new PrintStream(outBytes, true, StandardCharsets.UTF_8)) {

            @Override
            protected Terminal createTerminal() {
                return keepPrivateMode;
            }
        };
    }

    private List<String> screen() {
        var lines = new ArrayList<String>(ROWS);
        for (int row = 0; row < ROWS; row++) {
            var line = new StringBuilder(COLUMNS);
            for (int column = 0; column < COLUMNS; column++) {
                line.append(terminal.getCharacter(column, row)
                    .getCharacterString());
            }
            lines.add(line.toString());
        }
        return lines;
    }
}
