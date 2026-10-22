package fi.poltsi.vempain.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.ParsedLine;
import org.jline.reader.UserInterruptException;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.beust.jcommander.Parameters;
import com.beust.jcommander.internal.DefaultConsole;
import com.googlecode.lanterna.terminal.Terminal;

import fi.poltsi.vempain.cli.admin.AdminCommands;
import fi.poltsi.vempain.cli.core.BackendVersionValidator;
import fi.poltsi.vempain.cli.core.CommandHistory;
import fi.poltsi.vempain.cli.core.HttpTransport;
import fi.poltsi.vempain.cli.core.LocalPathCompleter;
import fi.poltsi.vempain.cli.core.PasswordReader;
import fi.poltsi.vempain.cli.core.SessionStore;
import fi.poltsi.vempain.cli.core.TaskClient;
import fi.poltsi.vempain.cli.core.TaskMonitor;
import fi.poltsi.vempain.cli.file.FileCommands;
import fi.poltsi.vempain.cli.task.TaskCommands;
import fi.poltsi.vempain.cli.tui.TuiShell;

public class CommandRouter {

    private static final List<String> BASE_COMMANDS = List.of("login", "files-list", "file-show", "publish-music", "publish-gps", "publish-files",
            "publish-status", "scan", "tag-rename", "tag-replace", "metadata-update", "admin", "tasks", "task-monitor", "task-cancel", "session", "logout",
            "shell", "exit", "quit");

    private static final Map<String, List<String>> COMMAND_OPTIONS = commandOptions();

    /**
     * The options whose value names a file of the local filesystem; their values are completed from the filesystem in both shells.
     */
    private static final Map<String, List<String>> LOCAL_PATH_OPTIONS = Map.of("publish-files", List.of("--ids-file"));

    private static final Duration TASK_POLL_INTERVAL = Duration.ofSeconds(2);

    private final SessionStore sessionStore;
    private final HttpTransport transport;
    private final PrintStream out;
    private final PrintStream err;
    private final FileCommands fileCommands;
    private final AdminCommands adminCommands;
    private final TaskCommands taskCommands;
    private final PasswordReader passwordReader;
    private final CommandHistory commandHistory;
    private final LocalPathCompleter localPathCompleter = new LocalPathCompleter();

    private boolean shellMode;

    public CommandRouter(SessionStore sessionStore, HttpTransport transport, PrintStream out, PrintStream err) {
        this(sessionStore, transport, out, err, PasswordReader.consoleReader());
    }

    public CommandRouter(SessionStore sessionStore, HttpTransport transport, PrintStream out, PrintStream err, PasswordReader passwordReader) {
        this(sessionStore, transport, out, err, passwordReader, new CommandHistory());
    }

    CommandRouter(SessionStore sessionStore, HttpTransport transport, PrintStream out, PrintStream err, PasswordReader passwordReader,
            CommandHistory commandHistory) {
        this(sessionStore, transport, out, err, passwordReader, commandHistory, taskClient -> new TaskMonitor(taskClient, out));
    }

    CommandRouter(SessionStore sessionStore, HttpTransport transport, PrintStream out, PrintStream err, PasswordReader passwordReader,
            CommandHistory commandHistory, Function<TaskClient, TaskMonitor> taskMonitorFactory) {
        this.sessionStore = sessionStore;
        this.transport = transport;
        this.out = out;
        this.err = err;
        this.passwordReader = passwordReader;
        this.commandHistory = commandHistory;
        var taskClient = new TaskClient(transport);
        var taskMonitor = taskMonitorFactory.apply(taskClient);
        this.fileCommands = new FileCommands(sessionStore, transport, out, taskMonitor);
        this.adminCommands = new AdminCommands(transport, out, taskMonitor);
        this.taskCommands = new TaskCommands(sessionStore, taskClient, taskMonitor, out);
    }

