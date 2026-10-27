package fi.poltsi.vempain.cli;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fi.poltsi.vempain.cli.core.SessionStore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
            .resolve("vempain-cli")
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

    @Test
    void save_loggingIntoAnotherBackendKeepsOldSession() throws Exception {
        sessionStore.save(SessionStore.BACKEND_FILE, "localhost:8080", "file-token");
        assertEquals("file-token", sessionStore.load(SessionStore.BACKEND_FILE)
            .token());
        assertNull(sessionStore.load(SessionStore.BACKEND_ADMIN));

        sessionStore.save(SessionStore.BACKEND_ADMIN, "localhost:9090", "admin-token");

        assertEquals("file-token", sessionStore.load(SessionStore.BACKEND_FILE)
            .token());
        assertEquals("admin-token", sessionStore.load(SessionStore.BACKEND_ADMIN)
            .token());
        assertEquals(SessionStore.BACKEND_ADMIN, sessionStore.getActiveBackend());
    }

    @Test
    void setActiveBackend_withoutSessionForBackend_throws() throws Exception {
        sessionStore.save(SessionStore.BACKEND_ADMIN, "localhost:9090", "admin-token");

        var ex = assertThrows(java.io.IOException.class, () -> sessionStore.setActiveBackend(SessionStore.BACKEND_FILE));

        assertTrue(ex.getMessage()
            .contains("No stored session"));
    }

    @Test
    void load_readsTheLegacySingleSessionFileAsTheFileSession() throws Exception {
        writeSessionFile("{\"base_url\":\"http://legacy.example.com/api\",\"token\":\"legacy-token\"}");

        var loaded = sessionStore.load(SessionStore.BACKEND_ADMIN);
        assertNotNull(loaded);
        assertEquals(SessionStore.BACKEND_FILE, loaded.backend(), "the legacy file only knows the file backend");
        assertEquals("http://legacy.example.com/api", loaded.baseUrl());
        assertEquals("legacy-token", loaded.token());
        assertTrue(sessionStore.hasSession(SessionStore.BACKEND_FILE));

        writeSessionFile("{\"base_url\":\"http://legacy.example.com/api\",\"token\":\"\"}");
        assertNull(sessionStore.load(), "a legacy file without token is no session");
        assertFalse(sessionStore.hasSession(SessionStore.BACKEND_FILE));
    }

    @Test
    void load_returnsNullForIncompleteBackendEntries() throws Exception {
        writeSessionFile("{\"active_backend\":\"file\"}");
        assertNull(sessionStore.load(SessionStore.BACKEND_FILE), "no backends object");

        writeSessionFile("{\"active_backend\":\"file\",\"backends\":{\"file\":{\"base_url\":\"http://h/api\",\"token\":\" \"}}}");
        assertNull(sessionStore.load(SessionStore.BACKEND_FILE), "blank token");

        writeSessionFile("{\"active_backend\":\"admin\",\"backends\":{\"file\":{\"base_url\":\"http://h/api\",\"token\":\"t\"}}}");
        assertNull(sessionStore.load(SessionStore.BACKEND_ADMIN), "backend missing");
        assertEquals(SessionStore.BACKEND_ADMIN, sessionStore.getActiveBackend());
    }

    @Test
    void save_migratesTheLegacyFileAndRepairsBrokenRootDocuments() throws Exception {
        writeSessionFile("{\"base_url\":\"http://legacy.example.com/api\",\"token\":\"legacy-token\"}");
        sessionStore.save(SessionStore.BACKEND_ADMIN, "admin.example.com", "admin-token");
        assertEquals("legacy-token", sessionStore.load(SessionStore.BACKEND_FILE)
            .token(), "the legacy session is migrated to the file backend");
        assertEquals("admin-token", sessionStore.load(SessionStore.BACKEND_ADMIN)
            .token());
        assertEquals(SessionStore.BACKEND_ADMIN, sessionStore.getActiveBackend());

        writeSessionFile("{\"base_url\":\"\",\"token\":\"\"}");
        sessionStore.save(SessionStore.BACKEND_ADMIN, "admin.example.com", "admin-token");
        assertNull(sessionStore.load(SessionStore.BACKEND_FILE), "an empty legacy session is dropped by the migration");
        assertNotNull(sessionStore.load(SessionStore.BACKEND_ADMIN));

        writeSessionFile("{\"backends\":5}");
        sessionStore.save("localhost:8080", "token-1");
        assertEquals("token-1", sessionStore.load(SessionStore.BACKEND_FILE)
            .token(), "a backends value that is no object is replaced");

        writeSessionFile("{}");
        sessionStore.save("localhost:8080", "token-2");
        assertEquals("token-2", sessionStore.load()
            .token(), "missing backends and active_backend get defaults");

        writeSessionFile("   ");
        sessionStore.save("localhost:8080", "token-3");
        assertEquals("token-3", sessionStore.load()
            .token(), "a blank file is treated as empty");
    }

    @Test
    void clear_backend_handlesMissingAndBrokenFilesAndMovesTheActiveBackend() throws Exception {
        sessionStore.clear(SessionStore.BACKEND_FILE);
        assertFalse(Files.exists(sessionFile()), "nothing to clear without a file");

        writeSessionFile("{\"backends\":\"broken\"}");
        sessionStore.clear(SessionStore.BACKEND_FILE);
        assertFalse(Files.exists(sessionFile()), "a file without a backends object is removed");

        sessionStore.save(SessionStore.BACKEND_FILE, "localhost:8080", "file-token");
        sessionStore.save(SessionStore.BACKEND_ADMIN, "localhost:9090", "admin-token");
        sessionStore.clear(SessionStore.BACKEND_ADMIN);
        assertEquals(SessionStore.BACKEND_FILE, sessionStore.getActiveBackend(), "the file backend takes over when the active admin session goes");
        assertNull(sessionStore.load(SessionStore.BACKEND_ADMIN));

        sessionStore.save(SessionStore.BACKEND_ADMIN, "localhost:9090", "admin-token");
        sessionStore.setActiveBackend(SessionStore.BACKEND_FILE);
        assertEquals(SessionStore.BACKEND_FILE, sessionStore.getActiveBackend());
        sessionStore.clear(SessionStore.BACKEND_FILE);
        assertEquals(SessionStore.BACKEND_ADMIN, sessionStore.getActiveBackend(), "the admin backend takes over when the active file session goes");

        sessionStore.clear(SessionStore.BACKEND_ADMIN);
        assertFalse(Files.exists(sessionFile()), "the file is removed with the last session");
    }

    @Test
    void setActiveBackend_switchesBetweenStoredSessions() throws Exception {
        sessionStore.save(SessionStore.BACKEND_FILE, "localhost:8080", "file-token");
        sessionStore.save(SessionStore.BACKEND_ADMIN, "localhost:9090", "admin-token");

        sessionStore.setActiveBackend("FILE");

        assertEquals(SessionStore.BACKEND_FILE, sessionStore.getActiveBackend());
        assertEquals("file-token", sessionStore.load()
            .token());
        assertTrue(sessionStore.hasSession(SessionStore.BACKEND_ADMIN));
    }

    @Test
    void normalizeBaseUrl_addsTheSchemeAndApiPathToHostsWithPaths() {
        assertEquals("http://example.com/vempain/api", sessionStore.normalizeBaseUrl("example.com/vempain/"));
        assertEquals("https://example.com/vempain/api", sessionStore.normalizeBaseUrl("https://example.com/vempain"));
    }

    @Test
    void compatibilitySubclassInTheRootPackage_isAWorkingSessionStore() throws Exception {
        var legacy = new fi.poltsi.vempain.cli.SessionStore();

        legacy.save("localhost:8080", "token-123");

        assertEquals("token-123", sessionStore.load()
            .token());
    }

    @Test
    void keepSessionsInMemory_removesTheStoredFileAndKeepsLaterSessionsOffDisk() throws Exception {
        sessionStore.save(SessionStore.BACKEND_FILE, "localhost:8080", "stored-file-token");
        sessionStore.save(SessionStore.BACKEND_ADMIN, "localhost:9090", "stored-admin-token");
        assertFalse(sessionStore.isInMemory());

        sessionStore.keepSessionsInMemory();

        assertTrue(sessionStore.isInMemory());
        assertFalse(Files.exists(sessionFile()), "the stored sessions are invalidated");
        assertNull(sessionStore.load(SessionStore.BACKEND_FILE), "the old file session is gone");
        assertNull(sessionStore.load(SessionStore.BACKEND_ADMIN), "the old admin session is gone");
        assertEquals(SessionStore.BACKEND_FILE, sessionStore.getActiveBackend());

        sessionStore.save(SessionStore.BACKEND_ADMIN, "localhost:9090", "memory-admin-token");
        sessionStore.save(SessionStore.BACKEND_FILE, "localhost:8080", "memory-file-token");

        assertFalse(Files.exists(sessionFile()), "nothing is written while in memory");
        assertEquals("memory-admin-token", sessionStore.load(SessionStore.BACKEND_ADMIN)
            .token());
        assertEquals("memory-file-token", sessionStore.load(SessionStore.BACKEND_FILE)
            .token());
        assertEquals(SessionStore.BACKEND_FILE, sessionStore.getActiveBackend());
        assertNull(new SessionStore().load(SessionStore.BACKEND_FILE), "another store sees no session");

        sessionStore.setActiveBackend(SessionStore.BACKEND_ADMIN);
        assertEquals(SessionStore.BACKEND_ADMIN, sessionStore.getActiveBackend());
        assertFalse(Files.exists(sessionFile()));

        sessionStore.clear(SessionStore.BACKEND_ADMIN);
        assertNull(sessionStore.load(SessionStore.BACKEND_ADMIN));
        assertEquals(SessionStore.BACKEND_FILE, sessionStore.getActiveBackend(), "the active backend moves to the remaining session");
        assertTrue(sessionStore.isInMemory(), "clearing one backend keeps the memory mode");

        sessionStore.clear(SessionStore.BACKEND_FILE);
        assertNull(sessionStore.load(SessionStore.BACKEND_FILE));
        assertTrue(sessionStore.isInMemory(), "clearing the last session keeps the memory mode");

        sessionStore.save(SessionStore.BACKEND_FILE, "localhost:8080", "memory-file-token-2");
        sessionStore.keepSessionsInMemory();
        assertEquals("memory-file-token-2", sessionStore.load(SessionStore.BACKEND_FILE)
            .token(), "entering the memory mode again keeps the in-memory sessions");
    }

    @Test
    void renewToken_replacesTheTokenOfTheSessionAndKeepsTheActiveBackend() throws Exception {
        sessionStore.save(SessionStore.BACKEND_FILE, "localhost:8080", "file-token");
        sessionStore.save(SessionStore.BACKEND_ADMIN, "localhost:9090", "admin-token");
        assertEquals(SessionStore.BACKEND_ADMIN, sessionStore.getActiveBackend());

        assertTrue(sessionStore.renewToken(SessionStore.BACKEND_FILE, "file-token", "file-token-2"));

        assertEquals("file-token-2", sessionStore.load(SessionStore.BACKEND_FILE)
            .token());
        assertEquals("http://localhost:8080/api", sessionStore.load(SessionStore.BACKEND_FILE)
            .baseUrl());
        assertEquals(SessionStore.BACKEND_ADMIN, sessionStore.getActiveBackend(), "a renewal never switches the active backend");
        assertEquals("file-token-2", new SessionStore().load(SessionStore.BACKEND_FILE)
            .token(), "the renewed token is on disk for the next invocation");

        // A stale renewal (the token was replaced by a newer login meanwhile), a missing session and an empty token change nothing
        assertFalse(sessionStore.renewToken(SessionStore.BACKEND_FILE, "file-token", "file-token-3"));
        assertFalse(sessionStore.renewToken("file", "file-token-2", " "));
        sessionStore.clear(SessionStore.BACKEND_ADMIN);
        assertFalse(sessionStore.renewToken(SessionStore.BACKEND_ADMIN, "admin-token", "admin-token-2"));
        assertEquals("file-token-2", sessionStore.load(SessionStore.BACKEND_FILE)
            .token());

        // In memory mode the renewal stays in memory
        sessionStore.keepSessionsInMemory();
        sessionStore.save(SessionStore.BACKEND_FILE, "localhost:8080", "memory-token");
        assertTrue(sessionStore.renewToken(SessionStore.BACKEND_FILE, "memory-token", "memory-token-2"));
        assertEquals("memory-token-2", sessionStore.load(SessionStore.BACKEND_FILE)
            .token());
        assertFalse(Files.exists(sessionFile()));
    }

    @Test
    void discardInMemorySessions_forgetsTheSessionsAndReturnsToTheEmptyDiskStore() throws Exception {
        sessionStore.save(SessionStore.BACKEND_FILE, "localhost:8080", "stored-file-token");
        sessionStore.keepSessionsInMemory();
        sessionStore.save(SessionStore.BACKEND_FILE, "localhost:8080", "memory-file-token");
        sessionStore.save(SessionStore.BACKEND_ADMIN, "localhost:9090", "memory-admin-token");

        sessionStore.discardInMemorySessions();

        assertFalse(sessionStore.isInMemory());
        assertNull(sessionStore.load(SessionStore.BACKEND_FILE));
        assertNull(sessionStore.load(SessionStore.BACKEND_ADMIN));
        assertFalse(Files.exists(sessionFile()));

        sessionStore.save(SessionStore.BACKEND_FILE, "localhost:8080", "stored-again");
        assertTrue(Files.exists(sessionFile()), "after the memory mode the store persists again");
        assertEquals("stored-again", new SessionStore().load(SessionStore.BACKEND_FILE)
            .token());
    }

    @Test
    void load_movesTheSessionFileOfAnEarlierReleaseIntoTheCliDirectory() throws Exception {
        var legacyFile = tempHome.resolve(".config")
            .resolve("vempain-file-cli")
            .resolve("session.json");
        Files.createDirectories(legacyFile.getParent());
        Files.writeString(legacyFile, "{\"active_backend\":\"admin\",\"backends\":{\"admin\":{\"base_url\":\"http://h/api\",\"token\":\"t\"}}}",
                StandardCharsets.UTF_8);

        assertEquals("t", sessionStore.load(SessionStore.BACKEND_ADMIN)
            .token());
        assertTrue(Files.exists(sessionFile()), "the file now lives in ~/.config/vempain-cli");
        assertFalse(Files.exists(legacyFile), "the old file is moved, not copied");
        assertFalse(Files.exists(legacyFile.getParent()), "the emptied old directory is removed");
    }

    private Path sessionFile() {
        return tempHome.resolve(".config")
            .resolve("vempain-cli")
            .resolve("session.json");
    }

    private void writeSessionFile(String content) throws Exception {
        Files.createDirectories(sessionFile().getParent());
        Files.writeString(sessionFile(), content, StandardCharsets.UTF_8);
    }
}
