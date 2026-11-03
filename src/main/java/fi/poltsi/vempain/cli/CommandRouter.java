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

    /**
     * The top level commands: the backend specific work sits under {@code file} and {@code admin}, the session and task commands apply to both backends.
     */
    private static final List<String> BASE_COMMANDS = List.of("login", "file", "admin", "tasks", "task-monitor", "task-cancel", "session", "logout", "shell",
            "exit", "quit");

    private static final Map<String, List<String>> COMMAND_OPTIONS = commandOptions();
    /** The subcommands of {@code file} (the file backend) and their options, in completion order */
    static final Map<String, List<String>> FILE_OPTIONS = fileOptions();
    /** The subcommands of {@code admin} (the admin backend) and their options, in completion order */
    static final Map<String, List<String>> ADMIN_OPTIONS = adminOptions();
    /** The top level commands of version 1.x and their form since 2.0, so that a user typing the old name is told the new one */
    static final Map<String, String> LEGACY_COMMANDS = legacyCommands();

    /**
     * The options whose value names a file of the local filesystem; their values are completed from the filesystem in both shells.
     */
    private static final Map<String, List<String>> LOCAL_PATH_OPTIONS = Map.of("publish-files", List.of("--ids-file"), "publish-site-files",
            List.of("--ids-file"));

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
        // Every command the user runs renews the session; the renewed token replaces the stored one (on disk or in memory)
        transport.setTokenRenewalListener(this::adoptRenewedToken);
        var taskClient = new TaskClient(transport);
        var taskMonitor = taskMonitorFactory.apply(taskClient);
        this.fileCommands = new FileCommands(sessionStore, transport, out, taskMonitor);
        this.adminCommands = new AdminCommands(transport, out, taskMonitor);
        this.taskCommands = new TaskCommands(sessionStore, taskClient, taskMonitor, out);
    }

    private static Map<String, List<String>> commandOptions() {
        var options = new LinkedHashMap<String, List<String>>();
        options.put("login", List.of("--backend", "-b", "--url", "--username"));
        options.put("file", List.of());
        options.put("admin", List.of());
        options.put("tasks", List.of("--backend", "-b", "--raw"));
        options.put("task-monitor", List.of("--task-id", "--backend", "-b", "--interval-ms"));
        options.put("task-cancel", List.of("--task-id", "--backend", "-b"));
        options.put("session", List.of());
        options.put("logout", List.of());
        options.put("shell", List.of());
        return options;
    }

    private static Map<String, List<String>> fileOptions() {
        var options = new LinkedHashMap<String, List<String>>();
        options.put("list", List.of("-t", "--type", "-p", "--page", "-s", "--size", "--sort-by", "--direction", "--search", "--case-sensitive"));
        options.put("show", List.of("-t", "--type", "-i", "--id", "--raw", "--content-limit"));
        options.put("scan", List.of("-o", "--original-directory", "-e", "--export-directory", "--monitor"));
        options.put("publish-files", List.of("--ids-file", "--monitor"));
        options.put("publish-status", List.of("--file-id", "--file-group-id", "--raw"));
        options.put("publish-music", List.of("--publish-to-site", "--acl", "--monitor"));
        options.put("publish-gps", List.of("--file-group-id", "--time-series-name", "--publish-to-site", "--acl", "--monitor"));
        options.put("tag-rename", List.of("--tag", "--to", "--name-de", "--name-en", "--name-es", "--name-fi", "--name-sv", "--monitor"));
        options.put("tag-replace", List.of("--tag", "--with", "--monitor"));
        options.put("metadata-update", List.of("--group", "--key", "--value", "--new-value", "--remove", "--monitor"));
        return options;
    }

    private static Map<String, List<String>> adminOptions() {
        var options = new LinkedHashMap<String, List<String>>();
        options.put("list-groups", List.of("--details", "--search", "--page", "--size", "--modified-after-publish"));
        options.put("list-tags", List.of("--search", "--limit"));
        options.put("publish-group", List.of("--id", "--message", "--publish-datetime", "--monitor"));
        options.put("publish-groups", List.of("--ids", "--monitor"));
        options.put("publish-site-files", List.of("--ids-file", "--monitor"));
        return options;
    }

    private static Map<String, String> legacyCommands() {
        var legacy = new LinkedHashMap<String, String>();
        legacy.put("files-list", "file list");
        legacy.put("file-show", "file show");
        legacy.put("scan", "file scan");
        legacy.put("publish-files", "file publish-files");
        legacy.put("publish-status", "file publish-status");
        legacy.put("publish-music", "file publish-music");
        legacy.put("publish-gps", "file publish-gps");
        legacy.put("tag-rename", "file tag-rename");
        legacy.put("tag-replace", "file tag-replace");
        legacy.put("metadata-update", "file metadata-update");
        return legacy;
    }

    @SuppressWarnings("CyclomaticComplexity")
    public void run(String[] args, boolean debugCompletion) {
        var root = new RootArgs();
        var login = new LoginCommand();

        var fileGroup = new FileGroupCommand();
        var fileSubcommands = new FileSubcommands(new FileCommands.ListCommand(), new FileCommands.ShowCommand(), new FileCommands.ScanCommand(),
                new FileCommands.PublishFilesCommand(), new FileCommands.PublishStatusCommand(), new PublishMusicCommand(),
                new FileCommands.PublishGpsCommand(), new FileCommands.TagRenameCommand(), new FileCommands.TagReplaceCommand(),
                new FileCommands.MetadataUpdateCommand());

        var admin = new AdminCommands.AdminCommand();
        var adminListGroups = new AdminCommands.AdminListGroupsCommand();
        var adminListTags = new AdminCommands.AdminListTagsCommand();
        var adminPublishGroup = new AdminCommands.AdminPublishGroupCommand();
        var adminPublishGroups = new AdminCommands.AdminPublishGroupsCommand();
        var adminPublishSiteFiles = new AdminCommands.AdminPublishSiteFilesCommand();

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
            .programName("vf-cli")
            .addObject(root)
            .addCommand("login", login)
            .addCommand("file", fileGroup)
            .addCommand("admin", admin)
            .addCommand("tasks", tasks)
            .addCommand("task-monitor", taskMonitor)
            .addCommand("task-cancel", taskCancel)
            .addCommand("session", session)
            .addCommand("logout", logout)
            .addCommand("shell", shell)
            .build();

        var fileJc = jc.getCommands()
            .get("file");
        fileJc.addCommand("list", fileSubcommands.list());
        fileJc.addCommand("show", fileSubcommands.show());
        fileJc.addCommand("scan", fileSubcommands.scan());
        fileJc.addCommand("publish-files", fileSubcommands.publishFiles());
        fileJc.addCommand("publish-status", fileSubcommands.publishStatus());
        fileJc.addCommand("publish-music", fileSubcommands.publishMusic());
        fileJc.addCommand("publish-gps", fileSubcommands.publishGps());
        fileJc.addCommand("tag-rename", fileSubcommands.tagRename());
        fileJc.addCommand("tag-replace", fileSubcommands.tagReplace());
        fileJc.addCommand("metadata-update", fileSubcommands.metadataUpdate());
        fileJc.setConsole(console);

        var adminJc = jc.getCommands()
            .get("admin");
        adminJc.addCommand("list-groups", adminListGroups);
        adminJc.addCommand("list-tags", adminListTags);
        adminJc.addCommand("publish-group", adminPublishGroup);
        adminJc.addCommand("publish-groups", adminPublishGroups);
        adminJc.addCommand("publish-site-files", adminPublishSiteFiles);
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

        var legacy = LEGACY_COMMANDS.get(args[0]);
        if (legacy != null) {
            err.println("Since version 2.0 the command '"
                    + args[0]
                    + "' is '"
                    + legacy
                    + "': the file backend commands are grouped under 'file' and the "
                    + "admin backend commands under 'admin'. Run: vf-cli "
                    + legacy
                    + (args.length > 1 ? " "
                            + String.join(" ", List.of(args)
                                .subList(1, args.length))
                            : ""));
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
                case "file" -> file(fileJc, fileSubcommands);
                case "admin" -> admin(adminJc, adminListGroups, adminListTags, adminPublishGroup, adminPublishGroups, adminPublishSiteFiles);
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
            sessionStore.discardInMemorySessions();
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
        try {
            tui.run(line -> router.runShellLine(line, debugCompletion), router::shellPrompt, router.createCompleter(true, debugCompletion));
        } finally {
            // Sessions created by a login inside the TUI must not outlive it.
            sessionStore.discardInMemorySessions();
        }
    }

    public Completer createCompleter(boolean shellCompleterMode, boolean debugCompletion) {
        return new CliCompleter(shellCompleterMode, debugCompletion);
    }

    private void adoptRenewedToken(SessionStore.Session session, String token, String expiresAt) {
        try {
            sessionStore.renewToken(session.backend(), session.token(), token);
        } catch (IOException e) {
            err.println("Warning: could not store the renewed session: " + e.getMessage());
        }
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
        if (shellMode) {
            // A login inside the shell or TUI invalidates the sessions stored on disk; from here on the sessions live only in this process
            // and are gone when the shell exits.
            sessionStore.keepSessionsInMemory();
        }
        sessionStore.save(backend, baseUrl, token);
        if (sessionStore.isInMemory()) {
            out.println("Login successful. Session for backend '"
                    + backend
                    + "' at "
                    + baseUrl
                    + " is kept in memory until the shell exits; the sessions stored on disk were removed.");
        } else {
            out.println("Login successful. Session saved for backend '" + backend + "' at " + baseUrl);
        }
    }

    private void logout() throws Exception {
        var activeBackend = sessionStore.getActiveBackend();
        sessionStore.clear(activeBackend);
        out.println("Session removed for backend '" + activeBackend + "'.");
    }

    /** The parsed argument objects of the {@code file} group, one per subcommand */
    private record FileSubcommands(FileCommands.ListCommand list, FileCommands.ShowCommand show, FileCommands.ScanCommand scan,
            FileCommands.PublishFilesCommand publishFiles, FileCommands.PublishStatusCommand publishStatus, PublishMusicCommand publishMusic,
            FileCommands.PublishGpsCommand publishGps, FileCommands.TagRenameCommand tagRename, FileCommands.TagReplaceCommand tagReplace,
            FileCommands.MetadataUpdateCommand metadataUpdate) {}

    @SuppressWarnings("CyclomaticComplexity")
    private void file(JCommander fileJc, FileSubcommands sub) throws Exception {
        var subCommand = fileJc.getParsedCommand();
        if (subCommand == null) {
            fileJc.usage();
            return;
        }
        switch (subCommand) {
            case "list" -> fileCommands.listFiles(sub.list());
            case "show" -> fileCommands.showFile(sub.show());
            case "scan" -> {
                sub.scan().monitor |= shellMode;
                fileCommands.scan(sub.scan());
            }
            case "publish-files" -> {
                sub.publishFiles().monitor |= shellMode;
                fileCommands.publishFiles(sub.publishFiles());
            }
            case "publish-status" -> fileCommands.publishStatus(sub.publishStatus());
            case "publish-music" -> fileCommands.publishMusic(sub.publishMusic().monitor
                                                              || shellMode,
                    sub.publishMusic().publishToSite, sub.publishMusic().acls);
            case "publish-gps" -> {
                sub.publishGps().monitor |= shellMode;
                fileCommands.publishGps(sub.publishGps());
            }
            case "tag-rename" -> {
                sub.tagRename().monitor |= shellMode;
                fileCommands.renameTag(sub.tagRename());
            }
            case "tag-replace" -> {
                sub.tagReplace().monitor |= shellMode;
                fileCommands.replaceTag(sub.tagReplace());
            }
            case "metadata-update" -> {
                sub.metadataUpdate().monitor |= shellMode;
                fileCommands.updateMetadata(sub.metadataUpdate());
            }
            default -> fileJc.usage();
        }
    }

    private void admin(JCommander adminJc, AdminCommands.AdminListGroupsCommand listGroups, AdminCommands.AdminListTagsCommand listTags,
            AdminCommands.AdminPublishGroupCommand publishGroup, AdminCommands.AdminPublishGroupsCommand publishGroups,
            AdminCommands.AdminPublishSiteFilesCommand publishSiteFiles) throws Exception {
        var subCommand = adminJc.getParsedCommand();
        if (subCommand == null) {
            adminJc.usage();
            return;
        }
        switch (subCommand) {
            case "list-groups" -> adminCommands.listGroups(listGroups, requireSession(SessionStore.BACKEND_ADMIN));
            case "list-tags" -> adminCommands.listTags(listTags, requireSession(SessionStore.BACKEND_ADMIN));
            case "publish-group" -> {
                publishGroup.monitor |= shellMode;
                adminCommands.publishGroup(publishGroup, requireSession(SessionStore.BACKEND_ADMIN));
            }
            case "publish-groups" -> {
                publishGroups.monitor |= shellMode;
                adminCommands.publishGroups(publishGroups, requireSession(SessionStore.BACKEND_ADMIN));
            }
            case "publish-site-files" -> {
                publishSiteFiles.monitor |= shellMode;
                adminCommands.publishSiteFiles(publishSiteFiles, requireSession(SessionStore.BACKEND_ADMIN));
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
                .put("in_memory", sessionStore.isInMemory())
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
        out.println(
                sessionStore.isInMemory() ? "Sessions kept in memory until the shell exits (the stored sessions were removed at login):" : "Stored sessions:");
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

    @Parameters(commandDescription = "Vempain File backend commands (list, show, scan, publish-files, publish-status, publish-music, publish-gps, tag-rename, "
            + "tag-replace, metadata-update)")
    private static class FileGroupCommand {
    }

    @Parameters(commandDescription = "Generate and publish music dataset")
    private static class PublishMusicCommand {

        @Parameter(names = "--publish-to-site", description = "Also publish the created data set to the website (the admin backend's publishing task)")
        boolean publishToSite;
        @Parameter(names = "--acl",
                description = "Admin user or unit granted privileges on the data set, user:<id>:<privileges> or unit:<id>:<privileges> "
                        + "with the privileges as letters out of r, c, m and d (repeatable); 'none' removes every additional grantee of an existing data set; "
                        + "without --acl the current grantees stay")
        List<String> acls;
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

            if ("file".equals(command)) {
                completeFileSubcommands(words, line.wordIndex(), currentWord, candidates);
                debugCandidates(currentWord, candidates);
                return;
            }

            if ("admin".equals(command)) {
                completeGroupSubcommands(ADMIN_OPTIONS, words, line.wordIndex(), currentWord, candidates);
                debugCandidates(currentWord, candidates);
                return;
            }

            if ("session".equals(command)) {
                completeSessionSubcommands(words, line.wordIndex(), currentWord, candidates);
                debugCandidates(currentWord, candidates);
                return;
            }

            completeCommandOptions(command, words, line.wordIndex(), currentWord, candidates);
            debugCandidates(currentWord, candidates);
        }

        /** The {@code file} group: its subcommands and options, then the file types of {@code list}/{@code show} and the backend paths of {@code scan} */
        private void completeFileSubcommands(List<String> words, int wordIndex, String currentWord, List<Candidate> candidates) {
            if (completeGroupSubcommands(FILE_OPTIONS, words, wordIndex, currentWord, candidates)) {
                return;
            }
            var subcommand = words.size() > 1 ? words.get(1) : "";
            if (("list".equals(subcommand)
                 || "show".equals(subcommand))
                && wantsTypeCompletion(words, wordIndex)) {
                FileCommands.fileTypes()
                    .stream()
                    .sorted()
                    .forEach(type -> candidates.add(new Candidate(type)));
                return;
            }
            if ("scan".equals(subcommand)
                && wordIndex >= 1
                && words.size() > 1) {
                // The scan completion sees the words from its own subcommand on, as it did when scan was a top level command
                fileCommands.completeScanPath(words.subList(1, words.size()), wordIndex - 1, candidates, currentWord, debugCompletion, err);
            }
        }

        /**
         * Completes a backend group ({@code file}, {@code admin}): the subcommand names in the second word, the local file of an {@link #LOCAL_PATH_OPTIONS}
         * option, and the options of the subcommand when the user asks for them (the first word after the subcommand, a dash, or a lone dash before).
         *
         * @return {@code true} when the word was fully handled (subcommand names or a local path), {@code false} when the group may add more candidates
         */
        @SuppressWarnings("CyclomaticComplexity")
        private boolean completeGroupSubcommands(Map<String, List<String>> groupOptions, List<String> words, int wordIndex, String currentWord,
                List<Candidate> candidates) {
            if (wordIndex == 1) {
                for (var sub : groupOptions.keySet()) {
                    if (currentWord.isBlank()
                        || sub.startsWith(currentWord)) {
                        candidates.add(new Candidate(sub));
                    }
                }
                return true;
            }

            var subcommand = words.size() > 1 ? words.get(1) : "";
            var options = groupOptions.getOrDefault(subcommand, List.of());

            if (wantsLocalPathCompletion(subcommand, words, wordIndex, currentWord)) {
                localPathCompleter.complete(currentWord, candidates);
                return true;
            }

            var previous = wordIndex > 0 ? words.get(Math.max(0, wordIndex - 1)) : "";
            var suggest = (wordIndex == 2
                           && currentWord.isBlank())
                          || currentWord.startsWith("-")
                          || "--".equals(previous)
                          || "-".equals(previous);
            if (!suggest) {
                return false;
            }

            for (var option : options) {
                if (currentWord.isBlank()
                    || option.startsWith(currentWord)) {
                    candidates.add(new Candidate(option));
                }
            }
            return false;
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
