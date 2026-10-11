package fi.poltsi.vempain.cli.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class CommandHistory {

	private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

	private final Path historyFile;

	public CommandHistory() {
		this(Path.of(System.getProperty("user.home"), ".vempain", "history"));
	}

	public CommandHistory(Path historyFile) {
		this.historyFile = historyFile;
	}

	public void append(String command) throws IOException {
		var parent = historyFile.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		var entry = FORMATTER.format(LocalDateTime.now()) + "\t" + command + System.lineSeparator();
		Files.writeString(historyFile, entry, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
	}
}
