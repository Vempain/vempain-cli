package fi.poltsi.vempain.cli;

import java.io.PrintStream;

import org.jline.reader.Completer;
import org.jline.reader.LineReader;

import fi.poltsi.vempain.cli.core.HttpTransport;
import fi.poltsi.vempain.cli.core.PasswordReader;
import fi.poltsi.vempain.cli.core.SessionStore;

public class VempainFileCliApplication {

    private final CommandRouter commandRouter;
    private boolean debugCompletion;

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
        run(args, System.getenv("VEMPAIN_CLI_DEBUG_COMPLETION"));
    }

    /**
     * @param debugCompletionEnv value of {@code VEMPAIN_CLI_DEBUG_COMPLETION}; {@code 1}, {@code true} or {@code yes} enable completion debugging
     */
    void run(String[] args, String debugCompletionEnv) {
        if (isDebugCompletionEnabled(debugCompletionEnv)) {
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

    private static boolean isDebugCompletionEnabled(String env) {
        if (env == null) {
            return false;
        }
        return "1".equals(env)
               || "true".equalsIgnoreCase(env)
               || "yes".equalsIgnoreCase(env);
    }
}
