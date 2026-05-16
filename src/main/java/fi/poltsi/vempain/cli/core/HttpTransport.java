package fi.poltsi.vempain.cli.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;

public class HttpTransport {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * Authenticate against the backend. The {@code password} char array is
     * zeroed by this method as soon as it has been serialised into the request
     * body, regardless of success or failure.
     */
    public JSONObject login(String baseUrl, String username, char[] password) throws IOException, InterruptedException {
        String passwordStr;
        try {
            passwordStr = new String(password);
        } finally {
            Arrays.fill(password, '\0');
        }
        var requestJson = new JSONObject()
                .put("login", username)
                .put("password", passwordStr);

        var response = request("POST", baseUrl, "/login", null, requestJson.toString(), "application/json");
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Login failed: HTTP " + response.statusCode() + " body: " + responseBody(response));
        }

        var body = responseBody(response).trim();
        if (body.startsWith("[")) {
            var array = new JSONArray(body);
            if (array.isEmpty()) {
                throw new IOException("Login response array was empty");
            }
            return array.getJSONObject(0);
        }
        return new JSONObject(body);
    }

    public JSONObject getJson(SessionStore.Session session, String path) throws IOException, InterruptedException {
        var response = request("GET", session.baseUrl(), path, session.token(), null, "application/json");
        if (response.statusCode() / 100 != 2) {
            throw new IOException("GET " + path + " failed: HTTP " + response.statusCode() + " body: " + responseBody(response));
        }
        return new JSONObject(responseBody(response));
    }

    public JSONObject getJsonFlexible(SessionStore.Session session, String path) throws IOException, InterruptedException {
        var response = request("GET", session.baseUrl(), path, session.token(), null, "application/json");
        if (response.statusCode() / 100 != 2) {
            throw new IOException("GET " + path + " failed: HTTP " + response.statusCode() + " body: " + responseBody(response));
        }
        var payload = responseBody(response).trim();
        if (payload.isEmpty()) {
            return new JSONObject();
        }
        if (payload.startsWith("[")) {
            return new JSONObject().put("items", new JSONArray(payload));
        }
        return new JSONObject(payload);
    }

    public JSONObject postJson(SessionStore.Session session, String path, JSONObject body) throws IOException, InterruptedException {
        var response = request("POST", session.baseUrl(), path, session.token(), body.toString(), "application/json");
        if (response.statusCode() / 100 != 2) {
            throw new IOException("POST " + path + " failed: HTTP " + response.statusCode() + " body: " + responseBody(response));
        }
        var payload = responseBody(response).trim();
        if (payload.isEmpty()) {
            return new JSONObject();
        }
        if (payload.startsWith("[")) {
            return new JSONObject().put("items", new JSONArray(payload));
        }
        return new JSONObject(payload);
    }

    public JSONObject patchJson(SessionStore.Session session, String path, JSONObject body) throws IOException, InterruptedException {
        var response = request("PATCH", session.baseUrl(), path, session.token(), body.toString(), "application/json");
        if (response.statusCode() / 100 != 2) {
            throw new IOException("PATCH " + path + " failed: HTTP " + response.statusCode() + " body: " + responseBody(response));
        }
        var payload = responseBody(response).trim();
        if (payload.isEmpty()) {
            return new JSONObject();
        }
        if (payload.startsWith("[")) {
            return new JSONObject().put("items", new JSONArray(payload));
        }
        return new JSONObject(payload);
    }

    public HttpResponse<byte[]> getBytes(SessionStore.Session session, String path) throws IOException, InterruptedException {
        var response = request("GET", session.baseUrl(), path, session.token(), null, "*/*");
        if (response.statusCode() / 100 != 2) {
            throw new IOException("GET " + path + " failed: HTTP " + response.statusCode() + " body: " + responseBody(response));
        }
        return response;
    }

    private HttpResponse<byte[]> request(String method,
                                         String baseUrl,
                                         String path,
                                         String token,
                                         String body,
                                         String accept) throws IOException, InterruptedException {
        var fullUri = URI.create(baseUrl + (path.startsWith("/") ? path : "/" + path));
        var builder = HttpRequest.newBuilder(fullUri)
                .timeout(Duration.ofSeconds(30))
                .header("Accept", accept);

        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }

        if ("POST".equals(method)) {
            builder.header("Content-Type", "application/json");
            builder.POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body, StandardCharsets.UTF_8));
        } else if ("PATCH".equals(method)) {
            builder.header("Content-Type", "application/json");
            builder.method("PATCH", HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body, StandardCharsets.UTF_8));
        } else {
            builder.GET();
        }

        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private String responseBody(HttpResponse<byte[]> response) {
        return new String(response.body(), StandardCharsets.UTF_8);
    }
}

