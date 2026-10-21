package fi.poltsi.vempain.cli.file;

import java.io.IOException;
import java.io.PrintStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.jline.reader.Candidate;
import org.json.JSONArray;
import org.json.JSONObject;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;

import fi.poltsi.vempain.cli.core.HttpTransport;
import fi.poltsi.vempain.cli.core.OutputFormatter;
import fi.poltsi.vempain.cli.core.SessionStore;
import fi.poltsi.vempain.cli.core.TaskClient;
import fi.poltsi.vempain.cli.core.TaskMonitor;

public class FileCommands {

    private static final Set<String> FILE_TYPES = Set.of("archive", "audio", "binary", "music", "data", "document", "executable", "font", "icon", "image",
            "interactive", "thumb", "vector", "video");

    private final SessionStore sessionStore;
    private final HttpTransport transport;
    private final PrintStream out;
    private final OutputFormatter formatter;
    private final TaskMonitor taskMonitor;

    public FileCommands(SessionStore sessionStore, HttpTransport transport, PrintStream out) {
        this(sessionStore, transport, out, new TaskMonitor(new TaskClient(transport), out));
    }

    public FileCommands(SessionStore sessionStore, HttpTransport transport, PrintStream out, TaskMonitor taskMonitor) {
        this.sessionStore = sessionStore;
        this.transport = transport;
        this.out = out;
        this.formatter = new OutputFormatter(out);
        this.taskMonitor = taskMonitor;
    }

    public void listFiles(ListCommand command) throws Exception {
        validateType(command.type);
        var session = requireSession();

        var request = new JSONObject().put("page", command.page)
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
        if (content == null
            || content.isEmpty()) {
            out.println("No files found.");
            return;
        }

        out.printf("%-8s %-36s %-13s %-12s %-25s%n", "id", "filename", "file_type", "filesize", "created");
        out.println("-".repeat(100));
        for (int i = 0; i < content.length(); i++) {
            var item = content.getJSONObject(i);
            out.printf("%-8d %-36s %-13s %-12d %-25s%n", item.optLong("id", 0L), formatter.truncate(item.optString("filename", ""), 36),
                    formatter.truncate(item.optString("file_type", ""), 13), item.optLong("filesize", 0L),
                    formatter.truncate(item.optString("created", ""), 25));
        }

        var totalPages = response.has("totalPages") ? response.optInt("totalPages", response.optInt("total_pages", 0)) : response.optInt("total_pages", 0);
        var totalElements = response.has("totalElements") ? response.optLong("totalElements", response.optLong("total_elements", 0L))
                : response.optLong("total_elements", 0L);
        out.printf("%nPage %d/%d, total elements: %d%n", response.optInt("page", 0), totalPages, totalElements);
    }

