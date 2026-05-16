package fi.poltsi.vempain.file.cli;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SessionStoreUTC {

    private final SessionStore sessionStore = new SessionStore();
    private String previousHome;

    @TempDir
    Path tempHome;

    @BeforeEach
    void setUp() {
        previousHome = System.getProperty("user.home");
        System.setProperty("user.home", tempHome.toString());
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.home", previousHome);
    }

    @Test
    void normalizeBaseUrl_addsSchemeAndApi() {
        assertEquals("http://localhost:8080/api", sessionStore.normalizeBaseUrl("localhost:8080"));
    }

    @Test
    void normalizeBaseUrl_keepsExistingApiPath() {
        assertEquals("http://localhost:8080/api", sessionStore.normalizeBaseUrl("http://localhost:8080/api"));
    }

    @Test
    void normalizeBaseUrl_handlesHttpsAndTrailingSlash() {
        assertEquals("https://demo.example.com/api", sessionStore.normalizeBaseUrl("https://demo.example.com/"));
    }

    @Test
    void normalizeBaseUrl_nullInputStillReturnsApiSuffix() {
        assertEquals("http:///api", sessionStore.normalizeBaseUrl(null));
    }

    @Test
    void load_malformedFileReturnsNull() throws Exception {
        var sessionFile = tempHome.resolve(".config")
                .resolve("vempain-file-cli")
                .resolve("session.json");
        Files.createDirectories(sessionFile.getParent());
        Files.writeString(sessionFile, "not-json", StandardCharsets.UTF_8);

        assertNull(sessionStore.load());
    }

    @Test
    void saveLoadAndClear_roundTripWorks() throws Exception {
        sessionStore.save("localhost:8080", "token-123");

        var loaded = sessionStore.load();
        assertNotNull(loaded);
        assertEquals("http://localhost:8080/api", loaded.baseUrl());
        assertEquals("token-123", loaded.token());

        sessionStore.clear();
        assertNull(sessionStore.load());
    }
}

