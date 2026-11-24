package com.devdesk.tasks;

import com.devdesk.calendar.CalendarDay;
import com.devdesk.calendar.CalendarEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TaskFollowupSchedulerTest {
    private static final Instant NOW = Instant.parse("2026-11-24T09:00:00Z");
    private static final String KEY = "a".repeat(64);
    private static final String OTHER = "b".repeat(64);
    private static final LocalDate DATE = LocalDate.of(2026, 11, 24);

    private static CalendarEvent event(String end, boolean allDay, String key) {
        return new CalendarEvent("event-one", "Original title", "2026-11-24T17:00:00+09:00", end,
            allDay, "https://calendar.google.com/", "", "", "etag", "", true, "POPUP", 0, key);
    }
    private static CalendarEvent ended() { return event("2026-11-24T17:55:00+09:00", false, KEY); }
    private static CalendarDay day(List<CalendarEvent> events) {
        return new CalendarDay(true, events, "https://calendar.google.com/", null, true, DATE.toString());
    }

    private static final class FakeSources implements TaskFollowupScheduler.Sources {
        CalendarDay initial = TaskFollowupSchedulerTest.day(List.of(ended()));
        CalendarDay refreshed;
        List<TasksService.ListView> lists = List.of(new TasksService.ListView("list-id", "TODAY", "TODAY",
            List.of(new TasksService.Task("task-id", "Snapshot title", false, KEY))));
        TasksService.Task fresh = new TasksService.Task("task-id", "Fresh title", false, KEY);
        final AtomicInteger dayReads = new AtomicInteger(), listReads = new AtomicInteger(), taskReads = new AtomicInteger();
        Runnable beforeFresh = () -> {};
        public CalendarDay day(LocalDate date) {
            assertThat(date).isEqualTo(DATE);
            return dayReads.incrementAndGet() > 1 && refreshed != null ? refreshed : initial;
        }
        public List<TasksService.ListView> todayLists() { listReads.incrementAndGet(); return lists; }
        public TasksService.Task task(String list, String id) {
            assertThat(list).isEqualTo("list-id"); assertThat(id).isEqualTo("task-id");
            taskReads.incrementAndGet(); beforeFresh.run(); return fresh;
        }
    }
    private static final class FakeDelivery implements TaskFollowupScheduler.Delivery {
        boolean enabled = true, uncertain;
        String outcome = "SENT";
        final AtomicInteger availability = new AtomicInteger();
        final List<String> messages = new CopyOnWriteArrayList<>();
        public boolean available(String channel) { availability.incrementAndGet(); return enabled; }
        public String send(String channel, String subject, String text) {
            messages.add(subject + "\n" + text);
            if (uncertain) throw new IllegalStateException("Ambiguous transport outcome");
            return outcome;
        }
    }
    private static final class FakeClaims implements TaskFollowupScheduler.Claims {
        final ConcurrentHashMap<String, String> states = new ConcurrentHashMap<>();
        final AtomicInteger claimAttempts = new AtomicInteger();
        boolean failFinish;
        CountDownLatch race;
        private String key(String id, Instant end, String channel) { return id + "|" + end + "|" + channel; }
        public boolean exists(String id, Instant end, String channel) { return states.containsKey(key(id, end, channel)); }
        public boolean claim(String id, Instant end, String channel, String taskKey, Instant now) {
            claimAttempts.incrementAndGet();
            if (race != null) {
                race.countDown();
                try { if (!race.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("No concurrent worker"); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
            }
            return states.putIfAbsent(key(id, end, channel), "PROCESSING") == null;
        }
        public void finish(String id, Instant end, String channel, String status, Instant now) {
            if (failFinish) throw new IllegalStateException("Database unavailable after send");
            states.put(key(id, end, channel), status);
        }
    }
    private static final class Fixture {
        final FakeSources sources = new FakeSources();
        final FakeDelivery delivery = new FakeDelivery();
        final FakeClaims claims = new FakeClaims();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        TaskFollowupScheduler scheduler(String channel) { return new TaskFollowupScheduler(sources, delivery, claims, channel, clock); }
        void run() { scheduler("slack").followUp(); }
        void noSendOrClaim() { assertThat(delivery.messages).isEmpty(); assertThat(claims.states).isEmpty(); }
    }

    @Test void defaultOffAndInvalidChannelDoNotReadProvidersOrDatabase() {
        var f = new Fixture();
        f.scheduler("").followUp(); f.scheduler("sms").followUp();
        assertThat(f.delivery.availability.get()).isZero();
        assertThat(f.sources.dayReads.get()).isZero(); f.noSendOrClaim();
    }

    @Test void disabledOrUnconfiguredDeliveryStopsBeforeGoogleReads() {
        var f = new Fixture(); f.delivery.enabled = false; f.run();
        assertThat(f.sources.dayReads.get()).isZero(); f.noSendOrClaim();
    }

    @Test void freshPendingTaskSendsOnceAndUsesCurrentTitle() {
        var f = new Fixture(); f.run(); f.run();
        assertThat(f.delivery.messages).hasSize(1);
        assertThat(f.delivery.messages.getFirst()).contains("Fresh title", "17:55", "미완료").doesNotContain("Snapshot title");
        assertThat(f.claims.states.values()).containsExactly("SENT");
        assertThat(f.sources.taskReads.get()).isEqualTo(1);
    }

    @Test void taskCompletedSinceListSnapshotIsNotClaimed() {
        var f = new Fixture(); f.sources.fresh = new TasksService.Task("task-id", "Done", true, KEY); f.run();
        assertThat(f.sources.taskReads.get()).isEqualTo(1); f.noSendOrClaim();
    }

    @Test void mismatchedFreshTaskIdentityOrPlanKeyIsNotClaimed() {
        for (var task : List.of(new TasksService.Task("wrong-id", "Wrong", false, KEY),
                new TasksService.Task("task-id", "Wrong", false, OTHER))) {
            var f = new Fixture(); f.sources.fresh = task; f.run(); f.noSendOrClaim();
        }
    }

    @Test void failedFreshTaskReadCannotLeaveDeliveryClaim() {
        var f = new Fixture(); f.sources.beforeFresh = () -> { throw new IllegalStateException("Unavailable"); };
        f.run(); f.noSendOrClaim();
    }

    @Test void movedOrDeletedCalendarPlanIsRecheckedBeforeFreshTaskAndClaim() {
        for (var current : List.of(day(List.of()), day(List.of(event("2026-11-24T19:00:00+09:00", false, KEY))))) {
            var f = new Fixture(); f.sources.refreshed = current; f.run();
            assertThat(f.sources.taskReads.get()).isZero(); f.noSendOrClaim();
        }
    }

    @Test void staleFutureAllDayMalformedAndUnlinkedEventsAreSkipped() {
        for (var event : List.of(event("2026-11-24T17:20:00+09:00", false, KEY),
                event("2026-11-24T19:00:00+09:00", false, KEY), event("2026-11-24", true, KEY),
                event("not-a-date", false, KEY), event("2026-11-24T17:55:00+09:00", false, ""),
                event("2026-11-23T17:55:00+09:00", false, KEY))) {
            var f = new Fixture(); f.sources.initial = day(List.of(event)); f.run();
            assertThat(f.sources.listReads.get()).isZero(); f.noSendOrClaim();
        }
    }

    @Test void disconnectedCalendarOrMissingTodayTaskCannotSend() {
        var disconnected = new Fixture();
        disconnected.sources.initial = new CalendarDay(false, List.of(ended()), "", "Unavailable", false, DATE.toString());
        disconnected.run(); disconnected.noSendOrClaim();
        var absent = new Fixture(); absent.sources.lists = List.of(); absent.run(); absent.noSendOrClaim();
        var wrongHorizon = new Fixture(); wrongHorizon.sources.lists = List.of(new TasksService.ListView("list-id", "WEEK", "WEEK",
            List.of(new TasksService.Task("task-id", "Wrong list", false, KEY))));
        wrongHorizon.run(); wrongHorizon.noSendOrClaim();
    }

    @Test void duplicateLinkedPlansAreAmbiguousAndSkipped() {
        var f = new Fixture(); f.sources.initial = day(List.of(ended(), ended())); f.run(); f.noSendOrClaim();
    }

    @Test void transportAmbiguityAndDefiniteFailureAreNeverRetriedAutomatically() {
        var uncertain = new Fixture(); uncertain.delivery.uncertain = true; uncertain.run(); uncertain.run();
        assertThat(uncertain.claims.states.values()).containsExactly("UNKNOWN");
        assertThat(uncertain.delivery.messages).hasSize(1);
        var rejected = new Fixture(); rejected.delivery.outcome = "FAILED"; rejected.run(); rejected.run();
        assertThat(rejected.claims.states.values()).containsExactly("FAILED");
        assertThat(rejected.delivery.messages).hasSize(1);
    }

    @Test void resultSaveFailureLeavesProcessingAndDoesNotResend() {
        var f = new Fixture(); f.claims.failFinish = true; f.run(); f.run();
        assertThat(f.claims.states.values()).containsExactly("PROCESSING");
        assertThat(f.delivery.messages).hasSize(1);
    }

    @Test void concurrentWorkersSendOnlyAfterOneAtomicClaimWins() throws Exception {
        var f = new Fixture(); f.claims.race = new CountDownLatch(2);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> f.scheduler("slack").followUp());
            var second = workers.submit(() -> f.scheduler("slack").followUp());
            first.get(); second.get();
        }
        assertThat(f.claims.claimAttempts.get()).isEqualTo(2);
        assertThat(f.delivery.messages).hasSize(1);
    }

    @Test void changedEndHasSeparateDeliveryIdentity() {
        var f = new Fixture(); f.run();
        f.sources.initial = day(List.of(event("2026-11-24T17:59:00+09:00", false, KEY)));
        f.run();
        assertThat(f.claims.states).hasSize(2); assertThat(f.delivery.messages).hasSize(2);
    }

    @Test void timeWindowIsRecheckedAfterSlowFreshRead() {
        var f = new Fixture();
        class MutableClock extends Clock {
            Instant instant = NOW;
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return Clock.fixed(instant, zone); }
            public Instant instant() { return instant; }
        }
        var clock = new MutableClock(); f.clock = clock;
        f.sources.beforeFresh = () -> clock.instant = NOW.plusSeconds(31 * 60);
        f.run(); f.noSendOrClaim();
    }
}
