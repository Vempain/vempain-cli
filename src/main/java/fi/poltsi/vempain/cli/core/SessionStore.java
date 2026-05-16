package fi.poltsi.vempain.cli.core;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class SessionStore {

    public static final String BACKEND_FILE = "file";
    public static final String BACKEND_ADMIN = "admin";

    private Path sessionFile() {
        return Path.of(System.getProperty("user.home"), ".config", "vempain-file-cli", "session.json");
    }

    public Session load() {
        return load(getActiveBackend());
    }

    public Session load(String backend) {
        var file = sessionFile();
        if (!Files.exists(file)) {
            return null;
        }
        try {
            var json = new JSONObject(Files.readString(file, StandardCharsets.UTF_8));

            // Backward-compatible read for old single-session schema.
            if (json.has("base_url") || json.has("token")) {
                var baseUrl = json.optString("base_url", null);
                var token = json.optString("token", null);
                if (baseUrl == null || baseUrl.isBlank() || token == null || token.isBlank()) {
                    return null;
                }
                return new Session(BACKEND_FILE, baseUrl, token);
            }

            var selectedBackend = normalizeBackend(backend);
            var backends = json.optJSONObject("backends");
            if (backends == null) {
                return null;
            }
            var backendJson = backends.optJSONObject(selectedBackend);
            if (backendJson == null) {
                return null;
            }
            var baseUrl = backendJson.optString("base_url", null);
            var token = backendJson.optString("token", null);
            if (baseUrl == null || baseUrl.isBlank() || token == null || token.isBlank()) {
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

    public void save(String backend, String baseUrl, String token) throws IOException {
        var file = sessionFile();
        Files.createDirectories(file.getParent());

        var root = readRootJson();
        var normalizedBackend = normalizeBackend(backend);
        // Only one backend session is kept at a time.
        var backends = new JSONObject();
        root.put("backends", backends);
        backends.put(normalizedBackend, new JSONObject()
                .put("base_url", normalizeBaseUrl(baseUrl))
                .put("token", token));
        root.put("active_backend", normalizedBackend);

        Files.writeString(file, root.toString(2), StandardCharsets.UTF_8);
    }

    public void clear() throws IOException {
        Files.deleteIfExists(sessionFile());
    }

    public void clear(String backend) throws IOException {
        var file = sessionFile();
        if (!Files.exists(file)) {
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

        Files.writeString(file, root.toString(2), StandardCharsets.UTF_8);
    }

    public String getActiveBackend() {
        try {
            var root = readRootJson();
            return normalizeBackend(root.optString("active_backend", BACKEND_FILE));
        } catch (Exception ignored) {
            return BACKEND_FILE;
        }
    }

    public void setActiveBackend(String backend) throws IOException {
        var normalizedBackend = normalizeBackend(backend);
        var session = load(normalizedBackend);
        if (session == null) {
            throw new IOException("No stored session for backend: " + normalizedBackend);
        }
        var root = readRootJson();
        root.put("active_backend", normalizedBackend);
        Files.createDirectories(sessionFile().getParent());
        Files.writeString(sessionFile(), root.toString(2), StandardCharsets.UTF_8);
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

    private JSONObject readRootJson() throws IOException {
        var file = sessionFile();
        if (!Files.exists(file)) {
            return new JSONObject()
                    .put("active_backend", BACKEND_FILE)
                    .put("backends", new JSONObject());
        }

        var raw = Files.readString(file, StandardCharsets.UTF_8);
        if (raw.isBlank()) {
            return new JSONObject()
                    .put("active_backend", BACKEND_FILE)
                    .put("backends", new JSONObject());
        }

        var json = new JSONObject(raw);
        if (json.has("base_url") || json.has("token")) {
            var migrated = new JSONObject()
                    .put("active_backend", BACKEND_FILE)
                    .put("backends", new JSONObject());
            var baseUrl = json.optString("base_url", "");
            var token = json.optString("token", "");
            if (!baseUrl.isBlank() && !token.isBlank()) {
                migrated.getJSONObject("backends")
                        .put(BACKEND_FILE, new JSONObject().put("base_url", baseUrl).put("token", token));
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

