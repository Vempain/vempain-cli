package fi.poltsi.vempain.cli;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import fi.poltsi.vempain.cli.core.OutputFormatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutputFormatterUTC {

    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    private final OutputFormatter formatter = new OutputFormatter(new PrintStream(outBytes, true, StandardCharsets.UTF_8));

    @Test
    void truncate_handlesNullShortAndLongValues() {
        assertEquals("", formatter.truncate(null, 10));
        assertEquals("short", formatter.truncate("short", 10));
        assertEquals("a-long-...", formatter.truncate("a-long-value", 10));
        assertEquals("no-limit", formatter.truncate("no-limit", 3), "widths below four characters leave the value as it is");
    }

    @Test
    void printFileMetadata_printsSectionsOrRawJson() {
        var metadata = new JSONObject().put("id", 42)
            .put("filename", "song.mp3")
            .put("file_type", "MUSIC")
            .put("created", "2026-05-14T12:00:00Z");

        formatter.printFileMetadata(metadata, false);
        var formatted = outBytes.toString(StandardCharsets.UTF_8);
        assertTrue(formatted.contains("  [Identity]"), formatted);
        assertTrue(formatted.contains("    filename: song.mp3"), formatted);
        assertTrue(formatted.contains("  [Timestamps]"), formatted);
        assertTrue(formatted.contains("    created: 2026-05-14T12:00:00Z"), formatted);
        assertTrue(formatted.contains("    original_datetime: "), "missing values print empty");

        outBytes.reset();
        formatter.printFileMetadata(metadata, true);
        var raw = outBytes.toString(StandardCharsets.UTF_8);
        assertTrue(raw.contains("\"filename\": \"song.mp3\""), raw);
        assertFalse(raw.contains("[Identity]"), raw);
    }
}
