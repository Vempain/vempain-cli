package fi.poltsi.vempain.file.cli;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class SessionStore {

    private Path sessionFile() {
        return Path.of(System.getProperty("user.home"), ".config", "vempain-file-cli", "session.json");
    }

    public Session load() {
        var sessionFile = sessionFile();
        if (!Files.exists(sessionFile)) {
            return null;
        }
        try {
            var json = new JSONObject(Files.readString(sessionFile, StandardCharsets.UTF_8));
            var baseUrl = json.optString("base_url", null);
            var token = json.optString("token", null);
            if (baseUrl == null || baseUrl.isBlank() || token == null || token.isBlank()) {
                return null;
            }
            return new Session(baseUrl, token);
        } catch (Exception ignored) {
            return null;
        }
    }

    public void save(String baseUrl, String token) throws IOException {
        var sessionFile = sessionFile();
        Files.createDirectories(sessionFile.getParent());
        var json = new JSONObject();
        json.put("base_url", normalizeBaseUrl(baseUrl));
        json.put("token", token);
        Files.writeString(sessionFile, json.toString(2), StandardCharsets.UTF_8);
    }

    public void clear() throws IOException {
        Files.deleteIfExists(sessionFile());
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

    public record Session(String baseUrl, String token) {
    }
}

