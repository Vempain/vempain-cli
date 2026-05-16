package fi.poltsi.vempain.file.cli;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class BackendClientUTC {

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        baseUrl = "http://localhost:" + server.getAddress()
                .getPort() + "/api";
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void login_parsesObjectResponse() throws Exception {
        register("/api/login", 200, "application/json", "{\"token\":\"token-obj\"}");

        var client = new BackendClient();
        var response = client.login(baseUrl, "user", "pass".toCharArray());

        assertEquals("token-obj", response.getString("token"));
    }

    @Test
    void getJsonFlexible_wrapsArrayPayloadAsItems() throws Exception {
        register("/api/content-management/galleries", 200, "application/json", "[{\"id\":1}]");

        var client = new BackendClient();
        var response = client.getJsonFlexible(new SessionStore.Session(baseUrl, "abc"), "/content-management/galleries?details=FULL");

        assertTrue(response.has("items"));
        assertEquals(1, response.getJSONArray("items").length());
    }

    @Test
    void patchJson_returnsResponsePayload() throws Exception {
        register("/api/content-management/galleries/publish", 200, "application/json", "[{\"result\":\"OK\"}]");

        var client = new BackendClient();
        var response = client.patchJson(new SessionStore.Session(baseUrl, "abc"), "/content-management/galleries/publish", new JSONObject().put("id", 1));

        assertTrue(response.has("items"));
        assertEquals("OK", response.getJSONArray("items").getJSONObject(0).getString("result"));
    }

    @Test
    void postJson_wrapsArrayPayloadAsItems() throws Exception {
        register("/api/scan-files", 200, "application/json", "[{\"success\":true}]");

        var client = new BackendClient();
        var response = client.postJson(new SessionStore.Session(baseUrl, "abc"), "/scan-files", new JSONObject());

        assertTrue(response.has("items"));
    }

    @Test
    void getJson_throwsOnNon2xx() {
        register("/api/files/music/1", 404, "application/json", "{\"error\":\"not found\"}");

        var client = new BackendClient();
        var exception = assertThrows(IOException.class,
                () -> client.getJson(new SessionStore.Session(baseUrl, "abc"), "/files/music/1"));
        assertTrue(exception.getMessage()
                .contains("HTTP 404"));
    }

    @Test
    void getBytes_returnsPayload() throws Exception {
        register("/api/files/42/content", 200, "text/plain", "content");

        var client = new BackendClient();
        var response = client.getBytes(new SessionStore.Session(baseUrl, "abc"), "/files/42/content");
        assertEquals("content", new String(response.body(), StandardCharsets.UTF_8));
    }

    @Test
    void login_emptyArrayThrows() {
        register("/api/login", 200, "application/json", "[]");

        var client = new BackendClient();
        var exception = assertThrows(IOException.class, () -> client.login(baseUrl, "user", "pass".toCharArray()));
        assertTrue(exception.getMessage()
                .contains("empty"));
    }

    @Test
    void postJson_emptyBodyReturnsEmptyJsonObject() throws Exception {
        register("/api/empty", 200, "application/json", "");

        var client = new BackendClient();
        var response = client.postJson(new SessionStore.Session(baseUrl, "abc"), "/empty", new JSONObject());
        assertEquals(0, response.length());
    }

    private void register(String path, int code, String contentType, String body) {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        server.createContext(path, (HttpExchange exchange) -> {
            exchange.getResponseHeaders()
                    .set("Content-Type", contentType);
            exchange.sendResponseHeaders(code, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
    }
}

