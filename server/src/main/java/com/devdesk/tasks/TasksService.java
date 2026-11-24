package com.devdesk.tasks;

import com.devdesk.HttpFetch;
import com.devdesk.calendar.CalendarException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class TasksService {
    private static final String API = "https://tasks.googleapis.com/tasks/v1";
    private static final String LISTS = API + "/users/@me/lists";
    private static final List<String> HORIZONS = List.of("TODAY", "WEEK", "MONTH", "YEAR");
    public record Task(String id, String title, boolean completed, String planKey) {}
    public record ListView(String id, String title, String horizon, List<Task> tasks) {}
    public record Board(boolean connected, boolean writeEnabled, List<ListView> lists) {}
    @FunctionalInterface
    interface Transport {
        HttpFetch.Response exchange(HttpRequest request, int maxBytes) throws IOException;
    }

    private final ObjectMapper json;
    private final String clientId, clientSecret, refreshToken;
    private final Transport transport;
    private final Clock clock;
    private String accessToken = "";
    private Instant expiresAt = Instant.EPOCH;
    private volatile boolean writeEnabled = true;

    @Autowired
    TasksService(ObjectMapper json, @Value("${devdesk.google.client-id:}") String clientId,
            @Value("${devdesk.google.client-secret:}") String clientSecret,
            @Value("${devdesk.google.tasks-refresh-token:}") String refreshToken) {
        this(json, clientId, clientSecret, refreshToken, HttpFetch::exchange, Clock.systemUTC());
    }

    TasksService(ObjectMapper json, String clientId, String clientSecret, String refreshToken,
            Transport transport, Clock clock) {
        this.json = json;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.refreshToken = refreshToken;
        this.transport = transport;
        this.clock = clock;
    }

    public Board board() {
        if (!configured()) return new Board(false, false, List.of());
        var lists = listsFor(HORIZONS);
        return new Board(true, writeEnabled, lists);
    }

    public List<ListView> todayLists() {
        return configured() ? listsFor(List.of("TODAY")) : List.of();
    }

    private List<ListView> listsFor(List<String> horizons) {
        var lists = pageItems(LISTS + "?maxResults=100&fields=items(id,title),nextPageToken");
        var board = new ArrayList<ListView>();
        for (String horizon : horizons) {
            for (var list : lists) {
                String title = list.path("title").asText();
                if (!horizon.equals(horizon(title))) continue;
                String id = list.path("id").asText();
                var tasks = pageItems(taskList(id) + "?maxResults=100&showCompleted=true&showHidden=true"
                    + "&fields=items(id,title,status,hidden),nextPageToken").stream()
                    .filter(task -> !task.path("hidden").asBoolean() || "completed".equals(task.path("status").asText()))
                    .map(task -> taskView(id, task)).toList();
                board.add(new ListView(id, title, horizon, tasks));
            }
        }
        return List.copyOf(board);
    }

    public Task add(String list, String title) {
        String trimmed = title == null ? "" : title.strip();
        if (trimmed.isEmpty() || trimmed.length() > 300 || trimmed.chars().anyMatch(c -> c < 32))
            throw new CalendarException(400, "INVALID_TASK", "할 일 제목을 1~300자로 입력해 주세요.");
        requireBoardList(list, false);
        return taskView(list, request("POST", taskList(list), Map.of("title", trimmed)));
    }

    public Task complete(String list, String id, Boolean completed) {
        if (completed == null) throw new CalendarException(400, "INVALID_TASK", "완료 여부를 선택해 주세요.");
        requireBoardList(list, false);
        return taskView(list, request("PATCH", taskList(list) + "/" + segment(id),
            Map.of("status", completed ? "completed" : "needsAction")));
    }

    public Task todayTask(String list, String id) {
        requireBoardList(list, true);
        JsonNode task = request("GET", taskList(list) + "/" + segment(id), null);
        if (task.path("deleted").asBoolean() || task.path("hidden").asBoolean())
            throw new CalendarException(404, "TASK_MISSING", "목록에서 할 일을 다시 확인해 주세요.");
        if (!List.of("needsAction", "completed").contains(task.path("status").asText()))
            throw new CalendarException(502, "TASK_STATUS_UNKNOWN", "Google Tasks의 완료 상태를 확인하지 못했습니다.");
        return taskView(list, task);
    }

    private void requireBoardList(String list, boolean todayOnly) {
        String title = request("GET", LISTS + "/" + segment(list) + "?fields=title", null).path("title").asText();
        String horizon = horizon(title);
        if (!(todayOnly ? "TODAY".equals(horizon) : HORIZONS.contains(horizon)))
            throw new CalendarException(400, "INVALID_TASK_LIST", todayOnly ? "TODAY 목록의 할 일에 시간을 배정할 수 있어요." : "DevDesk에 표시된 목록을 선택해 주세요.");
    }

    private static String horizon(String title) {
        return switch (title.strip().toUpperCase(Locale.ROOT).replaceFirst("[^A-Z]+$", "")) {
            case "TODAY" -> "TODAY";
            case "WEEK", "WEEKLY" -> "WEEK";
            case "MONTH", "MONTHLY" -> "MONTH";
            case "YEAR", "YEARLY" -> "YEAR";
            default -> "";
        };
    }

    private static Task taskView(String list, JsonNode task) {
        String id = task.path("id").asText();
        segment(id);
        String title = task.path("title").asText().strip();
        return new Task(id, title.isBlank() ? "제목 없는 할 일" : title,
            "completed".equals(task.path("status").asText()), planKey(list, id));
    }

    private static String planKey(String list, String id) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((list + "\n" + id).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private List<JsonNode> pageItems(String url) {
        var items = new ArrayList<JsonNode>();
        String next = "";
        for (int page = 0; page < 10; page++) {
            var response = request("GET", next.isBlank() ? url : url + "&pageToken=" + encode(next), null);
            response.path("items").forEach(items::add);
            next = response.path("nextPageToken").asText("");
            if (next.isBlank()) return items;
        }
        throw new CalendarException(502, "TASK_LIST_TOO_LARGE", "목록이 많아 한 번에 표시할 수 없습니다. Google Tasks에서 목록을 확인해 주세요.");
    }

    private JsonNode request(String method, String url, Map<String, String> body) {
        try {
            String bearer = token();
            if (body != null && !writeEnabled)
                throw new CalendarException(403, "TASKS_WRITE_SCOPE_REQUIRED", "할 일 관리 권한으로 Google Tasks에 다시 연결해 주세요.");
            var request = HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + bearer);
            if (body != null) request.header("Content-Type", "application/json");
            var response = transport.exchange(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), 524288);
            if (response.status() / 100 != 2) throw taskError(response);
            var data = json.readTree(response.body());
            if (data == null || !data.isObject()) throw new IllegalStateException("Invalid Tasks response");
            return data;
        } catch (CalendarException safe) {
            throw safe;
        } catch (Exception unavailable) {
            throw new CalendarException(502, "TASKS_UNAVAILABLE", "Google Tasks 결과를 확인하지 못했습니다. 목록을 새로 불러와 확인해 주세요.");
        }
    }

    private CalendarException taskError(HttpFetch.Response response) {
        if (response.status() == 403) {
            try {
                var error = json.readTree(response.body()).path("error");
                for (var item : error.path("errors")) if ("accessNotConfigured".equals(item.path("reason").asText()))
                    return new CalendarException(403, "TASKS_API_DISABLED", "Google Cloud 프로젝트에서 Google Tasks API를 사용 설정해 주세요.");
                for (var item : error.path("details")) if ("SERVICE_DISABLED".equals(item.path("reason").asText()))
                    return new CalendarException(403, "TASKS_API_DISABLED", "Google Cloud 프로젝트에서 Google Tasks API를 사용 설정해 주세요.");
            } catch (Exception ignored) { }
            return new CalendarException(403, "TASKS_FORBIDDEN", "Google Tasks 접근이 거부됐습니다. Tasks API 설정과 할 일 관리 권한을 확인해 주세요.");
        }
        return switch (response.status()) {
            case 401 -> new CalendarException(424, "TASKS_REAUTH_REQUIRED", "Google Tasks에 다시 연결해 주세요.");
            case 404 -> new CalendarException(404, "TASK_MISSING", "할 일 또는 목록이 변경됐습니다. 목록을 다시 불러와 주세요.");
            case 429 -> new CalendarException(429, "TASKS_RATE_LIMIT", "Google Tasks 호출 한도에 도달했습니다. 잠시 뒤 조회해 주세요.");
            default -> new CalendarException(502, "TASKS_UNAVAILABLE", "Google Tasks 결과를 확인하지 못했습니다. 목록을 새로 불러와 확인해 주세요.");
        };
    }

    private boolean configured() {
        return !clientId.isBlank() && !clientSecret.isBlank() && !refreshToken.isBlank();
    }

    private synchronized String token() {
        if (clock.instant().isBefore(expiresAt)) return accessToken;
        if (!configured()) throw new CalendarException(424, "TASKS_NOT_CONNECTED", "Google Tasks 연결이 필요합니다.");
        try {
            String form = "grant_type=refresh_token&client_id=" + encode(clientId)
                + "&client_secret=" + encode(clientSecret) + "&refresh_token=" + encode(refreshToken);
            var response = transport.exchange(HttpRequest.newBuilder(URI.create("https://oauth2.googleapis.com/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), 65536);
            if (response.status() == 400 || response.status() == 401)
                throw new CalendarException(424, "TASKS_REAUTH_REQUIRED", "Google Tasks 권한을 다시 승인해 주세요.");
            if (response.status() != 200) throw taskError(response);
            var data = json.readTree(response.body());
            String token = data.path("access_token").asText();
            if (token.isBlank()) throw new IllegalStateException("Tasks access token missing");
            String scope = data.path("scope").asText();
            // A refresh may omit unchanged scopes. Retain Google's last explicit scope.
            if (!scope.isBlank())
                writeEnabled = List.of(scope.split("\\s+")).contains("https://www.googleapis.com/auth/tasks");
            accessToken = token;
            expiresAt = clock.instant().plusSeconds(Math.max(1, Math.min(86400, data.path("expires_in").asLong(3600)) - 60));
            return accessToken;
        } catch (CalendarException safe) {
            throw safe;
        } catch (Exception unavailable) {
            throw new CalendarException(502, "TASKS_UNAVAILABLE", "Google Tasks 연결을 갱신하지 못했습니다. 잠시 뒤 다시 시도해 주세요.");
        }
    }

    private static String taskList(String list) { return API + "/lists/" + segment(list) + "/tasks"; }
    private static String segment(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_=-]{1,250}"))
            throw new CalendarException(400, "INVALID_TASK", "할 일 목록을 다시 불러와 주세요.");
        return encode(id);
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
