package com.devdesk.calendar;

import com.devdesk.HttpFetch;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Exercises the Google boundary without opening a socket or sending an actual event. */
class CalendarCrudTest {
    @Test void todayTaskUsesExactlyItsStartTimeForReminderAndReschedulesExistingEvent() {
        var remote = new FakeGoogle();
        var service = service(remote);
        String key = "a".repeat(64);
        var date = LocalDate.of(2026, 11, 22);
        var saved = service.scheduleTodayTask(key, "JVM 공부", date,
            LocalTime.of(22, 0), LocalTime.of(22, 30), REQUEST_ID, null, null);
        assertThat(saved.taskPlanKey()).isEqualTo(key);
        assertThat(remote.posted.path("start").path("dateTime").asText()).isEqualTo("2026-11-22T22:00:00+09:00");
        assertThat(remote.posted.path("reminders").path("overrides").get(0).path("minutes").asInt(-1)).isZero();
        assertThat(remote.posted.path("visibility").asText()).isEqualTo("private");
        assertThat(remote.requests.stream().anyMatch(request -> request.uri().getQuery() != null
            && request.uri().getQuery().contains("privateExtendedProperty=devdeskTask=" + key))).isTrue();

        remote.planned = remote.completed(remote.posted);
        service.scheduleTodayTask(key, "JVM 공부", date, LocalTime.of(22, 0), LocalTime.of(22, 30), REQUEST_ID, null, null);
        assertFailure(() -> service.scheduleTodayTask(key, "JVM 공부", date, LocalTime.of(22, 30),
            LocalTime.of(23, 0), REQUEST_ID, saved.id(), "\"old-etag\""), 412, "EVENT_CHANGED");
        service.scheduleTodayTask(key, "JVM 공부", date, LocalTime.of(22, 30), LocalTime.of(23, 0), REQUEST_ID, saved.id(), ETAG);
        assertThat(remote.mutations()).extracting(HttpRequest::method).containsExactly("POST", "PATCH");
        assertThat(remote.mutations().get(1).headers().firstValue("If-Match")).contains(ETAG);
        assertThat(remote.patched.path("start").path("dateTime").asText()).isEqualTo("2026-11-22T22:30:00+09:00");
    }

    @Test void invalidTodayTimeNeverTouchesCalendar() {
        var remote = new FakeGoogle();
        var service = service(remote);
        assertFailure(() -> service.scheduleTodayTask("a".repeat(64), "할 일", LocalDate.of(2026, 11, 22),
            LocalTime.of(20, 0), LocalTime.of(20, 30), REQUEST_ID, null, null), 400, "INVALID_EVENT");
        assertThat(remote.requests).isEmpty();
    }
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String REQUEST_ID = "11111111-2222-4333-8444-555555555555";
    private static final String EVENT_ID = "event12345";
    private static final String ETAG = "\"version1\"";

    @Test void sameCreateRequestRecoversExistingEventAfterGoogleConflict() {
        var remote = new FakeGoogle();
        remote.postStatus = 409;
        remote.recoverPostedPayload = true;
        var result = service(remote).create(createRequest());
        assertThat(result.id()).isEqualTo(CalendarPolicy.eventId(REQUEST_ID));
        assertThat(remote.mutations()).hasSize(1);
        assertThat(remote.mutations().getFirst().method()).isEqualTo("POST");
        assertThat(remote.posted.path("extendedProperties").path("private").size()).isGreaterThan(0);
    }

    @Test void conflictingCreateIdWithoutMatchingRequestFingerprintIsRejected() {
        var remote = new FakeGoogle();
        remote.postStatus = 409;
        assertFailure(() -> service(remote).create(createRequest()), 409, "REQUEST_REUSED");
        assertThat(remote.mutations()).hasSize(1);
    }

    @Test void patchUsesIfMatchAndSurfacesConcurrentGoogleChange() {
        var remote = new FakeGoogle();
        remote.patchStatus = 412;
        assertFailure(() -> service(remote).update(EVENT_ID, updateRequest(), ETAG, "occurrence"),
            412, "EVENT_CHANGED");
        assertThat(remote.mutations()).hasSize(1);
        assertThat(remote.mutations().getFirst().headers().firstValue("If-Match")).contains(ETAG);
        assertThat(remote.mutations().getFirst().method()).isEqualTo("PATCH");
    }

