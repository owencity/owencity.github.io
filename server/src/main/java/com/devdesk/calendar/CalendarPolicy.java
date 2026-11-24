package com.devdesk.calendar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.DayOfWeek;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.format.DateTimeFormatter;
import java.util.Set;

final class CalendarPolicy {
    private CalendarPolicy() {}

    static String eventId(String requestId) {
        if (requestId == null || !requestId.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw bad("일정 등록 요청 ID가 올바르지 않습니다.");
        return "d" + requestId.toLowerCase(java.util.Locale.ROOT).replace("-", "");
    }

    static void id(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{5,1024}")) throw bad("일정 ID가 올바르지 않습니다.");
    }

    static void etag(String etag) {
        if (etag == null || etag.length() < 3 || etag.length() > 300 || !etag.startsWith("\"")
                || !etag.endsWith("\"") || etag.substring(1, etag.length() - 1).chars()
                    .anyMatch(c -> c < 33 || c > 126 || c == '"'))
            throw new CalendarException(428, "ETAG_REQUIRED", "일정을 새로 조회한 뒤 수정하거나 삭제해 주세요.");
    }

    static boolean editable(JsonNode event, boolean writeEnabled) {
        return writeEnabled && event.path("organizer").path("self").asBoolean(false)
            && (!event.has("attendees") || (event.path("attendees").isArray() && event.path("attendees").isEmpty()))
            && "default".equals(event.path("eventType").asText("default"))
            && (!event.has("recurrence") || event.path("recurrence").isEmpty())
            && !event.path("locked").asBoolean(false)
            && !"cancelled".equals(event.path("status").asText());
    }

    static void requireEditable(JsonNode event, String expectedEtag, String scope) {
        if (!editable(event, true)) throw new CalendarException(403, "EVENT_NOT_EDITABLE",
            "참석자가 없는 본인 소유의 일반 일정만 변경할 수 있습니다. 반복 일정 전체는 Google Calendar에서 변경하세요.");
        if (!event.path("recurringEventId").asText("").isBlank() && !"occurrence".equals(scope))
            throw bad("반복 일정은 선택한 이번 일정만 변경할 수 있습니다.");
        if (!expectedEtag.equals(event.path("etag").asText())) throw conflict();
    }

    static ObjectNode payload(ObjectMapper json, CalendarWriteRequest input, boolean creating) {
        if (input == null || input.allDay() == null) throw bad("일정 날짜와 시간을 입력하세요.");
        String title = text(input.title(), 300, "제목");
        if (title.isBlank() || title.indexOf('\n') >= 0 || title.indexOf('\r') >= 0) throw bad("일정 제목을 입력하세요.");
        String description = text(input.description(), 20000, "설명");
        String location = text(input.location(), 1000, "장소");
        String start = text(input.start(), 80, "시작");
        String end = text(input.end(), 80, "종료");
        var result = json.createObjectNode();
        result.put("summary", title); result.put("description", description); result.put("location", location);
        try {
            if (input.allDay()) {
                LocalDate a = LocalDate.parse(start); LocalDate b = LocalDate.parse(end);
                if (!b.isAfter(a)) throw bad("종일 일정 종료일은 시작일 다음 날 이후여야 합니다.");
                result.putObject("start").put("date", a.toString());
                result.putObject("end").put("date", b.toString());
            } else {
                OffsetDateTime a = OffsetDateTime.parse(start); OffsetDateTime b = OffsetDateTime.parse(end);
                if (!b.toInstant().isAfter(a.toInstant())) throw bad("종료 시간은 시작 시간보다 늦어야 합니다.");
                result.putObject("start").put("dateTime", a.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)).put("timeZone", "Asia/Seoul");
                result.putObject("end").put("dateTime", b.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)).put("timeZone", "Asia/Seoul");
            }
        } catch (DateTimeParseException invalid) { throw bad("날짜 형식 또는 시간대가 올바르지 않습니다."); }

        String mode = input.reminderMode() == null ? (creating ? "DEFAULT" : "KEEP") : input.reminderMode();
        if (!Set.of("DEFAULT", "NONE", "POPUP", "KEEP").contains(mode) || (creating && "KEEP".equals(mode)))
            throw bad("알림 설정이 올바르지 않습니다.");
        if ("POPUP".equals(mode)) {
            if (input.reminderMinutes() == null || input.reminderMinutes() < 0 || input.reminderMinutes() > 40320)
                throw bad("알림은 시작 0분 전부터 4주 전까지 설정할 수 있습니다.");
            var reminders = result.putObject("reminders"); reminders.put("useDefault", false);
            reminders.putArray("overrides").addObject().put("method", "popup").put("minutes", input.reminderMinutes());
        } else if ("DEFAULT".equals(mode)) {
            var reminders = result.putObject("reminders"); reminders.put("useDefault", true); reminders.putArray("overrides");
        } else if ("NONE".equals(mode)) {
            var reminders = result.putObject("reminders"); reminders.put("useDefault", false); reminders.putArray("overrides");
        }

        String repeat = input.repeat() == null ? "NONE" : input.repeat();
        if (!creating && (input.requestId() != null || !"NONE".equals(repeat) || input.repeatCount() != null))
            throw bad("수정 시 반복 규칙을 바꿀 수 없습니다. 이번 일정만 수정해 주세요.");
        if (creating) {
            eventId(input.requestId());
            if (!Set.of("NONE", "DAILY", "WEEKDAYS", "WEEKLY").contains(repeat)) throw bad("반복 설정이 올바르지 않습니다.");
            if (!"NONE".equals(repeat)) {
                Integer count = input.repeatCount();
                if (count == null || count < 2 || count > 366) throw bad("반복 횟수는 2회부터 366회까지 설정하세요.");
                DayOfWeek weekday = input.allDay() ? LocalDate.parse(start).getDayOfWeek()
                    : OffsetDateTime.parse(start).atZoneSameInstant(ZoneId.of("Asia/Seoul")).getDayOfWeek();
                if ("WEEKDAYS".equals(repeat) && (weekday == DayOfWeek.SATURDAY || weekday == DayOfWeek.SUNDAY))
                    throw bad("평일 반복 일정은 한국 시간 기준 월요일부터 금요일 사이에 시작해야 합니다.");
                String rule = switch (repeat) {
                    case "DAILY" -> "FREQ=DAILY";
                    case "WEEKDAYS" -> "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR";
                    default -> "FREQ=WEEKLY";
                };
                result.putArray("recurrence").add("RRULE:" + rule + ";COUNT=" + count);
            } else if (input.repeatCount() != null) throw bad("반복하지 않는 일정에는 반복 횟수를 지정하지 마세요.");
        }
        return result;
    }

    private static String text(String value, int max, String name) {
        if (value == null) return "";
        if (value.length() > max || value.chars().anyMatch(c -> c < 32 && c != '\n' && c != '\r' && c != '\t'))
            throw bad(name + " 입력이 너무 길거나 올바르지 않습니다.");
        return value.strip();
    }
    static CalendarException bad(String message) { return new CalendarException(400, "INVALID_EVENT", message); }
    static CalendarException conflict() { return new CalendarException(412, "EVENT_CHANGED", "다른 곳에서 일정이 변경됐습니다. 새로 조회한 뒤 다시 시도하세요."); }
}
