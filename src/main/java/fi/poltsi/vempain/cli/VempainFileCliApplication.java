package fi.poltsi.vempain.cli;

import fi.poltsi.vempain.cli.core.HttpTransport;
import fi.poltsi.vempain.cli.core.PasswordReader;
import fi.poltsi.vempain.cli.core.SessionStore;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;

import java.io.PrintStream;

public class VempainFileCliApplication {

	private final CommandRouter commandRouter;
	private       boolean       debugCompletion;

	public VempainFileCliApplication() {
		this(new SessionStore(), new HttpTransport(), System.out, System.err, PasswordReader.consoleReader());
	}

	VempainFileCliApplication(SessionStore sessionStore, HttpTransport transport, PrintStream out, PrintStream err) {
		this(sessionStore, transport, out, err, PasswordReader.consoleReader());
	}

	VempainFileCliApplication(SessionStore sessionStore, HttpTransport transport, PrintStream out, PrintStream err, PasswordReader passwordReader) {
		this.commandRouter = new CommandRouter(sessionStore, transport, out, err, passwordReader);
	}

	static void main(String[] args) {
		new VempainFileCliApplication().run(args);
	}

	void run(String[] args) {
		if (isEnvDebugCompletionEnabled()) {
			debugCompletion = true;
		}
		commandRouter.run(args, debugCompletion);
	}

	void runShell(LineReader reader) {
		commandRouter.runShell(reader, debugCompletion);
	}

	Completer createCompleterForTests() {
		return commandRouter.createCompleter(false, debugCompletion);
	}

	Completer createShellCompleterForTests() {
		return commandRouter.createCompleter(true, debugCompletion);
	}

	private boolean isEnvDebugCompletionEnabled() {
		var env = System.getenv("VEMPAIN_CLI_DEBUG_COMPLETION");
		if (env == null) {
			return false;
		}
		return "1".equals(env) || "true".equalsIgnoreCase(env) || "yes".equalsIgnoreCase(env);
	}
}