    @Test void staleDeleteEtagStopsBeforeAnyDeleteRequest() {
        var remote = new FakeGoogle();
        remote.current.put("etag", "\"version2\"");
        assertFailure(() -> service(remote).delete(EVENT_ID, ETAG, "occurrence"), 412, "EVENT_CHANGED");
        assertThat(remote.mutations()).isEmpty();
    }

    @Test void successfulDeleteUsesConditionalRequestAndAcceptsNoContent() {
        var remote = new FakeGoogle();
        service(remote).delete(EVENT_ID, ETAG, "occurrence");
        assertThat(remote.mutations()).hasSize(1);
        var deletion = remote.mutations().getFirst();
        assertThat(deletion.method()).isEqualTo("DELETE");
        assertThat(deletion.headers().firstValue("If-Match")).contains(ETAG);
        assertThat(deletion.uri().getQuery()).contains("sendUpdates=none");
    }

    @Test void missingEtagIsRejectedBeforeAnyUpstreamRequest() {
        var remote = new FakeGoogle();
        assertFailure(() -> service(remote).delete(EVENT_ID, null, "occurrence"), 428, "ETAG_REQUIRED");
        assertThat(remote.requests).isEmpty();
    }

    @Test void disabledWritesDoNotRefreshTokenOrContactGoogle() {
        var remote = new FakeGoogle();
        var disabled = new CalendarService(JSON, "test-client", "test-secret", "test-refresh", "primary", false,
            remote, Clock.fixed(Instant.parse("2026-11-22T12:00:00Z"), ZoneOffset.UTC));
        assertFailure(() -> disabled.create(createRequest()), 403, "WRITE_DISABLED");
        assertThat(remote.requests).isEmpty();
    }

    @Test void seriesParentCannotBeChangedAsOneOccurrence() {
        var remote = new FakeGoogle();
        remote.current.putArray("recurrence").add("RRULE:FREQ=WEEKLY;COUNT=8");
        assertFailure(() -> service(remote).update(EVENT_ID, updateRequest(), ETAG, "occurrence"),
            403, "EVENT_NOT_EDITABLE");
        assertThat(remote.mutations()).isEmpty();
    }

    @Test void attendeeEventCannotBeDeletedEvenWhenOrganizerIsSelf() {
        var remote = new FakeGoogle();
        remote.current.putArray("attendees").addObject().put("email", "guest@example.invalid");
        assertFailure(() -> service(remote).delete(EVENT_ID, ETAG, "occurrence"), 403, "EVENT_NOT_EDITABLE");
        assertThat(remote.mutations()).isEmpty();
    }

    @Test void eventOwnedBySomebodyElseCannotBeChanged() {
        var remote = new FakeGoogle();
        remote.current.putObject("organizer").put("self", false);
        assertFailure(() -> service(remote).update(EVENT_ID, updateRequest(), ETAG, "occurrence"),
            403, "EVENT_NOT_EDITABLE");
        assertThat(remote.mutations()).isEmpty();
    }

    @Test void recurringOccurrenceRequiresExplicitOccurrenceScope() {
        var remote = new FakeGoogle();
        remote.current.put("recurringEventId", "parent12345");
        assertFailure(() -> service(remote).update(EVENT_ID, updateRequest(), ETAG, null), 400, "INVALID_EVENT");
        assertThat(remote.mutations()).isEmpty();
    }

    @Test void occurrenceUpdateDoesNotRewriteRecurrenceOrOtherEventFields() {
        var remote = new FakeGoogle();
        remote.current.put("recurringEventId", "parent12345");
        service(remote).update(EVENT_ID, updateRequest(), ETAG, "occurrence");
        assertThat(remote.patched.has("recurrence")).isFalse();
        assertThat(remote.patched.has("attendees")).isFalse();
        assertThat(remote.patched.has("recurringEventId")).isFalse();
        assertThat(remote.patched.has("reminders")).isFalse();
        assertThat(remote.patched.path("summary").asText()).isEqualTo("변경한 공부 일정");
    }

    @Test void changingToAllDayExplicitlyClearsOldTimedRepresentation() {
        var remote = new FakeGoogle();
        var allDay = new CalendarWriteRequest(null, "종일 공부 일정", "", "", true,
            "2026-11-23", "2026-11-24", "KEEP", null, "NONE", null);
        service(remote).update(EVENT_ID, allDay, ETAG, "occurrence");
        assertThat(remote.patched.path("start").path("date").asText()).isEqualTo("2026-11-23");
        assertThat(remote.patched.path("end").path("date").asText()).isEqualTo("2026-11-24");
        assertThat(remote.patched.path("start").path("dateTime").isNull()).isTrue();
        assertThat(remote.patched.path("end").path("dateTime").isNull()).isTrue();
        assertThat(remote.patched.path("start").path("timeZone").isNull()).isTrue();
    }

