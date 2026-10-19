package fi.poltsi.vempain.cli.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import org.jline.reader.Candidate;

/**
 * Completes paths of the local filesystem for the command arguments that name a local file, such as {@code publish-files --ids-file}. A candidate replaces the
 * typed word as a whole, so the directory part is kept exactly as it was typed (relative to the working directory, absolute, or below {@code ~}) and only the
 * last path element is completed. Directories end in {@code /} and stay open for further completion; files are complete words. Hidden entries are offered only
 * when the typed name already starts with a dot.
 */
public final class LocalPathCompleter {

    private final Path workingDirectory;
    private final Path homeDirectory;

    public LocalPathCompleter() {
        this(Path.of("")
            .toAbsolutePath(), Path.of(System.getProperty("user.home")));
    }

    /**
     * @param workingDirectory directory relative paths are resolved against
     * @param homeDirectory    directory {@code ~} stands for
     */
    LocalPathCompleter(Path workingDirectory, Path homeDirectory) {
        this.workingDirectory = workingDirectory;
        this.homeDirectory = homeDirectory;
    }

    /**
     * Adds the entries matching {@code word} to {@code candidates}; nothing is added when the directory part does not exist or cannot be read.
     */
    public void complete(String word, List<Candidate> candidates) {
        var typed = word == null ? "" : word;

        if ("~".equals(typed)) {
            candidates.add(directoryCandidate("~/", "~/"));
            return;
        }

        var slash = typed.lastIndexOf('/');
        var typedDirectory = slash < 0 ? "" : typed.substring(0, slash + 1);
        var namePrefix = typed.substring(slash + 1);
        var directory = resolve(typedDirectory);

        if (!Files.isDirectory(directory)) {
            return;
        }

        try (var entries = Files.list(directory)) {
            entries.filter(entry -> matches(entry.getFileName()
                .toString(), namePrefix))
                .sorted(Comparator.comparing(entry -> entry.getFileName()
                    .toString()))
                .forEach(entry -> candidates.add(toCandidate(typedDirectory, entry)));
        } catch (IOException | SecurityException e) {
            // An unreadable directory simply has no completions
        }
    }

    private Path resolve(String typedDirectory) {
        if (typedDirectory.isEmpty()) {
            return workingDirectory;
        }

        if (typedDirectory.startsWith("~/")) {
            return homeDirectory.resolve(typedDirectory.substring(2));
        }

        return workingDirectory.resolve(typedDirectory);
    }

    private static boolean matches(String name, String namePrefix) {
        if (!name.startsWith(namePrefix)) {
            return false;
        }

        return !name.startsWith(".")
               || namePrefix.startsWith(".");
    }

    private static Candidate toCandidate(String typedDirectory, Path entry) {
        var name = entry.getFileName()
            .toString();

        if (Files.isDirectory(entry)) {
            return directoryCandidate(typedDirectory + name + "/", name + "/");
        }

        return new Candidate(typedDirectory + name, name, null, null, null, null, true);
    }

    private static Candidate directoryCandidate(String value, String display) {
        return new Candidate(value, display, null, null, null, null, false);
    }
}