    private static Map<String, List<String>> commandOptions() {
        var options = new LinkedHashMap<String, List<String>>();
        options.put("login", List.of("--backend", "-b", "--url", "--username"));
        options.put("files-list", List.of("-t", "--type", "-p", "--page", "-s", "--size", "--sort-by", "--direction", "--search", "--case-sensitive"));
        options.put("file-show", List.of("-t", "--type", "-i", "--id", "--raw", "--content-limit"));
        options.put("publish-music", List.of("--monitor"));
        options.put("publish-gps", List.of("--file-group-id", "--time-series-name", "--monitor"));
        options.put("publish-files", List.of("--ids-file", "--monitor"));
        options.put("publish-status", List.of("--file-id", "--file-group-id", "--raw"));
        options.put("scan", List.of("-o", "--original-directory", "-e", "--export-directory", "--monitor"));
        options.put("tag-rename", List.of("--tag", "--to", "--name-de", "--name-en", "--name-es", "--name-fi", "--name-sv", "--monitor"));
        options.put("tag-replace", List.of("--tag", "--with", "--monitor"));
        options.put("metadata-update", List.of("--group", "--key", "--value", "--new-value", "--remove", "--monitor"));
        options.put("admin", List.of());
        options.put("tasks", List.of("--backend", "-b", "--raw"));
        options.put("task-monitor", List.of("--task-id", "--backend", "-b", "--interval-ms"));
        options.put("task-cancel", List.of("--task-id", "--backend", "-b"));
        options.put("session", List.of());
        options.put("logout", List.of());
        options.put("shell", List.of());
        return options;
    }

