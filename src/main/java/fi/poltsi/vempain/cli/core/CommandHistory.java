package fi.poltsi.vempain.cli.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.jline.reader.History;

public class CommandHistory {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final Path historyFile;

    /**
     * The history of {@code ~/.config/vempain-cli/history} ({@link CliHome}).
     */
    public CommandHistory() {
        this(CliHome.historyFile());
    }

    public CommandHistory(Path historyFile) {
        this.historyFile = historyFile;
    }

    public void append(String command) throws IOException {
        ensureFile();
        var entry = FORMATTER.format(LocalDateTime.now()) + "\t" + command + System.lineSeparator();
        Files.writeString(historyFile, entry, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    public void load(History history) throws IOException {
        commands().forEach(history::add);
    }

    /**
     * @return the recorded commands, oldest first
     */
    public List<String> commands() throws IOException {
        ensureFile();
        try (var lines = Files.lines(historyFile, StandardCharsets.UTF_8)) {
            return lines.map(this::commandFromEntry)
                .filter(command -> !command.isBlank())
                .toList();
        }
    }

    private void ensureFile() throws IOException {
        var parent = historyFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (!Files.exists(historyFile)) {
            Files.createFile(historyFile);
        }
    }

    private String commandFromEntry(String entry) {
        var separator = entry.indexOf('\t');
        return separator < 0 ? "" : entry.substring(separator + 1);
    }
}
