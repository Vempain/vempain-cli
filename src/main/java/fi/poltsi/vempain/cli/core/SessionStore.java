package fi.poltsi.vempain.cli.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.json.JSONObject;

/**
 * Keeps one JWT session per backend ({@code file}, {@code admin}) and the active backend.
 * <p>
 * By default the sessions are persisted in {@code ~/.config/vempain-cli/session.json} ({@link CliHome}), so one-shot commands reuse the login of an earlier
 * invocation. After {@link #keepSessionsInMemory()} the stored file is removed and every session lives only in this instance until
 * {@link #discardInMemorySessions()}; the interactive shell and TUI switch to this mode when the user logs in inside them, so such a login is neither written
 * to disk nor available after the shell exits.
 */
public class SessionStore {

    public static final String BACKEND_FILE = "file";
    public static final String BACKEND_ADMIN = "admin";

    /** Non-null while the sessions are kept in memory only. */
    private JSONObject memoryRoot;

    private Path sessionFile() {
        return CliHome.sessionFile();
    }

    /**
     * Removes the sessions stored on disk and keeps every session saved from now on in memory only, until {@link #discardInMemorySessions()}. Calling it again
     * while already in memory mode keeps the in-memory sessions and only makes sure nothing is left on disk.
     */
    public synchronized void keepSessionsInMemory() throws IOException {
        Files.deleteIfExists(sessionFile());
        if (memoryRoot == null) {
            memoryRoot = emptyRoot();
        }
    }

    /**
     * Forgets the in-memory sessions and returns to the on-disk store (which {@link #keepSessionsInMemory()} emptied).
     */
    public synchronized void discardInMemorySessions() {
        memoryRoot = null;
    }

    public synchronized boolean isInMemory() {
        return memoryRoot != null;
    }

    public Session load() {
        return load(getActiveBackend());
    }

    public synchronized Session load(String backend) {
        try {
            var legacy = legacySession();
            if (legacy != null) {
                return legacy;
            }
            var root = readRootJson();
            var selectedBackend = normalizeBackend(backend);
            var backends = root.optJSONObject("backends");
            if (backends == null) {
                return null;
            }
            var backendJson = backends.optJSONObject(selectedBackend);
            if (backendJson == null) {
                return null;
            }
            var baseUrl = backendJson.optString("base_url", null);
            var token = backendJson.optString("token", null);
            if (baseUrl == null
                || baseUrl.isBlank()
                || token == null
                || token.isBlank()) {
                return null;
            }
            return new Session(selectedBackend, baseUrl, token);
        } catch (Exception ignored) {
            return null;
        }
    }

    public void save(String baseUrl, String token) throws IOException {
        save(BACKEND_FILE, baseUrl, token);
    }

    public synchronized void save(String backend, String baseUrl, String token) throws IOException {
        var root = readRootJson();
        var normalizedBackend = normalizeBackend(backend);
        var backends = root.optJSONObject("backends");
        if (backends == null) {
            backends = new JSONObject();
            root.put("backends", backends);
        }
        backends.put(normalizedBackend, new JSONObject().put("base_url", normalizeBaseUrl(baseUrl))
            .put("token", token));
        root.put("active_backend", normalizedBackend);
        writeRootJson(root);
    }

    /**
     * Replaces the token of a stored session with a renewed one, keeping the active backend as it is. Nothing happens when the backend has no session or when
     * its token is not {@code currentToken} any more (a newer login replaced it meanwhile).
     *
     * @return whether the token was replaced
     */
    public synchronized boolean renewToken(String backend, String currentToken, String renewedToken) throws IOException {
        var normalizedBackend = normalizeBackend(backend);
        var session = load(normalizedBackend);
        if (session == null
            || !session.token()
                .equals(currentToken)
            || renewedToken == null
            || renewedToken.isBlank()) {
            return false;
        }
        var root = readRootJson();
        root.getJSONObject("backends")
            .put(normalizedBackend, new JSONObject().put("base_url", session.baseUrl())
                .put("token", renewedToken));
        writeRootJson(root);
        return true;
    }

    public synchronized void clear() throws IOException {
        if (memoryRoot != null) {
            memoryRoot = emptyRoot();
            return;
        }
        Files.deleteIfExists(sessionFile());
    }

