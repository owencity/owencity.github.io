package com.devdesk.calendar;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.devdesk.HttpFetch;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CalendarServiceTest {
    @Test void calendarApiNotEnabledIsDistinguishedFromMissingConsent() {
        var calendar = denied("""
            {"error":{"errors":[{"reason":"accessNotConfigured"}],"message":"private upstream text"}}
            """);
        var day = calendar.day(LocalDate.of(2026, 11, 23));
        assertThat(day.connected()).isFalse();
        assertThat(day.error()).contains("Google Calendar API", "사용 설정").doesNotContain("다시 승인", "private upstream text");
    }

    @Test void structuredScopeFailureIsDistinctFromQuotaFailure() {
        var scope = denied("""
            {"error":{"details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo",
              "domain":"googleapis.com","reason":"ACCESS_TOKEN_SCOPE_INSUFFICIENT"}]}}
            """).day(LocalDate.of(2026, 11, 23));
        assertThat(scope.error()).contains("토큰", "다시 승인");
        var quota = denied("{\"error\":{\"errors\":[{\"reason\":\"userRateLimitExceeded\"}]}}")
            .day(LocalDate.of(2026, 11, 23));
        assertThat(quota.error()).contains("호출 한도").doesNotContain("다시 승인");
    }

    @Test void unknownMalformedAndUntrustedErrorDetailsNeverExposeProviderMessages() {
        for (String body : List.of("not JSON", "null",
                "{\"error\":{\"message\":\"secret-value-must-not-leak\"}}",
                "{\"error\":{\"details\":[{\"@type\":\"type.googleapis.com/google.rpc.ErrorInfo\",\"domain\":\"untrusted\",\"reason\":\"SERVICE_DISABLED\"}]}}")) {
            var day = denied(body).day(LocalDate.of(2026, 11, 23));
            assertThat(day.error()).contains("접근이 거부").doesNotContain("secret-value-must-not-leak", "다시 승인");
        }
    }

    private CalendarService denied(String body) {
        CalendarTransport remote = (request, maxBytes) -> {
            if (request.uri().getHost().equals("oauth2.googleapis.com"))
                return new HttpFetch.Response(200, ("{\"access_token\":\"fake-token\",\"scope\":\"https://www.googleapis.com/auth/calendar.events.owned\"}").getBytes(StandardCharsets.UTF_8));
            return new HttpFetch.Response(403, body.getBytes(StandardCharsets.UTF_8));
        };
        return new CalendarService(new ObjectMapper(), "test-client", "test-secret", "test-refresh", "primary", true, remote, Clock.systemUTC());
    }

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
