package com.devdesk.calendar;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CalendarServiceTest {
    @Test void allDayEndStaysExclusiveDate()throws Exception {
        var event=CalendarService.event(new ObjectMapper().readTree("""
            {"id":"1","summary":"하루 일정","start":{"date":"2026-11-22"},"end":{"date":"2026-11-23"},"htmlLink":"https://calendar.google.com/event"}
            """));
        assertThat(event.allDay()).isTrue();
        assertThat(event.start()).isEqualTo("2026-11-22");
        assertThat(event.end()).isEqualTo("2026-11-23");
    }
    @Test void overnightEventPreservesOffsetsAndUnsafeLinksAreReplaced()throws Exception {
        var event=CalendarService.event(new ObjectMapper().readTree("""
            {"id":"2","start":{"dateTime":"2026-11-21T23:00:00+09:00"},"end":{"dateTime":"2026-11-22T01:00:00+09:00"},"htmlLink":"javascript:alert(1)"}
            """));
        assertThat(event.allDay()).isFalse();
        assertThat(event.start()).isEqualTo("2026-11-21T23:00:00+09:00");
        assertThat(event.url()).isEqualTo(CalendarService.CALENDAR_URL);
    }
}