    @SuppressWarnings("CyclomaticComplexity")
    public void run(String[] args, boolean debugCompletion) {
        var root = new RootArgs();
        var login = new LoginCommand();

        var list = new FileCommands.ListCommand();
        var show = new FileCommands.ShowCommand();
        var publishMusic = new PublishMusicCommand();
        var publishGps = new FileCommands.PublishGpsCommand();
        var publishFiles = new FileCommands.PublishFilesCommand();
        var publishStatus = new FileCommands.PublishStatusCommand();
        var scan = new FileCommands.ScanCommand();
        var tagRename = new FileCommands.TagRenameCommand();
        var tagReplace = new FileCommands.TagReplaceCommand();
        var metadataUpdate = new FileCommands.MetadataUpdateCommand();

        var admin = new AdminCommands.AdminCommand();
        var adminListGroups = new AdminCommands.AdminListGroupsCommand();
        var adminPublishGroup = new AdminCommands.AdminPublishGroupCommand();
        var adminPublishGroups = new AdminCommands.AdminPublishGroupsCommand();

        var session = new SessionCommand();
        var sessionShow = new SessionShowCommand();
        var sessionUse = new SessionUseCommand();

        var logout = new LogoutCommand();
        var shell = new ShellCommand();
        var tasks = new TaskCommands.ListCommand();
        var taskMonitor = new TaskCommands.MonitorCommand();
        var taskCancel = new TaskCommands.CancelCommand();

        // Usage goes to this router's output: inside the TUI, writing it to System.out would draw over the screen.
        var console = new DefaultConsole(out);
        var jc = JCommander.newBuilder()
            .console(console)
            .programName("vempain-file-cli")
            .addObject(root)
            .addCommand("login", login)
            .addCommand("files-list", list)
            .addCommand("file-show", show)
            .addCommand("publish-music", publishMusic)
            .addCommand("publish-gps", publishGps)
            .addCommand("publish-files", publishFiles)
            .addCommand("publish-status", publishStatus)
            .addCommand("scan", scan)
            .addCommand("tag-rename", tagRename)
            .addCommand("tag-replace", tagReplace)
            .addCommand("metadata-update", metadataUpdate)
            .addCommand("admin", admin)
            .addCommand("tasks", tasks)
            .addCommand("task-monitor", taskMonitor)
            .addCommand("task-cancel", taskCancel)
            .addCommand("session", session)
            .addCommand("logout", logout)
            .addCommand("shell", shell)
            .build();

        var adminJc = jc.getCommands()
            .get("admin");
        adminJc.addCommand("list-groups", adminListGroups);
        adminJc.addCommand("publish-group", adminPublishGroup);
        adminJc.addCommand("publish-groups", adminPublishGroups);
        adminJc.setConsole(console);

        var sessionJc = jc.getCommands()
            .get("session");
        sessionJc.addCommand("show", sessionShow);
        sessionJc.addCommand("use", sessionUse);
        sessionJc.setConsole(console);

        if (args.length == 0) {
            jc.usage();
            return;
        }

        try {
            jc.parse(args);
        } catch (ParameterException e) {
            err.println(e.getMessage());
            jc.usage();
            return;
        }

        if (root.help) {
            jc.usage();
            return;
        }

        var command = jc.getParsedCommand();
        if (command == null) {
            jc.usage();
            return;
        }

        var debugEnabled = debugCompletion
                           || root.debugCompletion;
        try {
            switch (command) {
                case "login" -> login(login);
                case "files-list" -> fileCommands.listFiles(list);
                case "file-show" -> fileCommands.showFile(show);
                case "publish-music" -> fileCommands.publishMusic(publishMusic.monitor
                                                                  || shellMode);
                case "publish-gps" -> {
                    publishGps.monitor |= shellMode;
                    fileCommands.publishGps(publishGps);
                }
                case "publish-files" -> {
                    publishFiles.monitor |= shellMode;
                    fileCommands.publishFiles(publishFiles);
                }
                case "publish-status" -> fileCommands.publishStatus(publishStatus);
                case "scan" -> {
                    scan.monitor |= shellMode;
                    fileCommands.scan(scan);
                }
                case "tag-rename" -> {
                    tagRename.monitor |= shellMode;
                    fileCommands.renameTag(tagRename);
                }
                case "tag-replace" -> {
                    tagReplace.monitor |= shellMode;
                    fileCommands.replaceTag(tagReplace);
                }
                case "metadata-update" -> {
                    metadataUpdate.monitor |= shellMode;
                    fileCommands.updateMetadata(metadataUpdate);
                }
                case "admin" -> admin(adminJc, adminListGroups, adminPublishGroup, adminPublishGroups);
                case "tasks" -> taskCommands.list(tasks);
                case "task-monitor" -> taskCommands.monitor(taskMonitor);
                case "task-cancel" -> taskCommands.cancel(taskCancel);
                case "session" -> session(sessionJc, sessionShow, sessionUse);
                case "logout" -> logout();
                case "shell" -> {
                    if (shellMode) {
                        throw new IllegalStateException("Already in shell-mode.");
                    }
                    shell(debugEnabled);
                }
                default -> jc.usage();
            }
        } catch (Exception e) {
            err.println("Error: " + e.getMessage());
        }
    }

    public void runShell(LineReader reader, boolean debugCompletion) {
        var previousShellMode = shellMode;
        shellMode = true;
        try {
            out.println("Interactive shell started. Type 'exit' to quit.");
            while (true) {
                try {
                    var line = reader.readLine(shellPrompt());
                    if (!runShellLine(line, debugCompletion)) {
                        break;
                    }
                } catch (UserInterruptException ignored) {
                } catch (EndOfFileException ignored) {
                    break;
                }
            }
        } finally {
            shellMode = previousShellMode;
        }
    }

    /**
     * Runs one line typed into the interactive shell (line based or TUI) and records it in the command history.
     *
     * @return {@code false} when the line ends the shell
     */
    boolean runShellLine(String line, boolean debugCompletion) {
        var trimmed = line == null ? "" : line.trim();
        if (trimmed.isEmpty()) {
            return true;
        }
        try {
            commandHistory.append(trimmed);
        } catch (IOException e) {
            err.println("Warning: could not write command history: " + e.getMessage());
        }
        if ("exit".equalsIgnoreCase(trimmed)
            || "quit".equalsIgnoreCase(trimmed)) {
            return false;
        }
        var previousShellMode = shellMode;
        shellMode = true;
        try {
            run(splitArgs(trimmed), debugCompletion);
        } finally {
            shellMode = previousShellMode;
        }
        return true;
    }

