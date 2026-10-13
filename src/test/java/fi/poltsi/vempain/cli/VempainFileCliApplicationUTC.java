package fi.poltsi.vempain.cli;

import fi.poltsi.vempain.cli.core.HttpTransport;
import fi.poltsi.vempain.cli.core.SessionStore;
import org.jline.reader.Candidate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VempainFileCliApplicationUTC {

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
	void main_withoutArguments_printsTheUsageOnStandardOutput() {
		var stdout   = new ByteArrayOutputStream();
		var original = System.out;
		System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
		try {
			VempainFileCliApplication.main(new String[0]);
		} finally {
			System.setOut(original);
		}

		assertTrue(stdout.toString(StandardCharsets.UTF_8)
						 .contains("Usage: vempain-file-cli"), stdout.toString(StandardCharsets.UTF_8));
	}

	@Test
	void run_enablesCompletionDebuggingFromTheEnvironmentFlag() {
		for (var enabled : List.of("1", "true", "YES")) {
			var errBytes = new ByteArrayOutputStream();
			var app = new VempainFileCliApplication(new SessionStore(), new HttpTransport(), new PrintStream(new ByteArrayOutputStream()),
													new PrintStream(errBytes));

			app.run(new String[]{"session", "show"}, enabled);
			app.createCompleterForTests()
			   .complete(null, new CliIntegrationUTC.StubParsedLine(List.of("fi"), 0, "fi"), new ArrayList<Candidate>());

			assertTrue(errBytes.toString(StandardCharsets.UTF_8)
							   .contains("[completion-debug] suggestions for 'fi': [files-list, file-show]"), enabled + ": " + errBytes);
		}
		for (var disabled : new String[]{null, "0", "no", ""}) {
			var errBytes = new ByteArrayOutputStream();
			var app = new VempainFileCliApplication(new SessionStore(), new HttpTransport(), new PrintStream(new ByteArrayOutputStream()),
													new PrintStream(errBytes));

			app.run(new String[]{"session", "show"}, disabled);
			app.createShellCompleterForTests()
			   .complete(null, new CliIntegrationUTC.StubParsedLine(List.of("fi"), 0, "fi"), new ArrayList<Candidate>());

			assertFalse(errBytes.toString(StandardCharsets.UTF_8)
								.contains("[completion-debug]"), disabled + ": " + errBytes);
		}
	}
}
