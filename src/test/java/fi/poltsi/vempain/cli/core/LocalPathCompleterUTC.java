package fi.poltsi.vempain.cli.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.jline.reader.Candidate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The completer lists the local filesystem below the typed directory part and keeps that part as it was typed.
 */
class LocalPathCompleterUTC {

    @TempDir
    Path work;
    @TempDir
    Path home;

    private LocalPathCompleter completer;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(work.resolve("inbox"));
        Files.writeString(work.resolve("ids.txt"), "1\n");
        Files.writeString(work.resolve("ids-old.txt"), "2\n");
        Files.writeString(work.resolve("notes.md"), "");
        Files.writeString(work.resolve(".hidden"), "");
        Files.writeString(work.resolve("inbox/batch one.txt"), "3\n");
        Files.createDirectories(home.resolve("Documents"));
        Files.writeString(home.resolve("Documents/published.txt"), "4\n");
        completer = new LocalPathCompleter(work, home);
    }

    @Test
    void completesTheLastElementRelativeToTheWorkingDirectory() {
        assertEquals(List.of("ids-old.txt", "ids.txt"), values("id"));
        assertEquals(List.of("ids-old.txt", "ids.txt", "inbox/", "notes.md"), values(""), "hidden entries are left out");
        assertEquals(List.of(".hidden"), values("."), "a typed dot shows the hidden entries");
        assertEquals(List.of(), values("zzz"));
        assertEquals(values(""), values(null), "a missing word completes like an empty one");
    }

    @Test
    void directoriesStayOpenAndFilesAreComplete() {
        var candidates = candidates("i");

        var inbox = candidates.stream()
            .filter(candidate -> "inbox/".equals(candidate.value()))
            .findFirst()
            .orElseThrow();
        assertFalse(inbox.complete(), "a directory is open for further completion");
        assertEquals("inbox/", inbox.displ());
        var ids = candidates.stream()
            .filter(candidate -> "ids.txt".equals(candidate.value()))
            .findFirst()
            .orElseThrow();
        assertTrue(ids.complete(), "a file ends the word");
        assertEquals("ids.txt", ids.displ());
    }

    @Test
    void keepsTheTypedDirectoryPartAndShowsOnlyTheName() {
        assertEquals(List.of("inbox/batch one.txt"), values("inbox/"));
        assertEquals(List.of("inbox/batch one.txt"), values("inbox/b"));
        assertEquals("batch one.txt", candidates("inbox/").getFirst()
            .displ());
        assertEquals(List.of(work + "/ids-old.txt", work + "/ids.txt"), values(work + "/ids"), "absolute paths are completed in place");
        assertEquals(List.of("inbox/"), values("inb"), "a directory is completed before its content");
    }

    @Test
    void expandsTheHomeDirectory() {
        assertEquals(List.of("~/"), values("~"));
        assertFalse(candidates("~").getFirst()
            .complete());
        assertEquals(List.of("~/Documents/"), values("~/"));
        assertEquals(List.of("~/Documents/published.txt"), values("~/Documents/pub"));
    }

    @Test
    void offersNothingForAMissingOrUnreadableDirectory() throws Exception {
        assertEquals(List.of(), values("missing/"));
        assertEquals(List.of(), values("ids.txt/"), "a file is not a directory to list");

        var locked = work.resolve("locked");
        Files.createDirectories(locked);
        Files.writeString(locked.resolve("secret.txt"), "");
        var readable = locked.toFile()
            .setReadable(false, false);
        try {
            if (readable
                && !Files.isReadable(locked)) {
                assertEquals(List.of(), values("locked/"), "an unreadable directory has no completions");
            }
        } finally {
            locked.toFile()
                .setReadable(true, false);
        }
    }

    private List<String> values(String word) {
        return candidates(word).stream()
            .map(Candidate::value)
            .toList();
    }

    private List<Candidate> candidates(String word) {
        var candidates = new ArrayList<Candidate>();
        completer.complete(word, candidates);
        return candidates;
    }
}