    /**
     * Runs the full-screen TUI shell on {@code terminal}. Commands typed there go through a router whose output, password prompt and task monitor are bound to
     * the TUI windows.
     */
    void runTuiShell(Terminal terminal, boolean debugCompletion, Duration pollInterval) throws IOException, InterruptedException {
        List<String> history;
        try {
            history = commandHistory.commands();
        } catch (IOException e) {
            history = List.of();
        }
        var tui = new TuiShell(terminal, sessionStore, new TaskClient(transport), history, pollInterval);
        var router = new CommandRouter(sessionStore, transport, tui.out(), tui.err(), tui.passwordReader(), commandHistory, tui::taskMonitor);
        tui.run(line -> router.runShellLine(line, debugCompletion), router::shellPrompt, router.createCompleter(true, debugCompletion));
    }

    public Completer createCompleter(boolean shellCompleterMode, boolean debugCompletion) {
        return new CliCompleter(shellCompleterMode, debugCompletion);
    }

    private void login(LoginCommand command) throws Exception {
        var backend = normalizeBackend(command.backend);
        var baseUrl = sessionStore.normalizeBaseUrl(command.url);
        var password = passwordReader.readPassword("Password: ");
        var response = transport.login(baseUrl, command.username, password);
        // password char[] was zeroed inside transport.login()
        var token = response.optString("token", "");
        if (token.isBlank()) {
            throw new IllegalStateException("Login response did not include token");
        }
        var version = transport.getJson(baseUrl, "/version", token);
        BackendVersionValidator.validate(version);
        sessionStore.save(backend, baseUrl, token);
        out.println("Login successful. Session saved for backend '" + backend + "' at " + baseUrl);
    }

    private void logout() throws Exception {
        var activeBackend = sessionStore.getActiveBackend();
        sessionStore.clear(activeBackend);
        out.println("Session removed for backend '" + activeBackend + "'.");
    }

    private void admin(JCommander adminJc, AdminCommands.AdminListGroupsCommand listGroups, AdminCommands.AdminPublishGroupCommand publishGroup,
            AdminCommands.AdminPublishGroupsCommand publishGroups) throws Exception {
        var subCommand = adminJc.getParsedCommand();
        if (subCommand == null) {
            adminJc.usage();
            return;
        }
        switch (subCommand) {
            case "list-groups" -> adminCommands.listGroups(listGroups, requireSession(SessionStore.BACKEND_ADMIN));
            case "publish-group" -> {
                publishGroup.monitor |= shellMode;
                adminCommands.publishGroup(publishGroup, requireSession(SessionStore.BACKEND_ADMIN));
            }
            case "publish-groups" -> {
                publishGroups.monitor |= shellMode;
                adminCommands.publishGroups(publishGroups, requireSession(SessionStore.BACKEND_ADMIN));
            }
            default -> adminJc.usage();
        }
    }

    private void session(JCommander sessionJc, SessionShowCommand show, SessionUseCommand use) throws Exception {
        var subCommand = sessionJc.getParsedCommand();
        if (subCommand == null) {
            sessionJc.usage();
            return;
        }
        switch (subCommand) {
            case "show" -> sessionShow(show);
            case "use" -> sessionUse(use);
            default -> sessionJc.usage();
        }
    }

    private void sessionShow(SessionShowCommand command) {
        var activeBackend = sessionStore.getActiveBackend();
        var file = sessionStore.load(SessionStore.BACKEND_FILE);
        var admin = sessionStore.load(SessionStore.BACKEND_ADMIN);

        if (command.raw) {
            var json = new org.json.JSONObject().put("active_backend", activeBackend)
                .put("backends", new org.json.JSONObject());
            if (file != null) {
                json.getJSONObject("backends")
                    .put(SessionStore.BACKEND_FILE, new org.json.JSONObject().put("base_url", file.baseUrl()));
            }
            if (admin != null) {
                json.getJSONObject("backends")
                    .put(SessionStore.BACKEND_ADMIN, new org.json.JSONObject().put("base_url", admin.baseUrl()));
            }
            out.println(json.toString(2));
            return;
        }

        out.println("Active backend: " + activeBackend + " (logged in)");
        out.println("Stored sessions:");
        out.println("  file:  " + (file == null ? "not logged in" : file.baseUrl()));
        out.println("  admin: " + (admin == null ? "not logged in" : admin.baseUrl()));
    }

