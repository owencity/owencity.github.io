package com.devdesk.calendar;

import com.devdesk.HttpFetch;
import com.devdesk.news.FeedParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class CalendarService {
    public static final String CALENDAR_URL = "https://calendar.google.com/calendar/u/0/r/day";
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final ObjectMapper json;
    private final String clientId, clientSecret, refreshToken, calendarId;
    private final boolean writesEnabled;
    private final CalendarTransport transport;
    private final Clock clock;
    private final Object taskPlanLock = new Object();
    private String accessToken = "";
    private Instant expiresAt = Instant.EPOCH;
    private volatile Boolean tokenAllowsWrites;

    @Autowired
    public CalendarService(ObjectMapper json,
            @Value("${devdesk.google.client-id:}") String clientId,
            @Value("${devdesk.google.client-secret:}") String clientSecret,
            @Value("${devdesk.google.refresh-token:}") String refreshToken,
            @Value("${devdesk.google.calendar-id:primary}") String calendarId,
            @Value("${devdesk.google.write-enabled:false}") boolean writesEnabled) {
        this(json, clientId, clientSecret, refreshToken, calendarId, writesEnabled, HttpFetch::exchange, Clock.systemUTC());
    }

    public CalendarService(ObjectMapper json, String clientId, String clientSecret, String refreshToken, String calendarId) {
        this(json, clientId, clientSecret, refreshToken, calendarId, false, HttpFetch::exchange, Clock.systemUTC());
    }

    CalendarService(ObjectMapper json, String clientId, String clientSecret, String refreshToken,
            String calendarId, boolean writesEnabled, CalendarTransport transport, Clock clock) {
        this.json = json; this.clientId = clientId; this.clientSecret = clientSecret; this.refreshToken = refreshToken;
        this.calendarId = calendarId; this.writesEnabled = writesEnabled; this.transport = transport; this.clock = clock;
    }

    public boolean configured() { return !clientId.isBlank() && !clientSecret.isBlank() && !refreshToken.isBlank(); }
    public boolean writeEnabled() { return configured() && writesEnabled && Boolean.TRUE.equals(tokenAllowsWrites); }

    public CalendarToday today() {
        CalendarDay day = day(LocalDate.now(clock.withZone(SEOUL)));
        return new CalendarToday(day.connected(), day.events(), day.calendarUrl(), day.error());
    }

    public CalendarDay day(LocalDate day) {
        String calendarUrl = CALENDAR_URL + "/" + day.getYear() + "/" + day.getMonthValue() + "/" + day.getDayOfMonth();
        if (!configured()) return new CalendarDay(false, List.of(), calendarUrl, null, false, day.toString());
        try {
            String start = day.atStartOfDay(SEOUL).toOffsetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            String end = day.plusDays(1).atStartOfDay(SEOUL).toOffsetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            List<JsonNode> items = new ArrayList<>();
            String pageToken = "";
            int pages = 0;
            boolean owner = false;
            do {
                String url = base() + "?singleEvents=true&orderBy=startTime&showDeleted=false&maxResults=250&timeZone=Asia%2FSeoul"
                    + "&timeMin=" + encode(start) + "&timeMax=" + encode(end);
                if (!pageToken.isBlank()) url += "&pageToken=" + encode(pageToken);
                JsonNode data = read(call("GET", url, null, null), false);
                if (pages == 0) owner = "owner".equals(data.path("accessRole").asText());
                for (JsonNode item : data.path("items")) if (!"cancelled".equals(item.path("status").asText())) items.add(item);
                pageToken = data.path("nextPageToken").asText("");
                if (++pages >= 10 && !pageToken.isBlank()) throw new CalendarException(502, "CALENDAR_TOO_LARGE", "일정이 너무 많아 Google Calendar에서 확인해 주세요.");
            } while (!pageToken.isBlank());
            boolean writable = writeEnabled() && owner;
            List<CalendarEvent> events = items.stream().map(item -> event(item, writable)).toList();
            return new CalendarDay(true, events, calendarUrl, null, writable, day.toString());
        } catch (CalendarException failure) {
            return new CalendarDay(false, List.of(), calendarUrl, failure.getMessage(), false, day.toString());
        }
    }

    public CalendarEvent create(CalendarWriteRequest input) {
        var body = CalendarPolicy.payload(json, input, true);
        requireOwner();
        return insert(body, CalendarPolicy.eventId(input.requestId()), "");
    }

    public CalendarEvent scheduleTodayTask(String taskKey, String title, LocalDate date,
            LocalTime start, LocalTime end, String requestId, String expectedId, String expectedEtag) {
        if (date == null || !date.equals(LocalDate.now(clock.withZone(SEOUL))))
            throw CalendarPolicy.bad("오늘 날짜의 할 일에만 시간을 배정할 수 있습니다. 목록을 새로고침해 주세요.");
        if (start == null || end == null || taskKey == null || !taskKey.matches("[a-f0-9]{64}"))
            throw CalendarPolicy.bad("할 일과 시작·종료 시간을 확인해 주세요.");
        var from = date.atTime(start).atZone(SEOUL).toOffsetDateTime();
        var to = date.atTime(end).atZone(SEOUL).toOffsetDateTime();
        if (!from.toInstant().isAfter(clock.instant()) || !to.isAfter(from))
            throw CalendarPolicy.bad("오늘의 남은 시간 중 시작과 종료를 순서대로 입력해 주세요.");
        String eventTitle = title.replaceAll("[\\r\\n]", " ").strip();
        if (eventTitle.length() > 300) {
            int limit = Character.isHighSurrogate(eventTitle.charAt(299)) ? 299 : 300;
            eventTitle = eventTitle.substring(0, limit);
        }
        var input = new CalendarWriteRequest(requestId, eventTitle, null, null, false,
            from.toString(), to.toString(), "POPUP", 0, "NONE", null);
        var body = CalendarPolicy.payload(json, input, true);
        String id = CalendarPolicy.eventId(requestId);
        if (expectedId != null || expectedEtag != null) {
            CalendarPolicy.id(expectedId);
            CalendarPolicy.etag(expectedEtag);
        }
        synchronized (taskPlanLock) {
            requireOwner();
            String link = encode("devdeskTask=" + taskKey);
            var existing = read(call("GET", base() + "?maxResults=2&singleEvents=true&showDeleted=false"
                + "&privateExtendedProperty=" + link
                + "&timeMin=" + encode(date.atStartOfDay(SEOUL).toOffsetDateTime().toString())
                + "&timeMax=" + encode(date.plusDays(1).atStartOfDay(SEOUL).toOffsetDateTime().toString()),
                null, null), false);
            var matches = existing.path("items");
            if (matches.size() > 1 || !existing.path("nextPageToken").asText().isBlank())
                throw new CalendarException(409, "TASK_PLAN_CONFLICT", "연결된 일정이 여러 개 있습니다. Google Calendar에서 확인해 주세요.");
            if (matches.isEmpty()) {
                if (expectedId != null) throw CalendarPolicy.conflict();
                body.put("visibility", "private");
                return insert(body, id, taskKey);
            }
            var current = matches.get(0);
            String eventId = current.path("id").asText();
            CalendarPolicy.id(eventId);
            if (!taskKey.equals(current.path("extendedProperties").path("private").path("devdeskTask").asText()))
                throw CalendarPolicy.conflict();
            if (expectedId == null) {
                if (id.equals(eventId) && sameTime(current.path("start"), input.start(), false)
                        && sameTime(current.path("end"), input.end(), false)) return event(current, true);
                throw CalendarPolicy.conflict();
            }
            if (!expectedId.equals(eventId)) throw CalendarPolicy.conflict();
            CalendarPolicy.requireEditable(current, expectedEtag, null);
            body.remove(List.of("description", "location"));
            if (current.path("start").hasNonNull("date")) {
                ((ObjectNode) body.get("start")).putNull("date");
                ((ObjectNode) body.get("end")).putNull("date");
            }
            return event(read(call("PATCH", base() + "/" + encode(eventId) + "?sendUpdates=none", body, expectedEtag), true), true);
        }
    }

    private CalendarEvent insert(ObjectNode body, String id, String taskKey) {
        String fingerprint = hash(body.toString());
        body.put("id", id);
        var privateProperties = body.putObject("extendedProperties").putObject("private");
        privateProperties.put("devdeskRequest", id).put("devdeskFingerprint", fingerprint);
        if (!taskKey.isBlank()) privateProperties.put("devdeskTask", taskKey);
        HttpFetch.Response response = call("POST", base() + "?sendUpdates=none", body, null);
        if (response.status() == 409) {
            JsonNode existing = getRaw(id);
            JsonNode properties = existing.path("extendedProperties").path("private");
            if (!id.equals(properties.path("devdeskRequest").asText())
                    || !fingerprint.equals(properties.path("devdeskFingerprint").asText())
                    || !taskKey.equals(properties.path("devdeskTask").asText("")))
                throw new CalendarException(409, "REQUEST_REUSED", "같은 등록 요청으로 다른 내용을 보낼 수 없습니다. 기존 일정부터 확인하세요.");
            if (!taskKey.isBlank() && (!sameTime(existing.path("start"), body.path("start").path("dateTime").asText(), false)
                    || !sameTime(existing.path("end"), body.path("end").path("dateTime").asText(), false)))
                throw CalendarPolicy.conflict();
            return event(existing, true);
        }
        return event(read(response, true), true);
    }

    public CalendarEvent update(String id, CalendarWriteRequest input, String etag, String scope) {
        CalendarPolicy.id(id); CalendarPolicy.etag(etag);
        ObjectNode body = CalendarPolicy.payload(json, input, false);
        requireOwner();
        JsonNode existing = getRaw(id);
        CalendarPolicy.requireEditable(existing, etag, scope);
        // Google PATCH preserves fields outside this editor, including reminders when mode is KEEP.
        // Clear the opposite start/end representation when changing between all-day and timed.
        if (Boolean.TRUE.equals(input.allDay())) {
            ((ObjectNode) body.get("start")).putNull("dateTime").putNull("timeZone");
            ((ObjectNode) body.get("end")).putNull("dateTime").putNull("timeZone");
        } else {
            ((ObjectNode) body.get("start")).putNull("date");
            ((ObjectNode) body.get("end")).putNull("date");
        }
        if (sameTime(existing.path("start"), input.start(), input.allDay())
                && sameTime(existing.path("end"), input.end(), input.allDay())) {
            body.remove("start"); body.remove("end");
        }
        return event(read(call("PATCH", base() + "/" + encode(id) + "?sendUpdates=none", body, etag), true), true);
    }

    public void delete(String id, String etag, String scope) {
        CalendarPolicy.id(id); CalendarPolicy.etag(etag);
        requireOwner();
        JsonNode existing = getRaw(id);
        CalendarPolicy.requireEditable(existing, etag, scope);
        HttpFetch.Response response = call("DELETE", base() + "/" + encode(id) + "?sendUpdates=none", null, etag);
        if (response.status() / 100 != 2) throw upstream(response, true);
    }

    private void requireOwner() {
        if (!configured() || !writesEnabled) throw new CalendarException(403, "WRITE_DISABLED", "일정 쓰기가 꺼져 있습니다. 서버 설정과 Google 쓰기 권한 연결을 확인하세요.");
        token();
        if (!writeEnabled()) throw new CalendarException(403, "GOOGLE_WRITE_SCOPE_REQUIRED", "Google 쓰기 권한을 확인할 수 없습니다. 쓰기 권한으로 다시 연결해 주세요.");
        JsonNode data = read(call("GET", base() + "?maxResults=1&fields=accessRole", null, null), false);
        if (!"owner".equals(data.path("accessRole").asText()))
            throw new CalendarException(403, "CALENDAR_NOT_OWNED", "본인이 소유한 캘린더에서만 일정을 변경할 수 있습니다.");
    }

    private JsonNode getRaw(String id) { return read(call("GET", base() + "/" + encode(id), null, null), false); }

    private HttpFetch.Response call(String method, String url, ObjectNode body, String etag) {
        boolean mutation = !"GET".equals(method);
        try {
            var request = HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + token())
                .header("Accept", "application/json");
            if (etag != null) request.header("If-Match", etag);
            if (body != null) request.header("Content-Type", "application/json");
            request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8));
            return transport.exchange(request.build(), 2_097_152);
        } catch (CalendarException safe) { throw safe; }
        catch (Exception failure) {
            if (mutation) throw new CalendarException(502, "WRITE_OUTCOME_UNKNOWN",
                "Google 처리 결과를 확인하지 못했습니다. 새로 조회하고, 등록 재시도 시 같은 요청 ID를 유지해 주세요.");
            throw new CalendarException(502, "CALENDAR_UNAVAILABLE", "일정을 불러오지 못했습니다. 연결 설정과 네트워크를 확인하세요.");
        }
    }

    private JsonNode read(HttpFetch.Response response, boolean mutation) {
        if (response.status() / 100 != 2) throw upstream(response, mutation);
        try {
            JsonNode value = json.readTree(response.body());
            if (value == null || !value.isObject()) throw new IOException("Invalid upstream document");
            return value;
        } catch (IOException invalid) {
            throw new CalendarException(502, mutation ? "WRITE_OUTCOME_UNKNOWN" : "INVALID_CALENDAR_RESPONSE",
                mutation ? "Google 응답을 확인하지 못했습니다. 일정을 새로 조회해 처리 여부를 확인하세요."
                    : "Google 일정 응답을 읽지 못했습니다. 잠시 후 다시 시도하세요.");
        }
    }

    private CalendarException upstream(HttpFetch.Response response, boolean mutation) {
        int status = response.status();
        if (status == 403) {
            // Only recognized reason codes cross this boundary; never expose upstream messages or credentials.
            try {
                JsonNode error = json.readTree(response.body()).path("error");
                var reasons = new java.util.HashSet<String>();
                for (JsonNode item : error.path("errors")) reasons.add(item.path("reason").asText());
                for (JsonNode item : error.path("details")) {
                    if ("type.googleapis.com/google.rpc.ErrorInfo".equals(item.path("@type").asText())
                            && "googleapis.com".equals(item.path("domain").asText()))
                        reasons.add(item.path("reason").asText());
                }
                if (reasons.contains("accessNotConfigured") || reasons.contains("SERVICE_DISABLED"))
                    return new CalendarException(403, "GOOGLE_CALENDAR_API_DISABLED",
                        "OAuth 클라이언트가 속한 Google Cloud 프로젝트에서 Google Calendar API를 사용 설정해 주세요. 사용 설정 직후에는 잠시 뒤 다시 조회하세요.");
                if (reasons.contains("insufficientPermissions") || reasons.contains("ACCESS_TOKEN_SCOPE_INSUFFICIENT"))
                    return new CalendarException(403, "GOOGLE_SCOPE_INSUFFICIENT",
                        "현재 Google 토큰에 필요한 일정 권한이 없습니다. 같은 OAuth 클라이언트로 일정 편집 권한을 다시 승인해 주세요.");
                if (reasons.stream().anyMatch(reason -> java.util.Set.of("rateLimitExceeded", "userRateLimitExceeded",
                        "quotaExceeded", "dailyLimitExceeded", "RATE_LIMIT_EXCEEDED", "QUOTA_EXCEEDED").contains(reason)))
                    return new CalendarException(429, "GOOGLE_RATE_LIMIT", "Google Calendar 호출 한도에 도달했습니다. 잠시 후 다시 조회하세요.");
                if (reasons.contains("domainPolicy"))
                    return new CalendarException(403, "GOOGLE_DOMAIN_POLICY", "Google Workspace 조직 정책으로 접근이 제한됐습니다. 조직의 앱 접근 설정을 확인해 주세요.");
            } catch (Exception ignored) { /* Preserve the safe generic failure for unsupported error bodies. */ }
        }
        return switch (status) {
            case 400 -> new CalendarException(400, "GOOGLE_REJECTED_EVENT", "Google에서 일정 입력을 받아들이지 못했습니다. 날짜와 알림 설정을 확인하세요.");
            case 401 -> new CalendarException(424, "GOOGLE_REAUTH_REQUIRED", "Google 연결이 만료됐습니다. 다시 연결해 주세요.");
            case 403 -> new CalendarException(403, "GOOGLE_PERMISSION_DENIED", "Google Calendar 접근이 거부됐습니다. API 사용 설정과 계정의 캘린더 접근 권한을 확인해 주세요.");
            case 404, 410 -> new CalendarException(404, "EVENT_NOT_FOUND", "일정이 없거나 이미 삭제됐습니다. 목록을 새로 조회하세요.");
            case 409 -> new CalendarException(409, "GOOGLE_CONFLICT", "일정 등록이 충돌했습니다. 기존 일정을 확인하세요.");
            case 412 -> CalendarPolicy.conflict();
            case 429 -> new CalendarException(429, "GOOGLE_RATE_LIMIT", "Google 호출 한도에 도달했습니다. 잠시 후 다시 시도하세요.");
            default -> new CalendarException(502, mutation ? "WRITE_OUTCOME_UNKNOWN" : "CALENDAR_UNAVAILABLE",
                mutation ? "Google 처리 결과를 확인하지 못했습니다. 일정을 새로 조회해 확인하세요."
                    : "Google 일정 서비스에 연결하지 못했습니다.");
        };
    }

    static CalendarEvent event(JsonNode item) { return event(item, false); }

    static CalendarEvent event(JsonNode item, boolean writeAllowed) {
        boolean allDay = item.path("start").hasNonNull("date");
        String start = item.path("start").path(allDay ? "date" : "dateTime").asText();
        String end = item.path("end").path(allDay ? "date" : "dateTime").asText();
        String url = item.path("htmlLink").asText();
        JsonNode reminders = item.path("reminders");
        String reminderMode = "DEFAULT"; Integer reminderMinutes = null;
        if (reminders.isObject() && !reminders.path("useDefault").asBoolean(false)) {
            JsonNode overrides = reminders.path("overrides");
            if (overrides.isMissingNode() || (overrides.isArray() && overrides.isEmpty())) reminderMode = "NONE";
            else if (overrides.isArray() && overrides.size() == 1 && "popup".equals(overrides.get(0).path("method").asText())
                    && overrides.get(0).path("minutes").canConvertToInt()) {
                reminderMode = "POPUP"; reminderMinutes = overrides.get(0).path("minutes").asInt();
            } else reminderMode = "OTHER";
        }
        return new CalendarEvent(item.path("id").asText(), item.path("summary").asText("제목 없는 일정"),
            start, end, allDay, FeedParser.safeUrl(url) ? url : CALENDAR_URL,
            item.path("description").asText(""), item.path("location").asText(""), item.path("etag").asText(""),
            item.path("recurringEventId").asText(""), CalendarPolicy.editable(item, writeAllowed), reminderMode, reminderMinutes,
            item.path("extendedProperties").path("private").path("devdeskTask").asText(""));
    }

    private synchronized String token() {
        if (!accessToken.isBlank() && clock.instant().isBefore(expiresAt)) return accessToken;
        if (!configured()) throw new CalendarException(503, "CALENDAR_NOT_CONFIGURED", "Google Calendar 연결이 필요합니다.");
        String form = "grant_type=refresh_token&client_id=" + encode(clientId) + "&client_secret=" + encode(clientSecret)
            + "&refresh_token=" + encode(refreshToken);
        try {
            HttpFetch.Response response = transport.exchange(HttpRequest.newBuilder(URI.create("https://oauth2.googleapis.com/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8)).build(), 65536);
            if (response.status() == 429) throw new CalendarException(429, "GOOGLE_RATE_LIMIT", "Google 연결 요청 한도에 도달했습니다. 잠시 후 다시 시도하세요.");
            if (response.status() >= 500) throw new CalendarException(502, "CALENDAR_UNAVAILABLE", "Google 연결 서비스가 일시적으로 응답하지 않습니다.");
            if (response.status() / 100 != 2) throw new CalendarException(424, "GOOGLE_REAUTH_REQUIRED", "Google 연결 권한을 확인하고 다시 승인해 주세요.");
            JsonNode data = json.readTree(response.body());
            String value = data.path("access_token").asText("");
            if (value.isBlank()) throw new IOException("OAuth token missing");
            String scope = data.path("scope").asText("");
            tokenAllowsWrites = false;
            if (!scope.isBlank()) {
                tokenAllowsWrites = java.util.Arrays.stream(scope.split("\\s+"))
                    .anyMatch(s -> s.equals("https://www.googleapis.com/auth/calendar.events.owned")
                        || s.equals("https://www.googleapis.com/auth/calendar.events")
                        || s.equals("https://www.googleapis.com/auth/calendar"));
            }
            accessToken = value;
            expiresAt = clock.instant().plusSeconds(Math.max(1, Math.min(86400, data.path("expires_in").asLong(3600)) - 60));
            return accessToken;
        } catch (CalendarException safe) { throw safe; }
        catch (Exception invalid) {
            throw new CalendarException(502, "CALENDAR_UNAVAILABLE", "Google 연결을 갱신하지 못했습니다. 네트워크를 확인하고 잠시 후 다시 시도하세요.");
        }
    }

    private static boolean sameTime(JsonNode original, String value, boolean allDay) {
        try {
            return allDay ? original.hasNonNull("date") && LocalDate.parse(original.path("date").asText()).equals(LocalDate.parse(value))
                : !original.hasNonNull("date") && OffsetDateTime.parse(original.path("dateTime").asText()).toInstant()
                    .equals(OffsetDateTime.parse(value).toInstant());
        } catch (RuntimeException invalid) { return false; }
    }

    private String base() { return "https://www.googleapis.com/calendar/v3/calendars/" + encode(calendarId) + "/events"; }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