    @Test void changingFromAllDayExplicitlyClearsOldDateRepresentation() {
        var remote = new FakeGoogle();
        remote.current.putObject("start").put("date", "2026-11-23");
        remote.current.putObject("end").put("date", "2026-11-24");
        service(remote).update(EVENT_ID, updateRequest(), ETAG, "occurrence");
        assertThat(remote.patched.path("start").path("date").isNull()).isTrue();
        assertThat(remote.patched.path("end").path("date").isNull()).isTrue();
        assertThat(remote.patched.path("start").path("dateTime").asText()).contains("20:00");
        assertThat(remote.patched.path("start").path("timeZone").asText()).isEqualTo("Asia/Seoul");
    }

    @Test void ambiguousCreateFailureIsNotAutomaticallyResent() {
        var remote = new FakeGoogle();
        remote.losePostResponse = true;
        assertFailure(() -> service(remote).create(createRequest()), 502, "WRITE_OUTCOME_UNKNOWN");
        assertThat(remote.mutations()).hasSize(1);
    }

    @Test void nonOwnerCalendarCannotReceiveNewEvents() {
        var remote = new FakeGoogle();
        remote.accessRole = "writer";
        assertThatThrownBy(() -> service(remote).create(createRequest()))
            .isInstanceOfSatisfying(CalendarException.class, failure -> assertThat(failure.status()).isEqualTo(403));
        assertThat(remote.mutations()).isEmpty();
    }

    @Test void readonlyGoogleScopeCannotAuthorizeAWrite() {
        var remote = new FakeGoogle();
        remote.tokenScope = "https://www.googleapis.com/auth/calendar.events.readonly";
        assertStatus(() -> service(remote).create(createRequest()), 403);
        assertThat(remote.mutations()).isEmpty();
    }

    @Test void missingGoogleScopeDoesNotAssumeWritePermission() {
        var remote = new FakeGoogle();
        remote.tokenScope = null;
        assertStatus(() -> service(remote).create(createRequest()), 403);
        assertThat(remote.mutations()).isEmpty();
    }

    @Test void refreshedReadonlyGrantInvalidatesPreviouslyConfirmedWritePermission() {
        var remote = new FakeGoogle();
        var clock = new MutableClock(Instant.parse("2026-11-22T12:00:00Z"));
        var calendar = new CalendarService(JSON, "test-client", "test-secret", "test-refresh", "primary", true,
            remote, clock);
        calendar.create(createRequest());
        assertThat(calendar.writeEnabled()).isTrue();
        remote.tokenScope = "https://www.googleapis.com/auth/calendar.events.readonly";
        clock.now = clock.now.plusSeconds(3601);
        assertFailure(() -> calendar.create(createRequest()), 403, "GOOGLE_WRITE_SCOPE_REQUIRED");
        assertThat(calendar.writeEnabled()).isFalse();
        assertThat(remote.mutations()).hasSize(1);
    }

    @Test void tokenNetworkFailureDoesNotPretendConsentWasRevoked() {
        var remote = new FakeGoogle();
        remote.loseTokenResponse = true;
        assertStatus(() -> service(remote).create(createRequest()), 502);
        assertThat(remote.mutations()).isEmpty();
    }

    @Test void tokenServiceFailureIsReportedAsUpstreamUnavailability() {
        var remote = new FakeGoogle();
        remote.tokenStatus = 503;
        assertStatus(() -> service(remote).create(createRequest()), 502);
        assertThat(remote.mutations()).isEmpty();
    }

    @Test void invalidGrantIsNotConfusedWithAnExpiredDevdeskSession() {
        var remote = new FakeGoogle();
        remote.tokenStatus = 400;
        assertFailure(() -> service(remote).create(createRequest()), 424, "GOOGLE_REAUTH_REQUIRED");
        assertThat(remote.mutations()).isEmpty();
    }

    private static CalendarService service(FakeGoogle remote) {
        return new CalendarService(JSON, "test-client", "test-secret", "test-refresh", "primary", true,
            remote, Clock.fixed(Instant.parse("2026-11-22T12:00:00Z"), ZoneOffset.UTC));
    }

    private static CalendarWriteRequest createRequest() {
        return new CalendarWriteRequest(REQUEST_ID, "새 공부 일정", "테스트 설명", "집", false,
            "2026-11-23T20:00:00+09:00", "2026-11-23T21:00:00+09:00", "NONE", null, "NONE", null);
    }

