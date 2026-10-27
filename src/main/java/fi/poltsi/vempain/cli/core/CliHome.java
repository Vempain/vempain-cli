package fi.poltsi.vempain.cli.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * The one directory the CLI keeps its state in: {@code ~/.config/vempain-cli} with {@code session.json} and {@code history}.
 * <p>
 * Earlier releases kept the sessions in {@code ~/.config/vempain-file-cli/session.json} and the shell history in {@code ~/.vempain/history}. A file still found
 * at its old place is moved to the new directory the first time the CLI needs it, so installed users keep their data without any manual step; afterwards the
 * old locations are not consulted again.
 */
public final class CliHome {

    static final String DIRECTORY_NAME = "vempain-cli";
    static final String SESSION_FILE_NAME = "session.json";
    static final String HISTORY_FILE_NAME = "history";

    private CliHome() {}

    /**
     * @return {@code ~/.config/vempain-cli} (not created)
     */
    public static Path directory() {
        return home().resolve(".config")
            .resolve(DIRECTORY_NAME);
    }

    /**
     * @return the session file, after moving a session file of an earlier release into place when none exists yet
     */
    public static Path sessionFile() {
        return migrated(directory().resolve(SESSION_FILE_NAME), home().resolve(".config")
            .resolve("vempain-file-cli")
            .resolve(SESSION_FILE_NAME));
    }

    /**
     * @return the shell history file, after moving a history file of an earlier release into place when none exists yet
     */
    public static Path historyFile() {
        return migrated(directory().resolve(HISTORY_FILE_NAME), home().resolve(".vempain")
            .resolve(HISTORY_FILE_NAME));
    }

    /**
     * Moves {@code legacy} to {@code target} when the target does not exist yet and the legacy file does. The emptied legacy directory is removed as well. A
     * failing move leaves both files where they are: the caller then simply starts with a fresh file.
     *
     * @return {@code target}
     */
    static Path migrated(Path target, Path legacy) {
        if (Files.exists(target)
            || !Files.isRegularFile(legacy)) {
            return target;
        }
        try {
            Files.createDirectories(target.getParent());
            Files.move(legacy, target, StandardCopyOption.ATOMIC_MOVE);
            removeIfEmpty(legacy.getParent());
        } catch (IOException ignored) {
            // The old file stays in place and the new location starts empty.
        }
        return target;
    }

    private static void removeIfEmpty(Path directory) {
        if (directory == null) {
            return;
        }
        try (var entries = Files.list(directory)) {
            if (entries.findAny()
                .isEmpty()) {
                Files.delete(directory);
            }
        } catch (IOException ignored) {
            // Leaving an empty old directory behind is harmless.
        }
    }

    private static Path home() {
        return Path.of(System.getProperty("user.home"));
    }
}