    private void sessionUse(SessionUseCommand command) throws Exception {
        var backend = normalizeBackend(command.backend);
        sessionStore.setActiveBackend(backend);
        out.println("Active backend set to '" + backend + "'.");
    }

    private SessionStore.Session requireSession(String backend) {
        var normalizedBackend = normalizeBackend(backend);
        var session = sessionStore.load(normalizedBackend);
        if (session == null) {
            throw new IllegalStateException(
                    "Not logged in for backend '" + normalizedBackend + "'. Run: login --backend " + normalizedBackend + " --url <url> --username <user>");
        }
        return session;
    }

    private String normalizeBackend(String backend) {
        return SessionStore.BACKEND_ADMIN.equalsIgnoreCase(backend) ? SessionStore.BACKEND_ADMIN : SessionStore.BACKEND_FILE;
    }

    private void shell(boolean debugCompletion) throws IOException, InterruptedException {
        if (TuiShell.isInteractiveTerminal()) {
            try (var terminal = TuiShell.createTerminal()) {
                runTuiShell(terminal, debugCompletion, TASK_POLL_INTERVAL);
            }
            return;
        }
        // Without a terminal (piped or redirected input) fall back to the line based shell.
        var reader = LineReaderBuilder.builder()
            .completer(createCompleter(true, debugCompletion))
            .build();
        try {
            commandHistory.load(reader.getHistory());
        } catch (IOException e) {
            err.println("Warning: could not load command history: " + e.getMessage());
        }
        runShell(reader, debugCompletion);
    }

    private String shellPrompt() {
        return "vempain-" + authenticatedBackendForPrompt() + "> ";
    }

    private String authenticatedBackendForPrompt() {
        var fileSession = sessionStore.load(SessionStore.BACKEND_FILE);
        var adminSession = sessionStore.load(SessionStore.BACKEND_ADMIN);
        if (fileSession == null
            && adminSession == null) {
            return "unauthicated";
        }
        if (fileSession != null
            && adminSession == null) {
            return SessionStore.BACKEND_FILE;
        }
        if (adminSession != null
            && fileSession == null) {
            return SessionStore.BACKEND_ADMIN;
        }
        var activeBackend = sessionStore.getActiveBackend();
        if (sessionStore.load(activeBackend) != null) {
            return activeBackend;
        }
        return "unauthicated";
    }

    private String[] splitArgs(String commandLine) {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = Pattern.compile("\"([^\"]*)\"|'([^']*)'|(\\S+)")
            .matcher(commandLine);
        while (matcher.find()) {
            if (matcher.group(1) != null) {
                tokens.add(matcher.group(1));
            } else if (matcher.group(2) != null) {
                tokens.add(matcher.group(2));
            } else {
                tokens.add(matcher.group(3));
            }
        }
        return tokens.toArray(new String[0]);
    }

    private static class RootArgs {

        @Parameter(names = { "-h", "--help" }, help = true, description = "Show help")
        boolean help;

        @Parameter(names = { "--debug-completion" }, description = "Enable shell completion debug logging")
        boolean debugCompletion;
    }

    @Parameters(commandDescription = "Authenticate and store session")
    private static class LoginCommand {

        @Parameter(names = { "--backend", "-b" }, description = "Backend name: file|admin")
        String backend = SessionStore.BACKEND_FILE;

        @Parameter(names = { "--url" }, required = true, description = "Backend base URL (e.g. http://localhost:8080/api)")
        String url;

        @Parameter(names = { "--username" }, required = true, description = "Username")
        String username;
    }

    @Parameters(commandDescription = "Generate and publish music dataset")
    private static class PublishMusicCommand {