    private static CalendarWriteRequest updateRequest() {
        return new CalendarWriteRequest(null, "변경한 공부 일정", "테스트 설명", "집", false,
            "2026-11-23T20:00:00+09:00", "2026-11-23T21:00:00+09:00", "KEEP", null, "NONE", null);
    }

    private static void assertFailure(Runnable action, int status, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(CalendarException.class, failure -> {
            assertThat(failure.status()).isEqualTo(status);
            assertThat(failure.code()).isEqualTo(code);
        });
    }

    private static void assertStatus(Runnable action, int status) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(CalendarException.class,
            failure -> assertThat(failure.status()).isEqualTo(status));
    }

    private static ObjectNode event() {
        var result = JSON.createObjectNode().put("id", EVENT_ID).put("summary", "기존 일정").put("etag", ETAG);
        result.putObject("organizer").put("self", true);
        result.putObject("start").put("dateTime", "2026-11-23T20:00:00+09:00");
        result.putObject("end").put("dateTime", "2026-11-23T21:00:00+09:00");
        result.put("htmlLink", "https://calendar.google.com/calendar/event?eid=test");
        return result;
    }

    private static final class FakeGoogle implements CalendarTransport {
        final List<HttpRequest> requests = new ArrayList<>();
        ObjectNode current = event(), posted, patched, planned;
        String accessRole = "owner";
        String tokenScope = "https://www.googleapis.com/auth/calendar.events.owned";
        int postStatus = 200, patchStatus = 200, tokenStatus = 200;
        boolean recoverPostedPayload, losePostResponse, loseTokenResponse;

        @Override public HttpFetch.Response exchange(HttpRequest request, int maxBytes) throws IOException {
            requests.add(request);
            if (request.uri().getHost().equals("oauth2.googleapis.com")) {
                if (loseTokenResponse) throw new IOException("Simulated token endpoint timeout");
                var token = JSON.createObjectNode().put("access_token", "fake-token").put("expires_in", 3600);
                if (tokenScope != null) token.put("scope", tokenScope);
                return response(tokenStatus, token);
            }
            if (request.method().equals("GET") && request.uri().getPath().endsWith("/events")) {
                var list = JSON.createObjectNode().put("accessRole", accessRole);
                var items = list.putArray("items");
                if (planned != null && request.uri().getQuery().contains("privateExtendedProperty=")) items.add(planned);
                return response(200, list);
            }
            if (request.method().equals("GET")) {
                return response(200, recoverPostedPayload && posted != null ? completed(posted) : current);
            }
            if (request.method().equals("POST")) {
                posted = (ObjectNode) JSON.readTree(body(request));
                if (losePostResponse) throw new IOException("Simulated lost response after write");
                return response(postStatus, completed(posted));
            }
            if (request.method().equals("PATCH")) {
                patched = (ObjectNode) JSON.readTree(body(request));
                return response(patchStatus, completed(patched));
            }
            if (request.method().equals("DELETE")) return new HttpFetch.Response(204, new byte[0]);
            throw new AssertionError("Unexpected fake request method: " + request.method());
        }

        List<HttpRequest> mutations() {
            return requests.stream().filter(r -> r.uri().getHost().equals("www.googleapis.com")
                && !r.method().equals("GET")).toList();
        }

        private ObjectNode completed(ObjectNode payload) {
            var result = payload.deepCopy();
            if (!result.has("id")) result.put("id", EVENT_ID);
            result.put("etag", ETAG);
            result.putObject("organizer").put("self", true);
            return result;
        }

        private HttpFetch.Response response(int status, com.fasterxml.jackson.databind.JsonNode data) throws IOException {
            return new HttpFetch.Response(status, JSON.writeValueAsBytes(data));
        }
    }

    private static byte[] body(HttpRequest request) {
        var result = new CompletableFuture<byte[]>();
        var bytes = new ByteArrayOutputStream();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            public void onNext(ByteBuffer chunk) {
                byte[] part = new byte[chunk.remaining()]; chunk.get(part); bytes.writeBytes(part);
            }
            public void onError(Throwable failure) { result.completeExceptionally(failure); }
            public void onComplete() { result.complete(bytes.toByteArray()); }
        });
        return result.join();
    }

    private static final class MutableClock extends Clock {
        Instant now;
        MutableClock(Instant now) { this.now = now; }
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
    }
}