    public void showFile(ShowCommand command) throws Exception {
        validateType(command.type);
        var session = requireSession();

        var metadata = transport.getJson(session, "/files/" + command.type + "/" + command.id);
        formatter.printFileMetadata(metadata, command.raw);

        HttpResponse<byte[]> contentResponse = transport.getBytes(session, "/files/" + command.id + "/content");
        var contentType = contentResponse.headers()
            .firstValue("content-type")
            .orElse("application/octet-stream");
        var bytes = contentResponse.body();

        if (isTextualContent(contentType)) {
            var text = new String(bytes, StandardCharsets.UTF_8);
            var maxLen = Math.max(0, command.contentLimit);
            if (!command.raw
                && text.length() > maxLen) {
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
        publishMusic(false);
    }

    public void publishMusic(boolean monitor) throws Exception {
        var session = requireSession();
        var response = transport.postJson(session, "/data-publish/music", new JSONObject());
        out.println(response.toString(2));
        monitor(response, session, monitor);
    }

    public void publishGps(PublishGpsCommand command) throws Exception {
        var session = requireSession();
        var request = new JSONObject().put("file_group_id", command.fileGroupId)
            .put("time_series_name", command.timeSeriesName);
        var response = transport.postJson(session, "/data-publish/gps-timeseries", request);
        out.println(response.toString(2));
        monitor(response, session, command.monitor);
    }

    /**
     * Shows whether a file or a file group was published to the admin backend before (GET /publish/file/{id}/status, GET /publish/file-group/{id}/status): the
     * site file or gallery it became, the site files of the gallery and the admin users and units the previous publish granted privileges to.
     */
    public void publishStatus(PublishStatusCommand command) throws Exception {
        if ((command.fileId == null) == (command.fileGroupId == null)) {
            throw new IllegalArgumentException("Give exactly one of --file-id and --file-group-id");
        }
        var session = requireSession();

        if (command.fileId != null) {
            var status = transport.getJson(session, "/publish/file/" + command.fileId + "/status");
            if (command.raw) {
                out.println(status.toString(2));
                return;
            }
            if (!status.optBoolean("published", false)) {
                out.printf("File %d has not been published to Vempain Admin.%n", command.fileId);
                return;
            }
            out.printf("File %d is published as site file %d.%n", command.fileId, status.optLong("site_file_id", 0L));
            printGrantees(status.optJSONArray("acls"));
            return;
        }

        var status = transport.getJson(session, "/publish/file-group/" + command.fileGroupId + "/status");
        if (command.raw) {
            out.println(status.toString(2));
            return;
        }
        if (!status.optBoolean("published", false)) {
            out.printf("File group %d has not been published to Vempain Admin.%n", command.fileGroupId);
            return;
        }
        var siteFileIds = status.optJSONArray("site_file_ids");
        out.printf("File group %d is published as gallery %d (%s) with %d site files%s%n", command.fileGroupId, status.optLong("gallery_id", 0L),
                status.optString("gallery_name", ""), siteFileIds == null ? 0 : siteFileIds.length(), siteFileIds == null
                                                                                                      || siteFileIds.isEmpty() ? "."
                                                                                                              : ": " + siteFileIds.join(", "));
        printGrantees(status.optJSONArray("acls"));
    }

    private void printGrantees(JSONArray acls) {
        if (acls == null
            || acls.isEmpty()) {
            out.println("No additional admin users or units hold privileges on it.");
            return;
        }
        out.printf("%-8s %-8s %-6s %-6s %-6s %-6s%n", "user_id", "unit_id", "read", "create", "modify", "delete");
        out.println("-".repeat(46));
        for (int i = 0; i < acls.length(); i++) {
            var acl = acls.getJSONObject(i);
            out.printf("%-8s %-8s %-6b %-6b %-6b %-6b%n", acl.isNull("user_id") ? "-" : acl.optString("user_id"),
                    acl.isNull("unit_id") ? "-" : acl.optString("unit_id"), acl.optBoolean("read_privilege"), acl.optBoolean("create_privilege"),
                    acl.optBoolean("modify_privilege"), acl.optBoolean("delete_privilege"));
        }
    }

    public void publishFiles(PublishFilesCommand command) throws Exception {
        var session = requireSession();
        var ids = new LinkedHashSet<Long>();
        int lineNumber = 0;
        try (var lines = Files.lines(Path.of(command.idsFile), StandardCharsets.UTF_8)) {
            for (var line : (Iterable<String>) lines::iterator) {
                lineNumber++;
                var value = line.trim();
                if (value.isBlank()
                    || value.startsWith("#")) {
                    continue;
                }
                try {
                    var id = Long.parseLong(value);
                    if (id <= 0) {
                        throw new NumberFormatException();
                    }
                    ids.add(id);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid file ID at " + command.idsFile + ":" + lineNumber + ": " + value);
                }
            }
        }
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("The file contains no positive file IDs: " + command.idsFile);
        }

        var request = new JSONObject().put("file_ids", new JSONArray(ids));
        try {
            var response = transport.postJson(session, "/publish/files", request);
            out.printf("Submitted %d file IDs for publishing.%n", ids.size());
            out.println(response.toString(2));
            monitor(response, session, command.monitor);
        } catch (IOException e) {
            if (!e.getMessage()
                .contains("HTTP 404")) {
                throw e;
            }
            out.println("Bulk publishing is not available on this file backend; falling back to POST /publish/file for each ID.");
            publishFilesIndividually(session, ids, command.monitor);
        }
    }

    private void publishFilesIndividually(SessionStore.Session session, Set<Long> ids, boolean monitor) throws Exception {
        var failures = new LinkedHashSet<String>();
        var submitted = 0;
        var taskIds = new LinkedHashSet<String>();
        for (var id : ids) {
            try {
                var response = transport.postJson(session, "/publish/file", new JSONObject().put("file_id", id));
                var taskId = TaskClient.taskId(response);
                if (taskId != null) {
                    taskIds.add(taskId);
                }
                submitted++;
            } catch (IOException e) {
                failures.add(id + ": " + e.getMessage());
            }
        }
        out.printf("Submitted %d of %d file IDs individually.%n", submitted, ids.size());
        if (!failures.isEmpty()) {
            out.println("Failed file IDs:");
            failures.forEach(out::println);
            throw new IOException("Some file IDs could not be submitted");
        }
        if (monitor) {
            if (taskIds.isEmpty()) {
                throw new IllegalStateException("The backend did not return task_id for the fallback submissions; cannot monitor them.");
            }
            for (var taskId : taskIds) {
                taskMonitor.printFinal(taskMonitor.monitor(session, taskId, java.time.Duration.ofSeconds(1)));
            }
        }
    }

    public void scan(ScanCommand command) throws Exception {
        var session = requireSession();
        if ((command.originalDirectory == null
             || command.originalDirectory.isBlank())
            && (command.exportDirectory == null
                || command.exportDirectory.isBlank())) {
            throw new IllegalArgumentException("Provide --original-directory and/or --export-directory");
        }

        var request = new JSONObject();
        if (command.originalDirectory != null
            && !command.originalDirectory.isBlank()) {
            request.put("original_directory", command.originalDirectory);
        }
        if (command.exportDirectory != null
            && !command.exportDirectory.isBlank()) {
            request.put("export_directory", command.exportDirectory);
        }

        var response = transport.postJson(session, "/scan-files", request);
        if (response.has("items")
            && response.get("items") instanceof JSONArray arr) {
            out.println(arr.toString(2));
        } else {
            out.println(response.toString(2));
        }
        monitor(response, session, command.monitor);
    }

    /**
     * Renames a tag on every file carrying it ({@code POST /tags/all/rename}, a background task with one step per file). The current translations of the tag
     * are looked up first and kept unless a {@code --name-xx} option replaces them, because the backend clears every translation that the request leaves out.
     */
    public void renameTag(TagRenameCommand command) throws Exception {
        var session = requireSession();
        requireText(command.tag, "--tag");
        requireText(command.newName, "--to");

        var current = findTag(session, command.tag);
        var request = new JSONObject().put("tag_name", command.tag)
            .put("replacement_tag_name", command.newName)
            .put("file_ids", new JSONArray());
        putTranslation(request, current, "tag_name_de", command.nameDe);
        putTranslation(request, current, "tag_name_en", command.nameEn);
        putTranslation(request, current, "tag_name_es", command.nameEs);
        putTranslation(request, current, "tag_name_fi", command.nameFi);
        putTranslation(request, current, "tag_name_sv", command.nameSv);

        var response = transport.postJson(session, "/tags/all/rename", request);
        out.printf("Submitted rename of tag '%s' to '%s'.%n", command.tag, command.newName);
        out.println(response.toString(2));
        monitor(response, session, command.monitor);
    }

    /**
     * Replaces a tag with another existing tag on every file carrying it ({@code POST /tags/all/replace}, a background task with one step per file); the
     * replaced tag is deleted once no file carries it any more.
     */
    public void replaceTag(TagReplaceCommand command) throws Exception {
        var session = requireSession();
        requireText(command.tag, "--tag");
        requireText(command.replacement, "--with");
        if (command.tag.equals(command.replacement)) {
            throw new IllegalArgumentException("--with must name a different tag than --tag");
        }

        var request = new JSONObject().put("tag_name", command.tag)
            .put("replacement_tag_name", command.replacement)
            .put("file_ids", new JSONArray());
        var response = transport.postJson(session, "/tags/all/replace", request);
        out.printf("Submitted replacement of tag '%s' with '%s'.%n", command.tag, command.replacement);
        out.println(response.toString(2));
        monitor(response, session, command.monitor);
    }

    /**
     * Rewrites one metadata value in every file carrying the given group, key and current value ({@code POST /metadata/value}, a background task with one step
     * per file). {@code --remove} (or an empty {@code --new-value}) removes the entry from the files.
     */
    public void updateMetadata(MetadataUpdateCommand command) throws Exception {
        var session = requireSession();
        requireText(command.group, "--group");
        requireText(command.key, "--key");
        if (command.value == null) {
            throw new IllegalArgumentException("--value is required");
        }
        if (command.newValue == null
            && !command.remove) {
            throw new IllegalArgumentException("Provide --new-value <value> or --remove");
        }
        if (command.newValue != null
            && !command.newValue.isEmpty()
            && command.remove) {
            throw new IllegalArgumentException("--new-value and --remove exclude each other");
        }

        var newValue = command.remove
                       || command.newValue == null ? "" : command.newValue;
        var request = new JSONObject().put("metadata_group", command.group)
            .put("metadata_key", command.key)
            .put("metadata_value", command.value)
            .put("new_value", newValue);
        var response = transport.postJson(session, "/metadata/value", request);
        if (newValue.isEmpty()) {
            out.printf("Submitted removal of %s:%s = '%s' from the files.%n", command.group, command.key, command.value);
        } else {
            out.printf("Submitted rewrite of %s:%s from '%s' to '%s'.%n", command.group, command.key, command.value, newValue);
        }
        out.println(response.toString(2));
        monitor(response, session, command.monitor);
    }

    /**
     * Looks a tag up by its exact default name through the paged search; {@code null} when the backend does not list it (the rename request is still sent, the
     * backend answers with its own error then).
     */
    private JSONObject findTag(SessionStore.Session session, String tagName) {
        try {
            var response = transport.postJson(session, "/tags/paged", new JSONObject().put("page", 0)
                .put("size", 200)
                .put("search", tagName)
                .put("case_sensitive", true));
            var content = response.optJSONArray("content");
            if (content == null) {
                return null;
            }
            for (int i = 0; i < content.length(); i++) {
                var tag = content.getJSONObject(i);
                if (tagName.equals(tag.optString("tag_name", null))) {
                    return tag;
                }
            }
        } catch (Exception e) {
            out.println("Could not look up the current translations of tag '" + tagName + "': " + e.getMessage());
        }
        return null;
    }

    private static void putTranslation(JSONObject request, JSONObject current, String field, String override) {
        if (override != null) {
            request.put(field, override);
            return;
        }
        if (current != null
            && !current.isNull(field)) {
            request.put(field, current.getString(field));
        }
    }

    private static void requireText(String value, String option) {
        if (value == null
            || value.isBlank()) {
            throw new IllegalArgumentException(option + " is required and must not be blank");
        }
    }

    public void completeScanPath(List<String> words, int wordIndex, List<Candidate> candidates, String currentWord, boolean debugCompletion,
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
        var request = new JSONObject().put("path", path)
            .put("type", completionType);
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
                                     && words.get(wordIndex - 1)
                                         .startsWith("/");
            var pathPrefixToken = hasPathPrefixToken ? words.get(wordIndex - 1) : "";
            var queryBasePath = basePathForQuery(path);

            for (int i = 0; i < completions.length(); i++) {
                var completion = ensureDirectorySuffix(normalizeCompletionPath(completions.getString(i), queryBasePath));
                var candidateValue = completion;
                if (hasPathPrefixToken
                    && completion.startsWith(pathPrefixToken)) {
                    candidateValue = completion.substring(pathPrefixToken.length());
                }
                candidates.add(new Candidate(candidateValue, completion, null, null, null, null, false));
            }
        } catch (Exception ignored) {
        }
    }

