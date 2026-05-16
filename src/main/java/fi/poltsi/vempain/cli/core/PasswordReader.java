package fi.poltsi.vempain.cli.core;

/**
 * Abstraction for prompting the user for a password without echoing it.
 * <p>
 * The returned {@code char[]} is owned by the caller; it should be zeroed with
 * {@code Arrays.fill(password, '\0')} as soon as it is no longer needed.
 */
@FunctionalInterface
public interface PasswordReader {

    char[] readPassword(String prompt);

    /**
     * Default implementation that reads from {@link System#console()} when
     * available (input is not echoed) and falls back to {@code System.in}
     * when running without a tty (e.g. piped or redirected input).
     */
    static PasswordReader consoleReader() {
        return prompt -> {
            var console = System.console();
            if (console != null) {
                return console.readPassword("%s", prompt);
            }
            // Fallback for non-interactive environments (e.g. piped input).
            System.out.print(prompt);
            System.out.flush();
            var sb = new StringBuilder();
            try {
                int ch;
                while ((ch = System.in.read()) != -1 && ch != '\n' && ch != '\r') {
                    sb.append((char) ch);
                }
            } catch (java.io.IOException ignored) {
            }
            var result = new char[sb.length()];
            sb.getChars(0, sb.length(), result, 0);
            // Zero the StringBuilder's internal buffer as best we can.
            for (int i = 0; i < sb.length(); i++) {
                sb.setCharAt(i, '\0');
            }
            return result;
        };
    }
}

