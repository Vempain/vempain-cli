package fi.poltsi.vempain.cli;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import fi.poltsi.vempain.cli.core.HttpTransport;
import fi.poltsi.vempain.cli.core.SessionStore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpTransportUTC {

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        baseUrl = "http://localhost:"
                + server.getAddress()
                    .getPort()
                + "/api";
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void login_parsesObjectResponse() throws Exception {
        register("/api/login", 200, "application/json", "{\"token\":\"token-obj\"}");

        var client = new HttpTransport();
        var response = client.login(baseUrl, "user", "pass".toCharArray());

        assertEquals("token-obj", response.getString("token"));
    }

    @Test
    void getJsonFlexible_wrapsArrayPayloadAsItems() throws Exception {
        register("/api/content-management/galleries", 200, "application/json", "[{\"id\":1}]");

        var client = new HttpTransport();
        var response = client.getJsonFlexible(new SessionStore.Session(baseUrl, "abc"), "/content-management/galleries?details=FULL");

        assertTrue(response.has("items"));
        assertEquals(1, response.getJSONArray("items")
            .length());
    }

    @Test
    void patchJson_returnsResponsePayload() throws Exception {
        register("/api/content-management/galleries/publish", 200, "application/json", "[{\"result\":\"OK\"}]");

        var client = new HttpTransport();
        var response = client.patchJson(new SessionStore.Session(baseUrl, "abc"), "/content-management/galleries/publish", new JSONObject().put("id", 1));

        assertTrue(response.has("items"));
        assertEquals("OK", response.getJSONArray("items")
            .getJSONObject(0)
            .getString("result"));
    }

    @Test
    void postJson_wrapsArrayPayloadAsItems() throws Exception {
        register("/api/scan-files", 200, "application/json", "[{\"success\":true}]");

        var client = new HttpTransport();
        var response = client.postJson(new SessionStore.Session(baseUrl, "abc"), "/scan-files", new JSONObject());

        assertTrue(response.has("items"));
    }

    @Test
    void getJson_throwsOnNon2xx() {
        register("/api/files/music/1", 404, "application/json", "{\"error\":\"not found\"}");

        var client = new HttpTransport();
        var exception = assertThrows(IOException.class, () -> client.getJson(new SessionStore.Session(baseUrl, "abc"), "/files/music/1"));
        assertTrue(exception.getMessage()
            .contains("HTTP 404"));
    }

    @Test
    void getBytes_returnsPayload() throws Exception {
        register("/api/files/42/content", 200, "text/plain", "content");

        var client = new HttpTransport();
        var response = client.getBytes(new SessionStore.Session(baseUrl, "abc"), "/files/42/content");
        assertEquals("content", new String(response.body(), StandardCharsets.UTF_8));
    }

    @Test
    void login_emptyArrayThrows() {
        register("/api/login", 200, "application/json", "[]");

        var client = new HttpTransport();
        var exception = assertThrows(IOException.class, () -> client.login(baseUrl, "user", "pass".toCharArray()));
        assertTrue(exception.getMessage()
            .contains("empty"));
    }

    @Test
    void postJson_emptyBodyReturnsEmptyJsonObject() throws Exception {
        register("/api/empty", 200, "application/json", "");

        var client = new HttpTransport();
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

    @Test
    void login_throwsOnNon2xx() {
        register("/api/login", 401, "application/json", "{\"error\":\"bad credentials\"}");

        var client = new HttpTransport();
        var password = "pass".toCharArray();
        var exception = assertThrows(IOException.class, () -> client.login(baseUrl, "user", password));

        assertTrue(exception.getMessage()
            .contains("Login failed: HTTP 401"), exception.getMessage());
        assertTrue(exception.getMessage()
            .contains("bad credentials"));
        assertEquals("\0\0\0\0", new String(password), "the password is zeroed even when the login fails");
    }

    @Test
    void getJsonFlexible_returnsEmptyObjectForEmptyBodyAndObjectsAsTheyAre() throws Exception {
        register("/api/empty", 200, "application/json", "");
        register("/api/object", 200, "application/json", "{\"task_id\":\"t-1\"}");

        var client = new HttpTransport();
        assertEquals(0, client.getJsonFlexible(new SessionStore.Session(baseUrl, "abc"), "/empty")
            .length());
        assertEquals("t-1", client.getJsonFlexible(new SessionStore.Session(baseUrl, "abc"), "/object")
            .getString("task_id"));
        assertFalse(client.getJsonFlexible(new SessionStore.Session(baseUrl, "abc"), "/object")
            .has("items"));
    }

    @Test
    void requestFailures_nameMethodUrlAndStatus() {
        register("/api/failing", 500, "application/json", "{\"error\":\"down\"}");
        var client = new HttpTransport();
        var session = new SessionStore.Session(baseUrl, "abc");

        assertEquals("POST " + baseUrl + "/failing failed: HTTP 500 body: {\"error\":\"down\"}",
                assertThrows(IOException.class, () -> client.postJson(session, "/failing", new JSONObject())).getMessage());
        assertEquals("PATCH " + baseUrl + "/failing failed: HTTP 500 body: {\"error\":\"down\"}",
                assertThrows(IOException.class, () -> client.patchJson(session, "/failing", new JSONObject())).getMessage());
        assertEquals("DELETE " + baseUrl + "/failing failed: HTTP 500 body: {\"error\":\"down\"}",
                assertThrows(IOException.class, () -> client.delete(session, "/failing")).getMessage());
        assertEquals("GET " + baseUrl + "/failing failed: HTTP 500 body: {\"error\":\"down\"}",
                assertThrows(IOException.class, () -> client.getBytes(session, "/failing")).getMessage());
        assertEquals("GET " + baseUrl + "/failing failed: HTTP 500 body: {\"error\":\"down\"}",
                assertThrows(IOException.class, () -> client.getJsonFlexible(session, "/failing")).getMessage());
    }

    @Test
    void patchJson_emptyBodyReturnsEmptyJsonObject() throws Exception {
        register("/api/empty", 200, "application/json", "");

        var response = new HttpTransport().patchJson(new SessionStore.Session(baseUrl, "abc"), "/empty", new JSONObject());

        assertEquals(0, response.length());
    }

    @Test
    void delete_acceptsAnyTwoHundredStatus() throws Exception {
        register("/api/tasks/t-1", 204, "application/json", "");

        new HttpTransport().delete(new SessionStore.Session(baseUrl, "abc"), "/tasks/t-1");
    }

    @Test
    void request_prependsTheMissingSlashToThePath() throws Exception {
        register("/api/version", 200, "application/json", "{\"version\":\"1.0\"}");

        var response = new HttpTransport().getJson(baseUrl, "version", null);

        assertEquals("1.0", response.getString("version"));
    }
}
