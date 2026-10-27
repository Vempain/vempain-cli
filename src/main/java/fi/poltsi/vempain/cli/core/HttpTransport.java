package fi.poltsi.vempain.cli.core;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The only HTTP client of the CLI. Every request made with a session that the user triggered renews the session: the backend answers with a fresh token in
 * {@value #RENEWED_TOKEN_HEADER}, which is handed to the {@link TokenRenewalListener} so that the session store keeps the token whose lifetime started at the
 * last action. Requests made with {@code background = true} (task polling) carry {@value #BACKGROUND_REQUEST_HEADER} and are not renewed.
 */
public class HttpTransport {

    /** Response header of the backends carrying a renewed session token (vempain-auth {@code Constants.RENEWED_TOKEN_HEADER}). */
    public static final String RENEWED_TOKEN_HEADER = "X-Vempain-Token";
    /** Response header carrying the expiry of the renewed token as an ISO-8601 instant. */
    public static final String RENEWED_TOKEN_EXPIRES_AT_HEADER = "X-Vempain-Token-Expires-At";
    /** Request header marking a request the user did not trigger; the backend serves it without renewing the session. */
    public static final String BACKGROUND_REQUEST_HEADER = "X-Vempain-Background";

    /**
     * Told when a backend renewed the session's token.
     */
    @FunctionalInterface
    public interface TokenRenewalListener {

        /**
         * @param session   the session the request was made with (its token is the old one)
         * @param token     the renewed token
         * @param expiresAt the expiry of the renewed token as an ISO-8601 instant, or null when the backend did not send it
         */
        void renewed(SessionStore.Session session, String token, String expiresAt);
    }

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();
    private volatile TokenRenewalListener tokenRenewalListener;

    public void setTokenRenewalListener(TokenRenewalListener listener) {
        this.tokenRenewalListener = listener;
    }

    /**
     * Authenticate against the backend. The {@code password} char array is zeroed by this method as soon as it has been serialised into the request body,
     * regardless of success or failure.
     */
    public JSONObject login(String baseUrl, String username, char[] password) throws IOException, InterruptedException {
        String passwordStr;
        try {
            passwordStr = new String(password);
        } finally {
            Arrays.fill(password, '\0');
        }
        var requestJson = new JSONObject().put("login", username)
            .put("password", passwordStr);

        var response = request("POST", null, baseUrl, "/login", null, requestJson.toString(), "application/json", false);
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
        return getJson(session, path, false);
    }

    /**
     * @param background the request is not a user action (polling): it carries {@value #BACKGROUND_REQUEST_HEADER} and does not renew the session
     */
    public JSONObject getJson(SessionStore.Session session, String path, boolean background) throws IOException, InterruptedException {
        var response = request("GET", session, session.baseUrl(), path, session.token(), null, "application/json", background);
        if (response.statusCode() / 100 != 2) {
            throw requestFailure("GET", session.baseUrl(), path, response);
        }
        return new JSONObject(responseBody(response));
    }

    /**
     * A request outside a stored session (the version check right after a login); it is never renewed.
     */
    public JSONObject getJson(String baseUrl, String path, String token) throws IOException, InterruptedException {
        var response = request("GET", null, baseUrl, path, token, null, "application/json", false);
        if (response.statusCode() / 100 != 2) {
            throw requestFailure("GET", baseUrl, path, response);
        }
        return new JSONObject(responseBody(response));
    }

    public JSONObject getJsonFlexible(SessionStore.Session session, String path) throws IOException, InterruptedException {
        return getJsonFlexible(session, path, false);
    }

    /**
     * @param background the request is not a user action (polling): it carries {@value #BACKGROUND_REQUEST_HEADER} and does not renew the session
     */
    public JSONObject getJsonFlexible(SessionStore.Session session, String path, boolean background) throws IOException, InterruptedException {
        var response = request("GET", session, session.baseUrl(), path, session.token(), null, "application/json", background);
        if (response.statusCode() / 100 != 2) {
            throw requestFailure("GET", session.baseUrl(), path, response);
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
        var response = request("POST", session, session.baseUrl(), path, session.token(), body.toString(), "application/json", false);
        if (response.statusCode() / 100 != 2) {
            throw requestFailure("POST", session.baseUrl(), path, response);
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
        var response = request("PATCH", session, session.baseUrl(), path, session.token(), body.toString(), "application/json", false);
        if (response.statusCode() / 100 != 2) {
            throw requestFailure("PATCH", session.baseUrl(), path, response);
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

    public void delete(SessionStore.Session session, String path) throws IOException, InterruptedException {
        var response = request("DELETE", session, session.baseUrl(), path, session.token(), null, "application/json", false);
        if (response.statusCode() / 100 != 2) {
            throw requestFailure("DELETE", session.baseUrl(), path, response);
        }
    }

    public HttpResponse<byte[]> getBytes(SessionStore.Session session, String path) throws IOException, InterruptedException {
        var response = request("GET", session, session.baseUrl(), path, session.token(), null, "*/*", false);
        if (response.statusCode() / 100 != 2) {
            throw requestFailure("GET", session.baseUrl(), path, response);
        }
        return response;
    }

    /**
     * @param session    the stored session the request belongs to, or null; a renewed token in the response is reported for it
     * @param background marks a request the user did not trigger, which the backend does not renew
     */
    private HttpResponse<byte[]> request(String method, SessionStore.Session session, String baseUrl, String path, String token, String body, String accept,
            boolean background) throws IOException, InterruptedException {
        var fullUri = URI.create(baseUrl + (path.startsWith("/") ? path : "/" + path));
        var builder = HttpRequest.newBuilder(fullUri)
            .timeout(Duration.ofSeconds(30))
            .header("Accept", accept);

        if (token != null
            && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (background) {
            builder.header(BACKGROUND_REQUEST_HEADER, "true");
        }

        if ("POST".equals(method)) {
            builder.header("Content-Type", "application/json");
            builder.POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body, StandardCharsets.UTF_8));
        } else if ("PATCH".equals(method)) {
            builder.header("Content-Type", "application/json");
            builder.method("PATCH", HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body, StandardCharsets.UTF_8));
        } else if ("DELETE".equals(method)) {
            builder.DELETE();
        } else {
            builder.GET();
        }

        var response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        reportRenewedToken(session, response);
        return response;
    }

    private void reportRenewedToken(SessionStore.Session session, HttpResponse<byte[]> response) {
        var listener = tokenRenewalListener;
        if (session == null
            || listener == null) {
            return;
        }
        var renewed = response.headers()
            .firstValue(RENEWED_TOKEN_HEADER)
            .orElse(null);
        if (renewed == null
            || renewed.isBlank()
            || renewed.equals(session.token())) {
            return;
        }
        listener.renewed(session, renewed, response.headers()
            .firstValue(RENEWED_TOKEN_EXPIRES_AT_HEADER)
            .orElse(null));
    }

    private String responseBody(HttpResponse<byte[]> response) {
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    private IOException requestFailure(String method, String baseUrl, String path, HttpResponse<byte[]> response) {
        return new HttpStatusException(method + " " + baseUrl + path + " failed: HTTP " + response.statusCode() + " body: " + responseBody(response),
                response.statusCode());
    }
}
