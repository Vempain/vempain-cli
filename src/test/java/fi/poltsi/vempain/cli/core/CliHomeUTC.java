package fi.poltsi.vempain.cli.core;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliHomeUTC {

    @TempDir
    Path tempHome;
    private String previousHome;

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
    void directory_isTheCliConfigDirectoryOfTheUser() {
        assertEquals(tempHome.resolve(".config")
            .resolve("vempain-cli"), CliHome.directory());
        assertFalse(Files.exists(CliHome.directory()), "the directory is not created by resolving it");
    }

    @Test
    void files_areResolvedWithoutLegacyFilesPresent() {
        assertEquals(CliHome.directory()
            .resolve("session.json"), CliHome.sessionFile());
        assertEquals(CliHome.directory()
            .resolve("history"), CliHome.historyFile());
        assertFalse(Files.exists(CliHome.directory()));
    }

    @Test
    void migrated_movesTheLegacyFileAndRemovesItsEmptyDirectory() throws Exception {
        var legacy = tempHome.resolve("old")
            .resolve("file");
        Files.createDirectories(legacy.getParent());
        Files.writeString(legacy, "content");
        var target = tempHome.resolve("new")
            .resolve("file");

        assertEquals(target, CliHome.migrated(target, legacy));

        assertEquals("content", Files.readString(target));
        assertFalse(Files.exists(legacy));
        assertFalse(Files.exists(legacy.getParent()));
    }

    @Test
    void migrated_keepsTheLegacyDirectoryWhenItStillHasOtherFiles() throws Exception {
        var legacy = tempHome.resolve("old")
            .resolve("file");
        Files.createDirectories(legacy.getParent());
        Files.writeString(legacy, "content");
        Files.writeString(legacy.resolveSibling("other"), "keep");
        var target = tempHome.resolve("new")
            .resolve("file");

        CliHome.migrated(target, legacy);

        assertTrue(Files.exists(target));
        assertTrue(Files.exists(legacy.resolveSibling("other")));
        assertTrue(Files.isDirectory(legacy.getParent()));
    }

    @Test
    void migrated_neverOverwritesAnExistingTargetAndIgnoresAMissingOrUnmovableLegacyFile() throws Exception {
        var legacy = tempHome.resolve("old")
            .resolve("file");
        Files.createDirectories(legacy.getParent());
        Files.writeString(legacy, "old");
        var target = tempHome.resolve("new")
            .resolve("file");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "new");

        CliHome.migrated(target, legacy);
        assertEquals("new", Files.readString(target), "an existing target wins");
        assertTrue(Files.exists(legacy), "the old file is left alone");

        Files.delete(target);
        CliHome.migrated(target, tempHome.resolve("missing"));
        assertFalse(Files.exists(target), "nothing to migrate");

        // A directory in place of the target's parent path makes the move fail: both sides are left untouched.
        var blocked = tempHome.resolve("blocked");
        Files.writeString(blocked, "a file, not a directory");
        CliHome.migrated(blocked.resolve("file"), legacy);
        assertTrue(Files.exists(legacy), "a failed move leaves the old file in place");
    }
}
