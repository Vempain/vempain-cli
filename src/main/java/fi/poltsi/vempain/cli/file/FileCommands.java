package fi.poltsi.vempain.cli.file;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import fi.poltsi.vempain.cli.core.HttpTransport;
import fi.poltsi.vempain.cli.core.OutputFormatter;
import fi.poltsi.vempain.cli.core.SessionStore;
import org.jline.reader.Candidate;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.PrintStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class FileCommands {

    private static final Set<String> FILE_TYPES = Set.of(
            "archive", "audio", "binary", "music", "data", "document", "executable",
            "font", "icon", "image", "interactive", "thumb", "vector", "video"
    );

    private final SessionStore sessionStore;
    private final HttpTransport transport;
    private final PrintStream out;
    private final OutputFormatter formatter;

    public FileCommands(SessionStore sessionStore, HttpTransport transport, PrintStream out) {
        this.sessionStore = sessionStore;
        this.transport = transport;
        this.out = out;
        this.formatter = new OutputFormatter(out);
    }

    public void listFiles(ListCommand command) throws Exception {
        validateType(command.type);
        var session = requireSession();

        var request = new JSONObject()
                .put("page", command.page)
                .put("size", command.size)
                .put("case_sensitive", command.caseSensitive);
        if (command.sortBy != null) {
            request.put("sort_by", command.sortBy);
        }
        if (command.direction != null) {
            request.put("direction", command.direction.toUpperCase(Locale.ROOT));
        }
        if (command.search != null) {
            request.put("search", command.search);
        }

        var response = transport.postJson(session, "/files/" + command.type + "/paged", request);
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
                    formatter.truncate(item.optString("filename", ""), 36),
                    formatter.truncate(item.optString("file_type", ""), 13),
                    item.optLong("filesize", 0L),
                    formatter.truncate(item.optString("created", ""), 25));
        }

        out.printf("%nPage %d/%d, total elements: %d%n",
                response.optInt("page", 0),
                response.has("totalPages") ? response.optInt("totalPages", response.optInt("total_pages", 0)) : response.optInt("total_pages", 0),
                response.has("totalElements") ? response.optLong("totalElements", response.optLong("total_elements", 0L)) : response.optLong("total_elements", 0L));
    }

    public void showFile(ShowCommand command) throws Exception {
        validateType(command.type);
        var session = requireSession();

        var metadata = transport.getJson(session, "/files/" + command.type + "/" + command.id);
        formatter.printFileMetadata(metadata, command.raw);

        HttpResponse<byte[]> contentResponse = transport.getBytes(session, "/files/" + command.id + "/content");
        var contentType = contentResponse.headers().firstValue("content-type").orElse("application/octet-stream");
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

    public void publishMusic() throws Exception {
        var session = requireSession();
        var response = transport.postJson(session, "/data-publish/music", new JSONObject());
        out.println(response.toString(2));
    }

    public void publishGps(PublishGpsCommand command) throws Exception {
        var session = requireSession();
        var request = new JSONObject()
                .put("file_group_id", command.fileGroupId)
                .put("time_series_name", command.timeSeriesName);
        var response = transport.postJson(session, "/data-publish/gps-timeseries", request);
        out.println(response.toString(2));
    }

    public void scan(ScanCommand command) throws Exception {
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

        var response = transport.postJson(session, "/scan-files", request);
        if (response.has("items") && response.get("items") instanceof JSONArray arr) {
            out.println(arr.toString(2));
        } else {
            out.println(response.toString(2));
        }
    }

    public void completeScanPath(List<String> words,
                                 int wordIndex,
                                 List<Candidate> candidates,
                                 String currentWord,
                                 boolean debugCompletion,
                                 PrintStream debugOut) {
        if (wordIndex <= 0) {
            return;
        }
        var completionType = resolveScanCompletionType(words, wordIndex, currentWord);
        if (completionType == null) {
            return;
        }

        var session = sessionStore.load(SessionStore.BACKEND_FILE);
        if (session == null) {
            return;
        }

        var path = buildPathQuery(words, wordIndex, currentWord);
        var request = new JSONObject().put("path", path).put("type", completionType);
        debug(debugCompletion, debugOut, "path-completion request: " + request);

        try {
            var response = transport.postJson(session, "/path-completion", request);
            var completions = response.optJSONArray("completions");
            debug(debugCompletion, debugOut, "path-completion response: " + response);
            if (completions == null) {
                return;
            }

            var hasPathPrefixToken = currentWord != null
                    && !currentWord.startsWith("/")
                    && wordIndex > 0
                    && words.get(wordIndex - 1).startsWith("/");
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
        } catch (Exception ignored) {
        }
    }

    private void validateType(String type) {
        if (type == null || !FILE_TYPES.contains(type.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Invalid --type. Allowed values: " + String.join(", ", FILE_TYPES));
        }
    }

    private SessionStore.Session requireSession() {
        var session = sessionStore.load(SessionStore.BACKEND_FILE);
        if (session == null) {
            throw new IllegalStateException("Not logged in for backend 'file'. Run: login --backend file --url <url> --username <user> --password <pass>");
        }
        return session;
    }

    private boolean isTextualContent(String contentType) {
        var lower = contentType.toLowerCase(Locale.ROOT);
        return lower.startsWith("text/")
                || lower.contains("application/json")
                || lower.contains("application/xml")
                || lower.contains("application/javascript");
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
        if (lastSlash <= 0) {
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

    private void debug(boolean enabled, PrintStream debugOut, String message) {
        if (enabled) {
            debugOut.println("[completion-debug] " + message);
        }
    }

    public static Set<String> fileTypes() {
        return FILE_TYPES;
    }

    @Parameters(commandDescription = "List files for one file type with paging")
    public static class ListCommand {
        @Parameter(names = {"-t", "--type"}, required = true, description = "File type in lowercase")
        public String type;
        @Parameter(names = {"-p", "--page"}, description = "Page number (0-based)")
        public int page = 0;
        @Parameter(names = {"-s", "--size"}, description = "Page size")
        public int size = 25;
        @Parameter(names = {"--sort-by"}, description = "Sort field")
        public String sortBy = "filename";
        @Parameter(names = {"--direction"}, description = "Sort direction ASC|DESC")
        public String direction = "ASC";
        @Parameter(names = {"--search"}, description = "Search query")
        public String search;
        @Parameter(names = {"--case-sensitive"}, description = "Case-sensitive search")
        public boolean caseSensitive;
    }

    @Parameters(commandDescription = "Show file metadata and content for one typed file")
    public static class ShowCommand {
        @Parameter(names = {"-t", "--type"}, required = true, description = "File type in lowercase")
        public String type;
        @Parameter(names = {"-i", "--id"}, required = true, description = "File ID")
        public long id;
        @Parameter(names = {"--raw"}, description = "Show raw metadata JSON and do not truncate text content")
        public boolean raw;
        @Parameter(names = {"--content-limit"}, description = "Maximum number of chars shown for textual content")
        public int contentLimit = 8_192;
    }

    @Parameters(commandDescription = "Generate and publish GPS time-series dataset")
    public static class PublishGpsCommand {
        @Parameter(names = {"--file-group-id"}, required = true, description = "File group ID")
        public long fileGroupId;
        @Parameter(names = {"--time-series-name"}, required = true, description = "Time-series identifier")
        public String timeSeriesName;
    }

    @Parameters(commandDescription = "Trigger backend file scan")
    public static class ScanCommand {
        @Parameter(names = {"-o", "--original-directory"}, description = "Original directory path")
        public String originalDirectory;
        @Parameter(names = {"-e", "--export-directory"}, description = "Export directory path")
        public String exportDirectory;
    }
}

