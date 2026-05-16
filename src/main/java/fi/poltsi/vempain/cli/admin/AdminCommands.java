package fi.poltsi.vempain.cli.admin;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import fi.poltsi.vempain.cli.core.HttpTransport;
import fi.poltsi.vempain.cli.core.OutputFormatter;
import fi.poltsi.vempain.cli.core.SessionStore;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.PrintStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public class AdminCommands {

    private final HttpTransport transport;
    private final PrintStream out;
    private final OutputFormatter formatter;

    public AdminCommands(HttpTransport transport, PrintStream out) {
        this.transport = transport;
        this.out = out;
        this.formatter = new OutputFormatter(out);
    }

    public void listGroups(AdminListGroupsCommand command, SessionStore.Session session) throws Exception {
        var details = command.details == null ? "FULL" : command.details.toUpperCase(Locale.ROOT);

        JSONObject response;
        if (command.search != null || command.page != null || command.size != null) {
            var page = command.page == null ? 0 : command.page;
            var size = command.size == null ? 25 : command.size;
            var query = new StringBuilder("/content-management/galleries/search?page=")
                    .append(page)
                    .append("&size=")
                    .append(size);
            if (command.search != null && !command.search.isBlank()) {
                query.append("&search=").append(urlEncode(command.search));
            }
            response = transport.getJsonFlexible(session, query.toString());
        } else {
            response = transport.getJsonFlexible(session, "/content-management/galleries?details=" + urlEncode(details));
        }

        var items = response.optJSONArray("items");
        if (items == null || items.isEmpty()) {
            out.println("No admin groups found.");
            return;
        }

        out.printf("%-8s %-36s %-60s%n", "id", "short_name", "description");
        out.println("-".repeat(108));
        for (int i = 0; i < items.length(); i++) {
            var item = items.getJSONObject(i);
            out.printf("%-8d %-36s %-60s%n",
                    item.optLong("id", 0L),
                    formatter.truncate(item.optString("shortName", ""), 36),
                    formatter.truncate(item.optString("description", ""), 60));
        }

        if (response.has("pageNumber") || response.has("totalPages") || response.has("totalElements")) {
            out.printf("%nPage %d/%d, total elements: %d%n",
                    response.optInt("pageNumber", 0),
                    response.optInt("totalPages", 0),
                    response.optLong("totalElements", 0L));
        }
    }

    public void publishGroup(AdminPublishGroupCommand command, SessionStore.Session session) throws Exception {
        var request = new JSONObject().put("id", command.id);
        if (command.message != null && !command.message.isBlank()) {
            request.put("publishMessage", command.message);
        }
        if (command.publishDatetime != null && !command.publishDatetime.isBlank()) {
            request.put("publishSchedule", true);
            request.put("publishDateTime", command.publishDatetime);
        }
        var response = transport.patchJson(session, "/content-management/galleries/publish", request);
        out.println(response.toString(2));
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
        var request = new JSONObject().put("galleryIds", ids);
        var response = transport.postJson(session, "/content-management/galleries/publish-selected", request);
        out.println(response.toString(2));
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    @Parameters(commandDescription = "Admin backend commands")
    public static class AdminCommand {
    }

    @Parameters(commandDescription = "List admin groups (galleries)")
    public static class AdminListGroupsCommand {
        @Parameter(names = {"--details"}, description = "Response details level BASIC|FULL")
        public String details = "FULL";
        @Parameter(names = {"--search"}, description = "Search term")
        public String search;
        @Parameter(names = {"--page"}, description = "Page number (0-based)")
        public Integer page;
        @Parameter(names = {"--size"}, description = "Page size")
        public Integer size;
    }

    @Parameters(commandDescription = "Publish a single admin group (gallery)")
    public static class AdminPublishGroupCommand {
        @Parameter(names = {"--id"}, required = true, description = "Gallery ID")
        public long id;
        @Parameter(names = {"--message"}, description = "Publish message")
        public String message;
        @Parameter(names = {"--publish-datetime"}, description = "Publish datetime in ISO-8601")
        public String publishDatetime;
    }

    @Parameters(commandDescription = "Publish selected admin groups (galleries)")
    public static class AdminPublishGroupsCommand {
        @Parameter(names = {"--ids"}, required = true, description = "Comma-separated list of gallery IDs")
        public String ids;
    }
}