    private void validateType(String type) {
        if (type == null
            || !FILE_TYPES.contains(type.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Invalid --type. Allowed values: " + String.join(", ", FILE_TYPES));
        }
    }

    private SessionStore.Session requireSession() {
        var session = sessionStore.load(SessionStore.BACKEND_FILE);
        if (session == null) {
            throw new IllegalStateException("""
                    Not logged in for backend 'file'. Run: login --backend file --url <url> --username <user> --password <pass>""");
        }

        return session;
    }

    private void monitor(JSONObject response, SessionStore.Session session, boolean requested) throws Exception {
        if (!requested) {
            return;
        }
        var taskId = TaskClient.taskId(response);
        if (taskId == null) {
            throw new IllegalStateException("The backend did not return a task_id; cannot monitor the request.");
        }
        taskMonitor.printFinal(taskMonitor.monitor(session, taskId, java.time.Duration.ofSeconds(1)));
    }

    private boolean isTextualContent(String contentType) {
        var lower = contentType.toLowerCase(Locale.ROOT);
        return lower.startsWith("text/")
               || lower.contains("application/json")
               || lower.contains("application/xml")
               || lower.contains("application/javascript");
    }

    private String resolveScanCompletionType(List<String> words, int wordIndex, String currentWord) {
        if (currentWord != null
            && currentWord.startsWith("-")) {
            return null;
        }

        for (int i = wordIndex - 1; i >= 1; i--) {
            var token = words.get(i);
            if ("--original-directory".equals(token)
                || "-o".equals(token)) {
                return "ORIGINAL";
            }
            if ("--export-directory".equals(token)
                || "-e".equals(token)) {
                return "EXPORTED";
            }
            if (token.startsWith("-")) {
                return null;
            }
        }
        return null;
    }

    private String buildPathQuery(List<String> words, int wordIndex, String currentWord) {
        if (currentWord == null
            || currentWord.isBlank()) {
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
        if (completion == null
            || completion.isBlank()
            || completion.endsWith("/")) {
            return completion;
        }
        return completion + "/";
    }

    private String basePathForQuery(String queryPath) {
        if (queryPath == null
            || queryPath.isBlank()
            || "/".equals(queryPath)) {
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
        if (rawCompletion == null
            || rawCompletion.isBlank()) {
            return rawCompletion;
        }
        var base = (queryBasePath == null
                    || queryBasePath.isBlank()) ? "/" : queryBasePath;
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

        @Parameter(names = { "-t", "--type" }, required = true, description = "File type in lowercase")
        public String type;
        @Parameter(names = { "-p", "--page" }, description = "Page number (0-based)")
        public int page = 0;
        @Parameter(names = { "-s", "--size" }, description = "Page size")
        public int size = 25;
        @Parameter(names = { "--sort-by" }, description = "Sort field")
        public String sortBy = "filename";
        @Parameter(names = { "--direction" }, description = "Sort direction ASC|DESC")
        public String direction = "ASC";
        @Parameter(names = { "--search" }, description = "Search query")
        public String search;
        @Parameter(names = { "--case-sensitive" }, description = "Case-sensitive search")
        public boolean caseSensitive;
    }

    @Parameters(commandDescription = "Show file metadata and content for one typed file")
    public static class ShowCommand {

        @Parameter(names = { "-t", "--type" }, required = true, description = "File type in lowercase")
        public String type;
        @Parameter(names = { "-i", "--id" }, required = true, description = "File ID")
        public long id;
        @Parameter(names = { "--raw" }, description = "Show raw metadata JSON and do not truncate text content")
        public boolean raw;
        @Parameter(names = { "--content-limit" }, description = "Maximum number of chars shown for textual content")
        public int contentLimit = 8_192;
    }

    @Parameters(commandDescription = "Generate and publish GPS time-series dataset")
    public static class PublishGpsCommand {

        @Parameter(names = { "--file-group-id" }, required = true, description = "File group ID")
        public long fileGroupId;
        @Parameter(names = { "--time-series-name" }, required = true, description = "Time-series identifier")
        public String timeSeriesName;
        @Parameter(names = "--monitor", description = "Open the full-screen task monitor after submission")
        public boolean monitor;
    }

    @Parameters(commandDescription = "Show whether a file or file group was published to Vempain Admin before and who was granted access by that publish")
    public static class PublishStatusCommand {

        @Parameter(names = { "--file-id" }, description = "File ID (exactly one of --file-id and --file-group-id)")
        public Long fileId;
        @Parameter(names = { "--file-group-id" }, description = "File group ID (exactly one of --file-id and --file-group-id)")
        public Long fileGroupId;
        @Parameter(names = { "--raw" }, description = "Print the JSON answer of the file backend")
        public boolean raw;
    }

    @Parameters(commandDescription = "Publish files listed in a UTF-8 file, one file ID per line")
    public static class PublishFilesCommand {

        @Parameter(names = { "--ids-file" }, required = true, description = "File containing one file ID per line")
        public String idsFile;
        @Parameter(names = "--monitor", description = "Open the full-screen task monitor after submission")
        public boolean monitor;
    }

    @Parameters(commandDescription = "Rename a tag on every file carrying it (rewrites the file metadata as a background task)")
    public static class TagRenameCommand {

        @Parameter(names = { "--tag" }, required = true, description = "Current tag name")
        public String tag;
        @Parameter(names = { "--to" }, required = true, description = "New tag name (must not exist yet)")
        public String newName;
        @Parameter(names = { "--name-de" }, description = "German name; the current one is kept when omitted")
        public String nameDe;
        @Parameter(names = { "--name-en" }, description = "English name; the current one is kept when omitted")
        public String nameEn;
        @Parameter(names = { "--name-es" }, description = "Spanish name; the current one is kept when omitted")
        public String nameEs;
        @Parameter(names = { "--name-fi" }, description = "Finnish name; the current one is kept when omitted")
        public String nameFi;
        @Parameter(names = { "--name-sv" }, description = "Swedish name; the current one is kept when omitted")
        public String nameSv;
        @Parameter(names = "--monitor", description = "Open the full-screen task monitor after submission")
        public boolean monitor;
    }

    @Parameters(commandDescription = "Replace a tag with another existing tag on every file carrying it (rewrites the file metadata as a background task)")
    public static class TagReplaceCommand {

        @Parameter(names = { "--tag" }, required = true, description = "Tag to replace")
        public String tag;
        @Parameter(names = { "--with" }, required = true, description = "Existing tag that replaces it")
        public String replacement;
        @Parameter(names = "--monitor", description = "Open the full-screen task monitor after submission")
        public boolean monitor;
    }

    @Parameters(commandDescription = "Rewrite one metadata value in every file carrying it (rewrites the file metadata as a background task)")
    public static class MetadataUpdateCommand {

        @Parameter(names = { "--group" }, required = true, description = "ExifTool group of the metadata entry, e.g. XMP-dc")
        public String group;
        @Parameter(names = { "--key" }, required = true, description = "Metadata key inside the group, e.g. Creator")
        public String key;
        @Parameter(names = { "--value" }, required = true, description = "Current value that selects the files")
        public String value;
        @Parameter(names = { "--new-value" }, description = "New value written into the files")
        public String newValue;
        @Parameter(names = { "--remove" }, description = "Remove the entry from the files instead of writing a new value")
        public boolean remove;
        @Parameter(names = "--monitor", description = "Open the full-screen task monitor after submission")
        public boolean monitor;
    }

    @Parameters(commandDescription = "Trigger backend file scan")
    public static class ScanCommand {

        @Parameter(names = { "-o", "--original-directory" }, description = "Original directory path")
        public String originalDirectory;
        @Parameter(names = { "-e", "--export-directory" }, description = "Export directory path")
        public String exportDirectory;
        @Parameter(names = "--monitor", description = "Open the full-screen task monitor after submission")
        public boolean monitor;
    }
}