    public synchronized void clear(String backend) throws IOException {
        if (memoryRoot == null
            && !Files.exists(sessionFile())) {
            return;
        }
        var root = readRootJson();
        var backends = root.optJSONObject("backends");
        if (backends == null) {
            clear();
            return;
        }

        backends.remove(normalizeBackend(backend));
        if (backends.isEmpty()) {
            clear();
            return;
        }

        var active = normalizeBackend(root.optString("active_backend", BACKEND_FILE));
        if (!backends.has(active)) {
            if (backends.has(BACKEND_FILE)) {
                root.put("active_backend", BACKEND_FILE);
            } else if (backends.has(BACKEND_ADMIN)) {
                root.put("active_backend", BACKEND_ADMIN);
            }
        }
        writeRootJson(root);
    }

    public synchronized String getActiveBackend() {
        try {
            var root = readRootJson();
            return normalizeBackend(root.optString("active_backend", BACKEND_FILE));
        } catch (Exception ignored) {
            return BACKEND_FILE;
        }
    }

    public synchronized void setActiveBackend(String backend) throws IOException {
        var normalizedBackend = normalizeBackend(backend);
        var session = load(normalizedBackend);
        if (session == null) {
            throw new IOException("No stored session for backend: " + normalizedBackend);
        }
        var root = readRootJson();
        root.put("active_backend", normalizedBackend);
        writeRootJson(root);
    }

    public boolean hasSession(String backend) {
        return load(backend) != null;
    }

    public String normalizeBaseUrl(String baseUrl) {
        var trimmed = baseUrl == null ? "" : baseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.endsWith("/api")) {
            return trimmed;
        }
        if (!trimmed.contains("/")) {
            return "http://" + trimmed + "/api";
        }
        if (!trimmed.matches("^https?://.*$")) {
            trimmed = "http://" + trimmed;
        }
        return trimmed.endsWith("/api") ? trimmed : trimmed + "/api";
    }

    /**
     * The old single-session file knew only the file backend; it is answered for both backends until the next save migrates it.
     */
    private Session legacySession() throws IOException {
        if (memoryRoot != null
            || !Files.exists(sessionFile())) {
            return null;
        }
        var json = new JSONObject(Files.readString(sessionFile(), StandardCharsets.UTF_8));
        if (!json.has("base_url")
            && !json.has("token")) {
            return null;
        }
        var baseUrl = json.optString("base_url", null);
        var token = json.optString("token", null);
        if (baseUrl == null
            || baseUrl.isBlank()
            || token == null
            || token.isBlank()) {
            return null;
        }
        return new Session(BACKEND_FILE, baseUrl, token);
    }

    private static JSONObject emptyRoot() {
        return new JSONObject().put("active_backend", BACKEND_FILE)
            .put("backends", new JSONObject());
    }

    private void writeRootJson(JSONObject root) throws IOException {
        if (memoryRoot != null) {
            memoryRoot = root;
            return;
        }
        var file = sessionFile();
        Files.createDirectories(file.getParent());
        Files.writeString(file, root.toString(2), StandardCharsets.UTF_8);
    }

    private JSONObject readRootJson() throws IOException {
        if (memoryRoot != null) {
            return memoryRoot;
        }
        var file = sessionFile();
        if (!Files.exists(file)) {
            return emptyRoot();
        }

        var raw = Files.readString(file, StandardCharsets.UTF_8);
        if (raw.isBlank()) {
            return emptyRoot();
        }

        var json = new JSONObject(raw);
        // Backward-compatible read of the old single-session schema: it is the file backend's session.
        if (json.has("base_url")
            || json.has("token")) {
            var migrated = emptyRoot();
            var baseUrl = json.optString("base_url", "");
            var token = json.optString("token", "");
            if (!baseUrl.isBlank()
                && !token.isBlank()) {
                migrated.getJSONObject("backends")
                    .put(BACKEND_FILE, new JSONObject().put("base_url", baseUrl)
                        .put("token", token));
            }
            return migrated;
        }

        if (!json.has("backends")) {
            json.put("backends", new JSONObject());
        }
        if (!json.has("active_backend")) {
            json.put("active_backend", BACKEND_FILE);
        }
        return json;
    }

    private String normalizeBackend(String backend) {
        if (BACKEND_ADMIN.equalsIgnoreCase(backend)) {
            return BACKEND_ADMIN;
        }
        return BACKEND_FILE;
    }

    public record Session(String backend, String baseUrl, String token) {

        public Session(String baseUrl, String token) {
            this(BACKEND_FILE, baseUrl, token);
        }
    }
}
