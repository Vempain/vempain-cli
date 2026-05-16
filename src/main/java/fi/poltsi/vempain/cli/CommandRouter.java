package fi.poltsi.vempain.cli;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.beust.jcommander.Parameters;
import fi.poltsi.vempain.cli.admin.AdminCommands;
import fi.poltsi.vempain.cli.core.HttpTransport;
import fi.poltsi.vempain.cli.core.PasswordReader;
import fi.poltsi.vempain.cli.core.SessionStore;
import fi.poltsi.vempain.cli.file.FileCommands;
import org.jline.reader.*;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class CommandRouter {

    private static final List<String> BASE_COMMANDS = List.of(
            "login", "files-list", "file-show", "publish-music", "publish-gps", "scan", "admin", "session", "logout", "shell", "exit", "quit"
    );

    private static final Map<String, List<String>> COMMAND_OPTIONS = commandOptions();

    private final SessionStore sessionStore;
    private final HttpTransport transport;
    private final PrintStream out;
    private final PrintStream err;
    private final FileCommands fileCommands;
    private final AdminCommands adminCommands;
    private final PasswordReader passwordReader;

    private boolean shellMode;

    public CommandRouter(SessionStore sessionStore, HttpTransport transport, PrintStream out, PrintStream err) {
        this(sessionStore, transport, out, err, PasswordReader.consoleReader());
    }

    public CommandRouter(SessionStore sessionStore, HttpTransport transport, PrintStream out, PrintStream err, PasswordReader passwordReader) {
        this.sessionStore = sessionStore;
        this.transport = transport;
        this.out = out;
        this.err = err;
        this.passwordReader = passwordReader;
        this.fileCommands = new FileCommands(sessionStore, transport, out);
        this.adminCommands = new AdminCommands(transport, out);
    }

    public void run(String[] args, boolean debugCompletion) {
        var root = new RootArgs();
        var login = new LoginCommand();

        var list = new FileCommands.ListCommand();
        var show = new FileCommands.ShowCommand();
        var publishGps = new FileCommands.PublishGpsCommand();
        var scan = new FileCommands.ScanCommand();

        var admin = new AdminCommands.AdminCommand();
        var adminListGroups = new AdminCommands.AdminListGroupsCommand();
        var adminPublishGroup = new AdminCommands.AdminPublishGroupCommand();
        var adminPublishGroups = new AdminCommands.AdminPublishGroupsCommand();

        var session = new SessionCommand();
        var sessionShow = new SessionShowCommand();
        var sessionUse = new SessionUseCommand();

        var logout = new LogoutCommand();
        var shell = new ShellCommand();

        var jc = JCommander.newBuilder()
                .programName("vempain-file-cli")
                .addObject(root)
                .addCommand("login", login)
                .addCommand("files-list", list)
                .addCommand("file-show", show)
                .addCommand("publish-music", new PublishMusicCommand())
                .addCommand("publish-gps", publishGps)
                .addCommand("scan", scan)
                .addCommand("admin", admin)
                .addCommand("session", session)
                .addCommand("logout", logout)
                .addCommand("shell", shell)
                .build();

        var adminJc = jc.getCommands().get("admin");
        adminJc.addCommand("list-groups", adminListGroups);
        adminJc.addCommand("publish-group", adminPublishGroup);
        adminJc.addCommand("publish-groups", adminPublishGroups);

        var sessionJc = jc.getCommands().get("session");
        sessionJc.addCommand("show", sessionShow);
        sessionJc.addCommand("use", sessionUse);

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

        var debugEnabled = debugCompletion || root.debugCompletion;
        try {
            switch (command) {
                case "login" -> login(login);
                case "files-list" -> fileCommands.listFiles(list);
                case "file-show" -> fileCommands.showFile(show);
                case "publish-music" -> fileCommands.publishMusic();
                case "publish-gps" -> fileCommands.publishGps(publishGps);
                case "scan" -> fileCommands.scan(scan);
                case "admin" -> admin(adminJc, adminListGroups, adminPublishGroup, adminPublishGroups);
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
                    if (line == null) {
                        continue;
                    }
                    var trimmed = line.trim();
                    if (trimmed.isEmpty()) {
                        continue;
                    }
                    if ("exit".equalsIgnoreCase(trimmed) || "quit".equalsIgnoreCase(trimmed)) {
                        break;
                    }
                    run(splitArgs(trimmed), debugCompletion);
                } catch (UserInterruptException ignored) {
                } catch (EndOfFileException ignored) {
                    break;
                }
            }
        } finally {
            shellMode = previousShellMode;
        }
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
        sessionStore.save(backend, baseUrl, token);
        out.println("Login successful. Session saved for backend '" + backend + "' at " + baseUrl);
    }

    private void logout() throws Exception {
        var activeBackend = sessionStore.getActiveBackend();
        sessionStore.clear(activeBackend);
        out.println("Session removed for backend '" + activeBackend + "'.");
    }

    private void admin(JCommander adminJc,
                       AdminCommands.AdminListGroupsCommand listGroups,
                       AdminCommands.AdminPublishGroupCommand publishGroup,
                       AdminCommands.AdminPublishGroupsCommand publishGroups) throws Exception {
        var subCommand = adminJc.getParsedCommand();
        if (subCommand == null) {
            adminJc.usage();
            return;
        }
        switch (subCommand) {
            case "list-groups" -> adminCommands.listGroups(listGroups, requireSession(SessionStore.BACKEND_ADMIN));
            case "publish-group" ->
                    adminCommands.publishGroup(publishGroup, requireSession(SessionStore.BACKEND_ADMIN));
            case "publish-groups" ->
                    adminCommands.publishGroups(publishGroups, requireSession(SessionStore.BACKEND_ADMIN));
            default -> adminJc.usage();
        }
    }

    private void session(JCommander sessionJc,
                         SessionShowCommand show,
                         SessionUseCommand use) throws Exception {
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
            var json = new org.json.JSONObject()
                    .put("active_backend", activeBackend)
                    .put("backends", new org.json.JSONObject());
            if (file != null) {
                json.getJSONObject("backends").put(SessionStore.BACKEND_FILE,
                        new org.json.JSONObject().put("base_url", file.baseUrl()));
            }
            if (admin != null) {
                json.getJSONObject("backends").put(SessionStore.BACKEND_ADMIN,
                        new org.json.JSONObject().put("base_url", admin.baseUrl()));
            }
            out.println(json.toString(2));
            return;
        }

        out.println("Active backend: " + activeBackend);
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
            throw new IllegalStateException("Not logged in for backend '" + normalizedBackend + "'. Run: login --backend "
                    + normalizedBackend + " --url <url> --username <user>");
        }
        return session;
    }

    private String normalizeBackend(String backend) {
        return SessionStore.BACKEND_ADMIN.equalsIgnoreCase(backend) ? SessionStore.BACKEND_ADMIN : SessionStore.BACKEND_FILE;
    }

    private void shell(boolean debugCompletion) {
        var reader = LineReaderBuilder.builder()
                .completer(createCompleter(true, debugCompletion))
                .build();
        runShell(reader, debugCompletion);
    }

    private String shellPrompt() {
        return "vempain-" + authenticatedBackendForPrompt() + "> ";
    }

    private String authenticatedBackendForPrompt() {
        var fileSession = sessionStore.load(SessionStore.BACKEND_FILE);
        var adminSession = sessionStore.load(SessionStore.BACKEND_ADMIN);
        if (fileSession == null && adminSession == null) {
            return "unauthicated";
        }
        if (fileSession != null && adminSession == null) {
            return SessionStore.BACKEND_FILE;
        }
        if (adminSession != null && fileSession == null) {
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

            if (("files-list".equals(command) || "file-show".equals(command)) && wantsTypeCompletion(words, line.wordIndex())) {
                FileCommands.fileTypes().stream()
                        .sorted()
                        .forEach(type -> candidates.add(new Candidate(type)));
                return;
            }

            if ("scan".equals(command)) {
                fileCommands.completeScanPath(words, line.wordIndex(), candidates, currentWord, debugCompletion, err);
            }
            debugCandidates(currentWord, candidates);
        }

        private void completeAdminSubcommands(List<String> words, int wordIndex, String currentWord, List<Candidate> candidates) {
            if (wordIndex == 1) {
                for (var sub : List.of("list-groups", "publish-group", "publish-groups")) {
                    if (currentWord.isBlank() || sub.startsWith(currentWord)) {
                        candidates.add(new Candidate(sub));
                    }
                }
                return;
            }

            var subcommand = words.size() > 1 ? words.get(1) : "";
            var options = switch (subcommand) {
                case "list-groups" -> List.of("--details", "--search", "--page", "--size");
                case "publish-group" -> List.of("--id", "--message", "--publish-datetime");
                case "publish-groups" -> List.of("--ids");
                default -> List.<String>of();
            };

            var previous = wordIndex > 0 ? words.get(Math.max(0, wordIndex - 1)) : "";
            var suggest = currentWord.isBlank() || currentWord.startsWith("-") || "--".equals(previous) || "-".equals(previous);
            if (!suggest) {
                return;
            }

            for (var option : options) {
                if (currentWord.isBlank() || option.startsWith(currentWord)) {
                    candidates.add(new Candidate(option));
                }
            }
        }

        private void completeSessionSubcommands(List<String> words, int wordIndex, String currentWord, List<Candidate> candidates) {
            if (wordIndex == 1) {
                for (var sub : List.of("show", "use")) {
                    if (currentWord.isBlank() || sub.startsWith(currentWord)) {
                        candidates.add(new Candidate(sub));
                    }
                }
                return;
            }

            var subcommand = words.size() > 1 ? words.get(1) : "";
            if (!"use".equals(subcommand)) {
                return;
            }

            if ("--backend".equals(currentWord) || "-b".equals(currentWord)) {
                return;
            }

            var previous = wordIndex > 0 ? words.get(Math.max(0, wordIndex - 1)) : "";
            if (wordIndex == 2 && (currentWord.isBlank() || currentWord.startsWith("-"))) {
                for (var option : List.of("--backend", "-b")) {
                    if (currentWord.isBlank() || option.startsWith(currentWord)) {
                        candidates.add(new Candidate(option));
                    }
                }
                return;
            }

            if ("--backend".equals(previous) || "-b".equals(previous)) {
                for (var backend : List.of(SessionStore.BACKEND_FILE, SessionStore.BACKEND_ADMIN)) {
                    if (currentWord.isBlank() || backend.startsWith(currentWord)) {
                        candidates.add(new Candidate(backend));
                    }
                }
            }
        }

        private void completeCommands(String currentWord, List<Candidate> candidates) {
            for (var command : BASE_COMMANDS) {
                if (shellCompleterMode && "shell".equals(command)) {
                    continue;
                }
                if (currentWord.isBlank() || command.startsWith(currentWord)) {
                    candidates.add(new Candidate(command));
                }
            }
        }

        private void completeCommandOptions(String command, List<String> words, int wordIndex, String currentWord, List<Candidate> candidates) {
            if ("exit".equals(command) || "quit".equals(command)) {
                return;
            }
            var options = COMMAND_OPTIONS.get(command);
            if (options == null || options.isEmpty()) {
                return;
            }

            var previous = wordIndex > 0 ? words.get(Math.max(0, wordIndex - 1)) : "";
            boolean suggest = (wordIndex == 1 && (currentWord.isBlank() || currentWord.startsWith("-")))
                    || currentWord.startsWith("-")
                    || "--".equals(previous) || "-".equals(previous);
            if (!suggest) {
                return;
            }

            for (var option : options) {
                if (currentWord.isBlank() || option.startsWith(currentWord)) {
                    candidates.add(new Candidate(option));
                }
            }
        }

        private boolean wantsTypeCompletion(List<String> words, int wordIndex) {
            if (wordIndex <= 0) {
                return false;
            }
            var previous = words.get(Math.max(0, wordIndex - 1));
            return "--type".equals(previous) || "-t".equals(previous);
        }

        private void debugCandidates(String currentWord, List<Candidate> candidates) {
            if (!debugCompletion) {
                return;
            }
            var values = candidates.stream().map(Candidate::value).collect(Collectors.joining(", "));
            debug("suggestions for '" + currentWord + "': [" + values + "]");
        }

        private void debug(String message) {
            if (debugCompletion) {
                err.println("[completion-debug] " + message);
            }
        }
    }

    private static Map<String, List<String>> commandOptions() {
        var options = new LinkedHashMap<String, List<String>>();
        options.put("login", List.of("--backend", "-b", "--url", "--username"));
        options.put("files-list", List.of("-t", "--type", "-p", "--page", "-s", "--size", "--sort-by", "--direction", "--search", "--case-sensitive"));
        options.put("file-show", List.of("-t", "--type", "-i", "--id", "--raw", "--content-limit"));
        options.put("publish-music", List.of());
        options.put("publish-gps", List.of("--file-group-id", "--time-series-name"));
        options.put("scan", List.of("-o", "--original-directory", "-e", "--export-directory"));
        options.put("admin", List.of());
        options.put("session", List.of());
        options.put("logout", List.of());
        options.put("shell", List.of());
        return options;
    }

    private static class RootArgs {
        @Parameter(names = {"-h", "--help"}, help = true, description = "Show help")
        boolean help;

        @Parameter(names = {"--debug-completion"}, description = "Enable shell completion debug logging")
        boolean debugCompletion;
    }

    @Parameters(commandDescription = "Authenticate and store session")
    private static class LoginCommand {
        @Parameter(names = {"--backend", "-b"}, description = "Backend name: file|admin")
        String backend = SessionStore.BACKEND_FILE;

        @Parameter(names = {"--url"}, required = true, description = "Backend base URL (e.g. http://localhost:8080/api)")
        String url;

        @Parameter(names = {"--username"}, required = true, description = "Username")
        String username;
    }

    @Parameters(commandDescription = "Generate and publish music dataset")
    private static class PublishMusicCommand {
    }

    @Parameters(commandDescription = "Session management commands")
    private static class SessionCommand {
    }

    @Parameters(commandDescription = "Show stored sessions and active backend")
    private static class SessionShowCommand {
        @Parameter(names = {"--raw"}, description = "Print session info as JSON")
        boolean raw;
    }

    @Parameters(commandDescription = "Switch active backend for future commands")
    private static class SessionUseCommand {
        @Parameter(names = {"--backend", "-b"}, required = true, description = "Backend name: file|admin")
        String backend;
    }

    @Parameters(commandDescription = "Clear stored session")
    private static class LogoutCommand {
    }

    @Parameters(commandDescription = "Start interactive shell (supports tab completion)")
    private static class ShellCommand {
    }
}