        @Parameter(names = "--monitor", description = "Open the full-screen task monitor after submission")
        boolean monitor;
    }

    @Parameters(commandDescription = "Session management commands")
    private static class SessionCommand {
    }

    @Parameters(commandDescription = "Show stored sessions and active backend")
    private static class SessionShowCommand {

        @Parameter(names = { "--raw" }, description = "Print session info as JSON")
        boolean raw;
    }

    @Parameters(commandDescription = "Switch active backend for future commands")
    private static class SessionUseCommand {

        @Parameter(names = { "--backend", "-b" }, required = true, description = "Backend name: file|admin")
        String backend;
    }

    @Parameters(commandDescription = "Clear stored session")
    private static class LogoutCommand {
    }

    @Parameters(commandDescription = "Start interactive shell (supports tab completion)")
    private static class ShellCommand {
    }

    private class CliCompleter implements Completer {

        private final boolean shellCompleterMode;
        private final boolean debugCompletion;

        private CliCompleter(boolean shellCompleterMode, boolean debugCompletion) {
            this.shellCompleterMode = shellCompleterMode;
            this.debugCompletion = debugCompletion;
        }

        @Override
        public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
            var currentWord = line.word() == null ? "" : line.word();
            debug("input line='" + line.line() + "' wordIndex=" + line.wordIndex() + " currentWord='" + currentWord + "'");
            if (line.wordIndex() == 0) {
                completeCommands(currentWord, candidates);
                debugCandidates(currentWord, candidates);
                return;
            }

            var words = line.words();
            if (words.isEmpty()) {
                return;
            }
            var command = words.getFirst();

            if ("admin".equals(command)) {
                completeAdminSubcommands(words, line.wordIndex(), currentWord, candidates);
                debugCandidates(currentWord, candidates);
                return;
            }

            if ("session".equals(command)) {
                completeSessionSubcommands(words, line.wordIndex(), currentWord, candidates);
                debugCandidates(currentWord, candidates);
                return;
            }

            completeCommandOptions(command, words, line.wordIndex(), currentWord, candidates);

            if (wantsLocalPathCompletion(command, words, line.wordIndex(), currentWord)) {
                localPathCompleter.complete(currentWord, candidates);
                debugCandidates(currentWord, candidates);
                return;
            }

            if (("files-list".equals(command)
                 || "file-show".equals(command))
                && wantsTypeCompletion(words, line.wordIndex())) {
                FileCommands.fileTypes()
                    .stream()
                    .sorted()
                    .forEach(type -> candidates.add(new Candidate(type)));
                return;
            }

            if ("scan".equals(command)) {
                fileCommands.completeScanPath(words, line.wordIndex(), candidates, currentWord, debugCompletion, err);
            }
            debugCandidates(currentWord, candidates);
        }

        @SuppressWarnings("CyclomaticComplexity")
        private void completeAdminSubcommands(List<String> words, int wordIndex, String currentWord, List<Candidate> candidates) {
            if (wordIndex == 1) {
                for (var sub : List.of("list-groups", "publish-group", "publish-groups")) {
                    if (currentWord.isBlank()
                        || sub.startsWith(currentWord)) {
                        candidates.add(new Candidate(sub));
                    }
                }
                return;
            }

            var subcommand = words.size() > 1 ? words.get(1) : "";
            var options = switch (subcommand) {
                case "list-groups" -> List.of("--details", "--search", "--page", "--size", "--modified-after-publish");
                case "publish-group" -> List.of("--id", "--message", "--publish-datetime", "--monitor");
                case "publish-groups" -> List.of("--ids", "--monitor");
                default -> List.<String>of();
            };

            var previous = wordIndex > 0 ? words.get(Math.max(0, wordIndex - 1)) : "";
            var suggest = currentWord.isBlank()
                          || currentWord.startsWith("-")
                          || "--".equals(previous)
                          || "-".equals(previous);
            if (!suggest) {
                return;
            }

            for (var option : options) {
                if (currentWord.isBlank()
                    || option.startsWith(currentWord)) {
                    candidates.add(new Candidate(option));
                }
            }
        }

