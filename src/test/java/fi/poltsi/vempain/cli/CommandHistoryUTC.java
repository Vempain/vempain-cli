package fi.poltsi.vempain.cli;

import fi.poltsi.vempain.cli.core.CommandHistory;
import org.jline.reader.impl.history.DefaultHistory;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandHistoryUTC {

	@Test
	void append_createsParentAndWritesTimestampedCommand() throws Exception {
		var historyFile = Files.createTempDirectory("vempain-history")
		                       .resolve("nested/history");
		var history     = new CommandHistory(historyFile);

		history.append("files-list --page 1");

		var line = Files.readString(historyFile)
		                .stripTrailing();
		assertTrue(line.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\tfiles-list --page 1"));
	}

	@Test
	void append_preservesExistingEntries() throws Exception {
		var historyFile = Files.createTempFile("vempain-history", "");
		var history     = new CommandHistory(historyFile);

		history.append("login --backend file");
		history.append("session show");

		var lines = Files.readAllLines(historyFile);
		assertEquals(2, lines.size());
		assertTrue(lines.get(0)
		                .endsWith("\tlogin --backend file"));
		assertTrue(lines.get(1)
		                .endsWith("\tsession show"));
	}

	@Test
	void load_addsCommandsFromPreviousSessionsToJlineHistory() throws Exception {
		var historyFile = Files.createTempFile("vempain-history", "");
		Files.writeString(historyFile,
						  "2026-10-11T23:00:00\tlogin --backend file\n"
						  + "2026-10-11T23:01:00\tsession show\n");
		var history      = new CommandHistory(historyFile);
		var jlineHistory = new DefaultHistory();

		history.load(jlineHistory);

		assertEquals(2, jlineHistory.size());
		assertEquals("login --backend file", jlineHistory.get(0));
		assertEquals("session show", jlineHistory.get(1));
	}
}
