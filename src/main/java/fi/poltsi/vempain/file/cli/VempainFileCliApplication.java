package fi.poltsi.vempain.file.cli;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.beust.jcommander.Parameters;
import org.jline.reader.*;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.PrintStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class VempainFileCliApplication {

    private static final Set<String> FILE_TYPES = Set.of(
            "archive",
            "audio",
            "binary",
            "music",
            "data",
            "document",
            "executable",
            "font",
            "icon",
            "image",
            "interactive",
            "thumb",
            "vector",
            "video"
    );

    private static final List<String> BASE_COMMANDS = List.of(
            "login", "files-list", "file-show", "publish-music", "publish-gps", "scan", "logout", "shell", "exit", "quit"
    );
    private static final Map<String, List<String>> COMMAND_OPTIONS = commandOptions();

    private final SessionStore sessionStore;
    private final BackendClient backendClient;
    private final PrintStream out;
    private final PrintStream err;

    private boolean shellMode;
    private boolean debugCompletion;

    public VempainFileCliApplication() {
        this(new SessionStore(), new BackendClient(), System.out, System.err);
    }

    VempainFileCliApplication(SessionStore sessionStore, BackendClient backendClient, PrintStream out, PrintStream err) {
        this.sessionStore = sessionStore;
        this.backendClient = backendClient;
        this.out = out;
        this.err = err;
    }

    static void main(String[] args) {
        new VempainFileCliApplication().run(args);
    }

    void run(String[] args) {
        var root = new RootArgs();
        var login = new LoginCommand();
        var list = new ListCommand();
        var show = new ShowCommand();
        var publishMusic = new PublishMusicCommand();
        var publishGps = new PublishGpsCommand();
        var scan = new ScanCommand();
        var logout = new LogoutCommand();
        var shell = new ShellCommand();

        var jc = JCommander.newBuilder()
                .programName("vempain-file-cli")
                .addObject(root)
                .addCommand("login", login)
                .addCommand("files-list", list)
                .addCommand("file-show", show)
                .addCommand("publish-music", publishMusic)
                .addCommand("publish-gps", publishGps)
                .addCommand("scan", scan)
                .addCommand("logout", logout)
                .addCommand("shell", shell)
                .build();

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

        if (root.debugCompletion || isEnvDebugCompletionEnabled()) {
            debugCompletion = true;
        }

        try {
            switch (command) {
                case "login" -> login(login);
                case "files-list" -> listFiles(list);
                case "file-show" -> showFile(show);
                case "publish-music" -> publishMusic();
                case "publish-gps" -> publishGps(publishGps);
                case "scan" -> scan(scan);
                case "logout" -> logout();
                case "shell" -> {
                    if (shellMode) {
                        throw new IllegalStateException("Already in shell-mode.");
                    }
                    shell();
                }
                default -> jc.usage();
            }
        } catch (Exception e) {
            err.println("Error: " + e.getMessage());
        }
    }

    private void login(LoginCommand command) throws Exception {
        var baseUrl = sessionStore.normalizeBaseUrl(command.url);
        var response = backendClient.login(baseUrl, command.username, command.password);
        var token = response.optString("token", "");
        if (token.isBlank()) {
            throw new IllegalStateException("Login response did not include token");
        }
        sessionStore.save(baseUrl, token);
        out.println("Login successful. Session saved for " + baseUrl);
    }

    private void listFiles(ListCommand command) throws Exception {
        validateType(command.type);
        var session = requireSession();

        var request = new JSONObject();
        request.put("page", command.page);
        request.put("size", command.size);
        if (command.sortBy != null) {
            request.put("sort_by", command.sortBy);
        }
        if (command.direction != null) {
            request.put("direction", command.direction.toUpperCase(Locale.ROOT));
        }
        if (command.search != null) {
            request.put("search", command.search);
        }
        request.put("case_sensitive", command.caseSensitive);

        var response = backendClient.postJson(session, "/files/" + command.type + "/paged", request);
        var content = response.optJSONArray("content");
        if (content == null || content.isEmpty()) {
            out.println("No files found.");
            return;
        }

        out.printf("%-8s %-36s %-13s %-12s %-25s%n", "id", "filename", "file_type", "filesize", "created");
        out.println("-".repeat(100));
        for (int i = 0; i < content.length(); i++) {
            var item = content.getJSONObject(i);
            out.printf("%-8d %-36s %-13s %-12d %-25s%n",
                    item.optLong("id", 0L),
                    truncate(item.optString("filename", ""), 36),
                    truncate(item.optString("file_type", ""), 13),
                    item.optLong("filesize", 0L),
                    truncate(item.optString("created", ""), 25));
        }

        out.printf("%nPage %d/%d, total elements: %d%n",
                response.optInt("page", 0),
                response.has("totalPages") ? response.optInt("totalPages", response.optInt("total_pages", 0)) : response.optInt("total_pages", 0),
                response.has("totalElements") ? response.optLong("totalElements", response.optLong("total_elements", 0L)) : response.optLong("total_elements", 0L));
    }

    private void showFile(ShowCommand command) throws Exception {
        validateType(command.type);
        var session = requireSession();

        var metadata = backendClient.getJson(session, "/files/" + command.type + "/" + command.id);
        printMetadata(metadata, command.raw);

        HttpResponse<byte[]> contentResponse = backendClient.getBytes(session, "/files/" + command.id + "/content");
        var contentType = contentResponse.headers()
                .firstValue("content-type")
                .orElse("application/octet-stream");
        var bytes = contentResponse.body();

        if (isTextualContent(contentType)) {
            var text = new String(bytes, StandardCharsets.UTF_8);
            var maxLen = Math.max(0, command.contentLimit);
            if (!command.raw && text.length() > maxLen) {
                out.printf("%nContent (truncated to %d chars):%n", maxLen);
                out.println(text.substring(0, maxLen));
            } else {
                out.println("\nContent:");
                out.println(text);
            }
        } else {
            out.printf("%nBinary content (%d bytes, content-type=%s) is not displayed.%n", bytes.length, contentType);
        }
    }

    private void publishMusic() throws Exception {
        var session = requireSession();
        var response = backendClient.postJson(session, "/data-publish/music", new JSONObject());
        out.println(response.toString(2));
    }

    private void publishGps(PublishGpsCommand command) throws Exception {
        var session = requireSession();
        var request = new JSONObject()
                .put("file_group_id", command.fileGroupId)
                .put("time_series_name", command.timeSeriesName);
        var response = backendClient.postJson(session, "/data-publish/gps-timeseries", request);
        out.println(response.toString(2));
    }

    private void scan(ScanCommand command) throws Exception {
        var session = requireSession();
        if ((command.originalDirectory == null || command.originalDirectory.isBlank())
                && (command.exportDirectory == null || command.exportDirectory.isBlank())) {
            throw new IllegalArgumentException("Provide --original-directory and/or --export-directory");
        }

        var request = new JSONObject();
        if (command.originalDirectory != null && !command.originalDirectory.isBlank()) {
            request.put("original_directory", command.originalDirectory);
        }
        if (command.exportDirectory != null && !command.exportDirectory.isBlank()) {
            request.put("export_directory", command.exportDirectory);
        }

        var response = backendClient.postJson(session, "/scan-files", request);
        if (response.has("items") && response.get("items") instanceof JSONArray arr) {
            out.println(arr.toString(2));
        } else {
            out.println(response.toString(2));
        }
    }

    private void logout() throws Exception {
        sessionStore.clear();
        out.println("Session removed.");
    }

    private void shell() {
        var reader = LineReaderBuilder.builder()
                .completer(new CliCompleter(true))
                .build();
        runShell(reader);
    }

    void runShell(LineReader reader) {
        var previousShellMode = shellMode;
        shellMode = true;
        try {
            out.println("Interactive shell started. Type 'exit' to quit.");
            while (true) {
                try {
                    var line = reader.readLine("vempain-file> ");
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
                    run(splitArgs(trimmed));
                } catch (UserInterruptException ignored) {
                } catch (EndOfFileException ignored) {
                    break;
                }
            }
        } finally {
            shellMode = previousShellMode;
        }
    }

    private SessionStore.Session requireSession() {
        var session = sessionStore.load();
        if (session == null) {
            throw new IllegalStateException("Not logged in. Run: login --url <url> --username <user> --password <pass>");
        }
        return session;
    }

    private void validateType(String type) {
        if (type == null || !FILE_TYPES.contains(type.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Invalid --type. Allowed values: " + String.join(", ", FILE_TYPES));
        }
    }

    private boolean isTextualContent(String contentType) {
        var lower = contentType.toLowerCase(Locale.ROOT);
        return lower.startsWith("text/")
                || lower.contains("application/json")
                || lower.contains("application/xml")
                || lower.contains("application/javascript");
    }

    private void printMetadata(JSONObject metadata, boolean raw) {
        out.println("Metadata:");
        if (raw) {
            out.println(metadata.toString(2));
            return;
        }

        out.println("  [Identity]");
        out.println("    id: " + metadata.optLong("id", 0));
        out.println("    filename: " + metadata.optString("filename", ""));
        out.println("    file_type: " + metadata.optString("file_type", ""));
        out.println("    mimetype: " + metadata.optString("mimetype", ""));
        out.println("    filesize: " + metadata.optLong("filesize", 0));
        out.println("  [Timestamps]");
        out.println("    created: " + metadata.optString("created", ""));
        out.println("    modified: " + metadata.optString("modified", ""));
        out.println("    original_datetime: " + metadata.optString("original_datetime", ""));
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxLength || maxLength < 4) {
            return value;
        }
        return value.substring(0, maxLength - 3) + "...";
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

        private CliCompleter(boolean shellCompleterMode) {
            this.shellCompleterMode = shellCompleterMode;
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

            completeCommandOptions(command, words, line.wordIndex(), currentWord, candidates);

            if ((("files-list".equals(command) || "file-show".equals(command)) && wantsTypeCompletion(words, line.wordIndex()))) {
                FILE_TYPES.stream()
                        .sorted()
                        .forEach(type -> candidates.add(new Candidate(type)));
                return;
            }

            if ("scan".equals(command)) {
                completeScanPath(words, line.wordIndex(), candidates, currentWord);
            }
            debugCandidates(currentWord, candidates);
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

        private void completeScanPath(List<String> words, int wordIndex, List<Candidate> candidates, String currentWord) {
            if (wordIndex <= 0) {
                return;
            }
            var completionType = resolveScanCompletionType(words, wordIndex, currentWord);
            if (completionType == null) {
                return;
            }

            var session = sessionStore.load();
            if (session == null) {
                return;
            }

            var path = buildPathQuery(words, wordIndex, currentWord);
            var request = new JSONObject().put("path", path)
                    .put("type", completionType);
            debug("path-completion request: " + request);

            try {
                var response = backendClient.postJson(session, "/path-completion", request);
                var completions = response.optJSONArray("completions");
                debug("path-completion response: " + response);
                if (completions == null) {
                    return;
                }
                var uniqueMatch = completions.length() == 1;
                var hasPathPrefixToken = currentWord != null
                        && !currentWord.startsWith("/")
                        && wordIndex > 0
                        && words.get(wordIndex - 1)
                        .startsWith("/");
                var pathPrefixToken = hasPathPrefixToken ? words.get(wordIndex - 1) : "";
                var queryBasePath = basePathForQuery(path);

                for (int i = 0; i < completions.length(); i++) {
                    var completion = ensureDirectorySuffix(normalizeCompletionPath(completions.getString(i), queryBasePath));
                    var candidateValue = completion;
                    if (hasPathPrefixToken && completion.startsWith(pathPrefixToken)) {
                        candidateValue = completion.substring(pathPrefixToken.length());
                    }
                    candidates.add(new Candidate(candidateValue, completion, null, null, null, null, false));
                }

                if (uniqueMatch && completions.length() == 1) {
                    var target = ensureDirectorySuffix(normalizeCompletionPath(completions.getString(0), queryBasePath));
                    var applied = (hasPathPrefixToken && target.startsWith(pathPrefixToken))
                            ? target.substring(pathPrefixToken.length()) : target;
                    debug("apply decision: would replace '" + currentWord + "' with '" + applied + "'");
                }
            } catch (Exception ignored) {
            }
        }

        private String resolveScanCompletionType(List<String> words, int wordIndex, String currentWord) {
            if (currentWord != null && currentWord.startsWith("-")) {
                return null;
            }

            for (int i = wordIndex - 1; i >= 1; i--) {
                var token = words.get(i);
                if ("--original-directory".equals(token) || "-o".equals(token)) {
                    return "ORIGINAL";
                }
                if ("--export-directory".equals(token) || "-e".equals(token)) {
                    return "EXPORTED";
                }
                if (token.startsWith("-")) {
                    return null;
                }
            }
            return null;
        }

        private String buildPathQuery(List<String> words, int wordIndex, String currentWord) {
            if (currentWord == null || currentWord.isBlank()) {
                return "/";
            }
            if (currentWord.startsWith("/")) {
                return currentWord;
            }
            if (wordIndex > 0) {
                var previous = words.get(wordIndex - 1);
                if (previous.startsWith("/")) {
                    return previous + currentWord;
                }
            }
            return currentWord;
        }

        private String ensureDirectorySuffix(String completion) {
            if (completion == null || completion.isBlank() || completion.endsWith("/")) {
                return completion;
            }
            return completion + "/";
        }

        private String basePathForQuery(String queryPath) {
            if (queryPath == null || queryPath.isBlank() || "/".equals(queryPath)) {
                return "/";
            }
            if (queryPath.endsWith("/")) {
                return queryPath;
            }
            var lastSlash = queryPath.lastIndexOf('/');
            if (lastSlash < 0) {
                return "/";
            }
            if (lastSlash == 0) {
                return "/";
            }
            return queryPath.substring(0, lastSlash + 1);
        }

        private String normalizeCompletionPath(String rawCompletion, String queryBasePath) {
            if (rawCompletion == null || rawCompletion.isBlank()) {
                return rawCompletion;
            }
            var base = (queryBasePath == null || queryBasePath.isBlank()) ? "/" : queryBasePath;
            if (rawCompletion.startsWith(base)) {
                return rawCompletion;
            }
            if (rawCompletion.startsWith("/")) {
                if ("/".equals(base)) {
                    return rawCompletion;
                }
                return base + rawCompletion.substring(1);
            }
            if ("/".equals(base)) {
                return "/" + rawCompletion;
            }
            return base + rawCompletion;
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

    Completer createCompleterForTests() {
        return new CliCompleter(false);
    }

    Completer createShellCompleterForTests() {
        return new CliCompleter(true);
    }

    private boolean isEnvDebugCompletionEnabled() {
        var env = System.getenv("VEMPAIN_CLI_DEBUG_COMPLETION");
        if (env == null) {
            return false;
        }
        return "1".equals(env) || "true".equalsIgnoreCase(env) || "yes".equalsIgnoreCase(env);
    }

    private static Map<String, List<String>> commandOptions() {
        var options = new LinkedHashMap<String, List<String>>();
        options.put("login", List.of("--url", "--username", "--password"));
        options.put("files-list", List.of("-t", "--type", "-p", "--page", "-s", "--size", "--sort-by", "--direction", "--search", "--case-sensitive"));
        options.put("file-show", List.of("-t", "--type", "-i", "--id", "--raw", "--content-limit"));
        options.put("publish-music", List.of());
        options.put("publish-gps", List.of("--file-group-id", "--time-series-name"));
        options.put("scan", List.of("-o", "--original-directory", "-e", "--export-directory"));
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
        @Parameter(names = {"--url"}, required = true, description = "Backend base URL (e.g. http://localhost:8080/api)")
        String url;
        @Parameter(names = {"--username"}, required = true, description = "Username")
        String username;
        @Parameter(names = {"--password"}, required = true, password = true, description = "Password")
        String password;
    }

    @Parameters(commandDescription = "List files for one file type with paging")
    private static class ListCommand {
        @Parameter(names = {"-t", "--type"}, required = true, description = "File type in lowercase")
        String type;
        @Parameter(names = {"-p", "--page"}, description = "Page number (0-based)")
        int page = 0;
        @Parameter(names = {"-s", "--size"}, description = "Page size")
        int size = 25;
        @Parameter(names = {"--sort-by"}, description = "Sort field")
        String sortBy = "filename";
        @Parameter(names = {"--direction"}, description = "Sort direction ASC|DESC")
        String direction = "ASC";
        @Parameter(names = {"--search"}, description = "Search query")
        String search;
        @Parameter(names = {"--case-sensitive"}, description = "Case-sensitive search")
        boolean caseSensitive = false;
    }

    @Parameters(commandDescription = "Show file metadata and content for one typed file")
    private static class ShowCommand {
        @Parameter(names = {"-t", "--type"}, required = true, description = "File type in lowercase")
        String type;
        @Parameter(names = {"-i", "--id"}, required = true, description = "File ID")
        long id;
        @Parameter(names = {"--raw"}, description = "Show raw metadata JSON and do not truncate text content")
        boolean raw;
        @Parameter(names = {"--content-limit"}, description = "Maximum number of chars shown for textual content")
        int contentLimit = 8_192;
    }

    @Parameters(commandDescription = "Generate and publish music dataset")
    private static class PublishMusicCommand {
    }

    @Parameters(commandDescription = "Generate and publish GPS time-series dataset")
    private static class PublishGpsCommand {
        @Parameter(names = {"--file-group-id"}, required = true, description = "File group ID")
        long fileGroupId;
        @Parameter(names = {"--time-series-name"}, required = true, description = "Time-series identifier")
        String timeSeriesName;
    }

    @Parameters(commandDescription = "Trigger backend file scan")
    private static class ScanCommand {
        @Parameter(names = {"-o", "--original-directory"}, description = "Original directory path")
        String originalDirectory;
        @Parameter(names = {"-e", "--export-directory"}, description = "Export directory path")
        String exportDirectory;
    }

    @Parameters(commandDescription = "Clear stored session")
    private static class LogoutCommand {
    }

    @Parameters(commandDescription = "Start interactive shell (supports tab completion)")
    private static class ShellCommand {
    }
}