        @SuppressWarnings("CyclomaticComplexity")
        private void completeSessionSubcommands(List<String> words, int wordIndex, String currentWord, List<Candidate> candidates) {
            if (wordIndex == 1) {
                for (var sub : List.of("show", "use")) {
                    if (currentWord.isBlank()
                        || sub.startsWith(currentWord)) {
                        candidates.add(new Candidate(sub));
                    }
                }
                return;
            }

            var subcommand = words.size() > 1 ? words.get(1) : "";
            if (!"use".equals(subcommand)) {
                return;
            }

            if ("--backend".equals(currentWord)
                || "-b".equals(currentWord)) {
                return;
            }

            var previous = wordIndex > 0 ? words.get(Math.max(0, wordIndex - 1)) : "";
            if (wordIndex == 2
                && (currentWord.isBlank()
                    || currentWord.startsWith("-"))) {
                for (var option : List.of("--backend", "-b")) {
                    if (currentWord.isBlank()
                        || option.startsWith(currentWord)) {
                        candidates.add(new Candidate(option));
                    }
                }
                return;
            }

            if ("--backend".equals(previous)
                || "-b".equals(previous)) {
                for (var backend : List.of(SessionStore.BACKEND_FILE, SessionStore.BACKEND_ADMIN)) {
                    if (currentWord.isBlank()
                        || backend.startsWith(currentWord)) {
                        candidates.add(new Candidate(backend));
                    }
                }
            }
        }

        private void completeCommands(String currentWord, List<Candidate> candidates) {
            for (var command : BASE_COMMANDS) {
                if (shellCompleterMode
                    && "shell".equals(command)) {
                    continue;
                }
                if (currentWord.isBlank()
                    || command.startsWith(currentWord)) {
                    candidates.add(new Candidate(command));
                }
            }
        }

        private void completeCommandOptions(String command, List<String> words, int wordIndex, String currentWord, List<Candidate> candidates) {
            if ("exit".equals(command)
                || "quit".equals(command)) {
                return;
            }
            var options = COMMAND_OPTIONS.get(command);
            if (options == null
                || options.isEmpty()) {
                return;
            }

            var previous = wordIndex > 0 ? words.get(Math.max(0, wordIndex - 1)) : "";
            boolean suggest = (wordIndex == 1
                               && (currentWord.isBlank()
                                   || currentWord.startsWith("-")))
                              || currentWord.startsWith("-")
                              || "--".equals(previous)
                              || "-".equals(previous);
            if (!suggest) {
                return;
            }

            for (var option : options) {
                if (currentWord.isBlank()
                    || option.startsWith(currentWord)) {
                    candidates.add(new Candidate(option));
                }
            }
        }

        /**
         * The value of an option listed in {@link #LOCAL_PATH_OPTIONS} is being typed; a word starting with a dash is taken for an option instead.
         */
        private boolean wantsLocalPathCompletion(String command, List<String> words, int wordIndex, String currentWord) {
            var pathOptions = LOCAL_PATH_OPTIONS.get(command);
            if (pathOptions == null
                || wordIndex <= 0
                || wordIndex > words.size()
                || currentWord.startsWith("-")) {
                return false;
            }
            return pathOptions.contains(words.get(wordIndex - 1));
        }

        private boolean wantsTypeCompletion(List<String> words, int wordIndex) {
            if (wordIndex <= 0) {
                return false;
            }
            var previous = words.get(Math.max(0, wordIndex - 1));
            return "--type".equals(previous)
                   || "-t".equals(previous);
        }

        private void debugCandidates(String currentWord, List<Candidate> candidates) {
            if (!debugCompletion) {
                return;
            }
            var values = candidates.stream()
                .map(Candidate::value)
                .collect(Collectors.joining(", "));
            debug("suggestions for '" + currentWord + "': [" + values + "]");
        }

        private void debug(String message) {
            if (debugCompletion) {
                err.println("[completion-debug] " + message);
            }
        }
    }
}
