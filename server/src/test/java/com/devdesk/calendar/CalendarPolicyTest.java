package com.devdesk.calendar;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CalendarPolicyTest {
    private final ObjectMapper json = new ObjectMapper();
    private static final String REQUEST = "11111111-2222-4333-8444-555555555555";

    private CalendarWriteRequest request(boolean allDay, String start, String end,
            String reminder, Integer minutes, String repeat, Integer count) {
        return new CalendarWriteRequest(REQUEST, "공부 일정", "내용", "집", allDay, start, end, reminder, minutes, repeat, count);
    }

    @Test void customIdsAreStableBase32HexAndMalformedRequestIdsAreRejected() {
        assertThat(CalendarPolicy.eventId(REQUEST)).isEqualTo("d11111111222243338444555555555555");
        assertThat(CalendarPolicy.eventId(REQUEST)).matches("[0-9a-v]{5,1024}");
        assertThatThrownBy(() -> CalendarPolicy.eventId("not-a-uuid")).isInstanceOf(CalendarException.class);
    }

    @Test void timedPayloadRetainsOffsetAndDefinesSeoulRecurrenceZone() {
        var body = CalendarPolicy.payload(json, request(false, "2026-11-23T09:00:00+09:00", "2026-11-23T10:00:00+09:00", "POPUP", 10, "DAILY", 5), true);
        assertThat(body.path("start").path("dateTime").asText()).isEqualTo("2026-11-23T09:00:00+09:00");
        assertThat(body.path("start").path("timeZone").asText()).isEqualTo("Asia/Seoul");
        assertThat(body.path("recurrence").get(0).asText()).isEqualTo("RRULE:FREQ=DAILY;COUNT=5");
        assertThat(body.path("reminders").path("overrides").get(0).path("minutes").asInt()).isEqualTo(10);
    }

    @Test void allDayEndIsExclusiveAndMustBeAfterStart() {
        var body = CalendarPolicy.payload(json, request(true, "2026-11-23", "2026-11-24", "NONE", null, "NONE", null), true);
        assertThat(body.path("end").path("date").asText()).isEqualTo("2026-11-24");
        assertThat(body.path("end").has("dateTime")).isFalse();
        assertThatThrownBy(() -> CalendarPolicy.payload(json, request(true, "2026-11-23", "2026-11-23", "NONE", null, "NONE", null), true))
            .isInstanceOf(CalendarException.class);
    }

    @Test void localTimesWithoutOffsetAndReversedInstantsAreRejected() {
        assertThatThrownBy(() -> CalendarPolicy.payload(json, request(false, "2026-11-23T09:00", "2026-11-23T10:00", "DEFAULT", null, "NONE", null), true))
            .isInstanceOf(CalendarException.class);
        assertThatThrownBy(() -> CalendarPolicy.payload(json, request(false, "2026-11-23T09:00:00Z", "2026-11-23T10:00:00+09:00", "DEFAULT", null, "NONE", null), true))
            .isInstanceOf(CalendarException.class);
    }

    @Test void defaultReminderClearsOldOverridesAndKeepOmitsReminderPatch() {
        var body = CalendarPolicy.payload(json, request(true, "2026-11-23", "2026-11-24", "DEFAULT", null, "NONE", null), true);
        assertThat(body.path("reminders").path("useDefault").asBoolean()).isTrue();
        assertThat(body.path("reminders").path("overrides").isEmpty()).isTrue();
        var update = new CalendarWriteRequest(null, "수정", "", "", true, "2026-11-23", "2026-11-24", "KEEP", null, null, null);
        assertThat(CalendarPolicy.payload(json, update, false).has("reminders")).isFalse();
    }

    @Test void popupRemindersEnforceGoogleRangeIncludingZero() {
        CalendarPolicy.payload(json, request(true, "2026-11-23", "2026-11-24", "POPUP", 0, "NONE", null), true);
        CalendarPolicy.payload(json, request(true, "2026-11-23", "2026-11-24", "POPUP", 40320, "NONE", null), true);
        for (Integer minutes : new Integer[] {null, -1, 40321})
            assertThatThrownBy(() -> CalendarPolicy.payload(json, request(true, "2026-11-23", "2026-11-24", "POPUP", minutes, "NONE", null), true))
                .isInstanceOf(CalendarException.class);
    }

    @Test void weekdaysRejectWeekendStartInSeoulAndAcceptWeekday() {
        assertThatThrownBy(() -> CalendarPolicy.payload(json, request(true, "2026-11-22", "2026-11-23", "NONE", null, "WEEKDAYS", 10), true))
            .isInstanceOf(CalendarException.class).hasMessageContaining("평일");
        assertThatThrownBy(() -> CalendarPolicy.payload(json, request(false, "2026-11-20T18:00:00Z", "2026-11-20T19:00:00Z", "NONE", null, "WEEKDAYS", 10), true))
            .isInstanceOf(CalendarException.class).hasMessageContaining("한국 시간");
        var valid = CalendarPolicy.payload(json, request(false, "2026-11-22T18:00:00Z", "2026-11-22T19:00:00Z", "NONE", null, "WEEKDAYS", 10), true);
        assertThat(valid.path("recurrence").get(0).asText()).isEqualTo("RRULE:FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR;COUNT=10");
    }

    @Test void recurrenceCountIsBoundedAndUpdateCannotChangeSeriesRules() {
        for (Integer count : new Integer[] {null, 1, 367})
            assertThatThrownBy(() -> CalendarPolicy.payload(json, request(true, "2026-11-23", "2026-11-24", "NONE", null, "WEEKLY", count), true))
                .isInstanceOf(CalendarException.class);
        var update = new CalendarWriteRequest(null, "수정", "", "", true, "2026-11-23", "2026-11-24", "KEEP", null, "DAILY", 5);
        assertThatThrownBy(() -> CalendarPolicy.payload(json, update, false)).isInstanceOf(CalendarException.class);
    }

    @Test void invalidEtagsCannotDisableOptimisticConcurrency() {
        CalendarPolicy.etag("\"known-version\"");
        for (String value : new String[] {null, "*", "unquoted", "W/\"weak\"", "\"line\nbreak\"", "\"one\",\"two\""})
            assertThatThrownBy(() -> CalendarPolicy.etag(value)).isInstanceOf(CalendarException.class)
                .satisfies(error -> assertThat(((CalendarException) error).status()).isEqualTo(428));
    }
}
