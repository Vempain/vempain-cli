package fi.poltsi.vempain.cli.admin;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;

import fi.poltsi.vempain.cli.core.HttpTransport;
import fi.poltsi.vempain.cli.core.OutputFormatter;
import fi.poltsi.vempain.cli.core.SessionStore;
import fi.poltsi.vempain.cli.core.TaskClient;
import fi.poltsi.vempain.cli.core.TaskMonitor;

public class AdminCommands {

    private final HttpTransport transport;
    private final PrintStream out;
    private final OutputFormatter formatter;
    private final TaskMonitor taskMonitor;

    public AdminCommands(HttpTransport transport, PrintStream out) {
        this(transport, out, new TaskMonitor(new TaskClient(transport), out));
    }

    public AdminCommands(HttpTransport transport, PrintStream out, TaskMonitor taskMonitor) {
        this.transport = transport;
        this.out = out;
        this.formatter = new OutputFormatter(out);
        this.taskMonitor = taskMonitor;
    }

    public void listGroups(AdminListGroupsCommand command, SessionStore.Session session) throws Exception {
        var details = command.details == null ? "FULL" : command.details.toUpperCase(Locale.ROOT);

        var request = new JSONObject().put("page", command.page == null ? 0 : command.page)
            .put("size", command.size == null ? 25 : command.size)
            .put("sort_by", "short_name")
            .put("direction", "ASC")
            .put("case_sensitive", false);
        if (command.search != null
            && !command.search.isBlank()) {
            request.put("search", command.search);
        }
        if (command.modifiedAfterPublish) {
            // Only the galleries whose website copy is outdated: released once and modified after that release
            request.put("modified_after_publish", true);
        }
        var response = transport.postJson(session, "/content-management/galleries/paged-list", request);

        var items = response.optJSONArray("content");
        if (items == null) {
            items = response.optJSONArray("items");
        }
        if (items == null
            || items.isEmpty()) {
            out.println("No admin groups found.");
            return;
        }

        // published is the time the gallery was last released to the website; null (JSON) when it never was
        out.printf("%-8s %-36s %-60s %s%n", "id", "short_name", "description", "published");
        out.println("-".repeat(124));
        for (int i = 0; i < items.length(); i++) {
            var item = items.getJSONObject(i);
            out.printf("%-8d %-36s %-60s %s%n", item.optLong("id", 0L), formatter.truncate(item.optString("short_name", ""), 36),
                    formatter.truncate(item.optString("description", ""), 60), publishedColumn(item));
        }

        if (response.has("pageNumber")
            || response.has("totalPages")
            || response.has("totalElements")) {
            out.printf("%nPage %d/%d, total elements: %d%n", response.optInt("pageNumber", 0), response.optInt("totalPages", 0),
                    response.optLong("totalElements", 0L));
        }
    }

    /**
     * The release time of a listed item for the table: the ISO timestamp the backend sent, trimmed to the minute, or {@code never} when the item has not been
     * released to the website (the backend sends {@code null}).
     */
    public static String publishedColumn(JSONObject item) {
        if (item.isNull("published")) {
            return "never";
        }
        var published = item.optString("published", "");
        if (published.isBlank()) {
            return "never";
        }
        return published.length() > 16 ? published.substring(0, 16)
            .replace('T', ' ') : published;
    }

    public void publishGroup(AdminPublishGroupCommand command, SessionStore.Session session) throws Exception {
        var request = new JSONObject().put("id", command.id);
        if (command.message != null
            && !command.message.isBlank()) {
            request.put("publish_message", command.message);
        }
        if (command.publishDatetime != null
            && !command.publishDatetime.isBlank()) {
            request.put("publish_schedule", true);
            request.put("publish_date_time", command.publishDatetime);
        }
        var response = transport.patchJson(session, "/content-management/galleries/publish", request);
        out.println(response.toString(2));
        monitor(response, session, command.monitor);
    }

    public void publishGroups(AdminPublishGroupsCommand command, SessionStore.Session session) throws Exception {
        var ids = new JSONArray();
        for (var rawId : command.ids.split(",")) {
            var trimmed = rawId.trim();
            if (!trimmed.isBlank()) {
                ids.put(Long.parseLong(trimmed));
            }
        }
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("Provide at least one gallery id with --ids");
        }
        var request = new JSONObject().put("gallery_ids", ids);
        var response = transport.postJson(session, "/content-management/galleries/publish-selected", request);
        out.println(response.toString(2));
        monitor(response, session, command.monitor);
    }

    public void publishSiteFiles(AdminPublishSiteFilesCommand command, SessionStore.Session session) throws Exception {
        var ids = readIds(command.idsFile, "site file ID");
        var request = new JSONObject().put("file_ids", new JSONArray(ids));
        var response = transport.postJson(session, "/content-management/file/site-files/publish-selected", request);
        out.printf("Submitted %d site file IDs for publishing.%n", ids.size());
        out.println(response.toString(2));
        monitor(response, session, command.monitor);
    }

    private Set<Long> readIds(String idsFile, String label) throws Exception {
        var ids = new LinkedHashSet<Long>();
        int lineNumber = 0;
        try (var lines = Files.lines(Path.of(idsFile), StandardCharsets.UTF_8)) {
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
                    throw new IllegalArgumentException("Invalid " + label + " at " + idsFile + ":" + lineNumber + ": " + value);
                }
            }
        }
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("The file contains no positive " + label + "s: " + idsFile);
        }
        return ids;
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

    @Parameters(commandDescription = "Admin backend commands")
    public static class AdminCommand {
    }

    @Parameters(commandDescription = "List admin groups (galleries)")
    public static class AdminListGroupsCommand {

        @Parameter(names = { "--details" }, description = "Response details level BASIC|FULL")
        public String details = "FULL";
        @Parameter(names = { "--search" }, description = "Search term")
        public String search;
        @Parameter(names = { "--page" }, description = "Page number (0-based)")
        public Integer page;
        @Parameter(names = { "--size" }, description = "Page size")
        public Integer size;
        @Parameter(names = { "--modified-after-publish" }, description = """
                List only the galleries modified after their last release to the website (both timestamps set, release older than the modification)
                """)
        public boolean modifiedAfterPublish = false;
    }

    @Parameters(commandDescription = "Publish a single admin group (gallery)")
    public static class AdminPublishGroupCommand {

        @Parameter(names = { "--id" }, required = true, description = "Gallery ID")
        public long id;
        @Parameter(names = { "--message" }, description = "Publish message")
        public String message;
        @Parameter(names = { "--publish-datetime" }, description = "Publish datetime in ISO-8601")
        public String publishDatetime;
        @Parameter(names = "--monitor", description = "Open the full-screen task monitor after submission")
        public boolean monitor;
    }

    @Parameters(commandDescription = "Publish selected admin groups (galleries)")
    public static class AdminPublishGroupsCommand {

        @Parameter(names = { "--ids" }, required = true, description = "Comma-separated list of gallery IDs")
        public String ids;
        @Parameter(names = "--monitor", description = "Open the full-screen task monitor after submission")
        public boolean monitor;
    }

    @Parameters(commandDescription = "Publish site files listed in a UTF-8 file, one site file ID per line")
    public static class AdminPublishSiteFilesCommand {

        @Parameter(names = { "--ids-file" }, required = true, description = "File containing one site file ID per line")
        public String idsFile;
        @Parameter(names = "--monitor", description = "Open the full-screen task monitor after submission")
        public boolean monitor;
    }
}
