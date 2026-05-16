package fi.poltsi.vempain.cli.core;

import org.json.JSONObject;

import java.io.PrintStream;

public class OutputFormatter {

    private final PrintStream out;

    public OutputFormatter(PrintStream out) {
        this.out = out;
    }

    public String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxLength || maxLength < 4) {
            return value;
        }
        return value.substring(0, maxLength - 3) + "...";
    }

    public void printFileMetadata(JSONObject metadata, boolean raw) {
        out.println("Metadata:");
        if (raw) {
            out.println(metadata.toString(2));
            return;
        }

        out.println("  [Identity]");
        out.println("    id: " + metadata.optLong("id", 0));
        out.println("    filename: " + metadata.optString("filename", ""));
        out.println("    file_type: " + metadata.optString("file_type", ""));
        out.println("    mimetype: " + metadata.optString("mimetype", ""));
        out.println("    filesize: " + metadata.optLong("filesize", 0));
        out.println("  [Timestamps]");
        out.println("    created: " + metadata.optString("created", ""));
        out.println("    modified: " + metadata.optString("modified", ""));
        out.println("    original_datetime: " + metadata.optString("original_datetime", ""));
    }
}

