package com.devdesk.tasks;

import com.devdesk.calendar.CalendarDay;
import com.devdesk.calendar.CalendarEvent;
import com.devdesk.calendar.CalendarService;
import com.devdesk.notification.DigestService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Optional server follow-up; Google remains the source of task and schedule state. */
@Component
public class TaskFollowupScheduler {
    private static final Logger log = LoggerFactory.getLogger(TaskFollowupScheduler.class);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Duration RECENT = Duration.ofMinutes(30);
    private static final int MAX_CHECKS = 5;

    interface Sources {
        CalendarDay day(LocalDate date);
        List<TasksService.ListView> todayLists();
        TasksService.Task task(String list, String id);
    }
    interface Delivery {
        boolean available(String channel);
        String send(String channel, String subject, String text);
    }
    interface Claims {
        boolean exists(String id, Instant end, String channel);
        boolean claim(String id, Instant end, String channel, String taskKey, Instant now);
        void finish(String id, Instant end, String channel, String status, Instant now);
    }
    private record Candidate(CalendarEvent event, Instant end) {}
    private record LinkedTask(String listId, TasksService.Task task) {}

    private final Sources sources;
    private final Delivery delivery;
    private final Claims claims;
    private final String channel;
    private final Clock clock;

    @Autowired
    public TaskFollowupScheduler(CalendarService calendar, TasksService tasks, DigestService notifications,
            JdbcTemplate jdbc, @Value("${devdesk.tasks.followup-channel:}") String channel) {
        this(new Sources() {
            public CalendarDay day(LocalDate date) { return calendar.day(date); }
            public List<TasksService.ListView> todayLists() { return tasks.todayLists(); }
            public TasksService.Task task(String list, String id) { return tasks.todayTask(list, id); }
        }, new Delivery() {
            public boolean available(String name) { return notifications.followupAvailable(name); }
            public String send(String name, String subject, String text) { return notifications.sendTaskFollowup(name, subject, text); }
        }, new JdbcClaims(jdbc), channel, Clock.systemUTC());
    }

    TaskFollowupScheduler(Sources sources, Delivery delivery, Claims claims, String channel, Clock clock) {
        this.sources = sources; this.delivery = delivery; this.claims = claims; this.clock = clock;
        this.channel = channel == null ? "" : channel.strip().toLowerCase(Locale.ROOT);
    }

    @Scheduled(initialDelay = 60000, fixedDelay = 60000, scheduler = "taskScheduler")
    public void followUp() {
        if (!List.of("slack", "email").contains(channel)) return;
        try {
            if (!delivery.available(channel)) return;
            LocalDate date = LocalDate.now(clock.withZone(SEOUL));
            List<Candidate> candidates = candidates(sources.day(date), date, clock.instant());
            if (candidates.isEmpty()) return;
            Map<String, LinkedTask> tasks = pendingTasks(sources.todayLists());
            int checked = 0;
            for (Candidate candidate : candidates) {
                LinkedTask linked = tasks.get(candidate.event().taskPlanKey());
                if (linked == null || claims.exists(candidate.event().id(), candidate.end(), channel)) continue;
                if (++checked > MAX_CHECKS) break;
                // Re-read after listing tasks, so moved/deleted/duplicated calendar plans are not claimed.
                boolean unchanged = candidates(sources.day(date), date, clock.instant()).stream().anyMatch(current ->
                    current.event().id().equals(candidate.event().id()) && current.end().equals(candidate.end())
                    && current.event().taskPlanKey().equals(candidate.event().taskPlanKey()));
                if (!unchanged) continue;
                TasksService.Task fresh = sources.task(linked.listId(), linked.task().id());
                if (fresh == null || fresh.completed() || !linked.task().id().equals(fresh.id())
                        || !candidate.event().taskPlanKey().equals(fresh.planKey())) continue;
                Instant now = clock.instant();
                if (!date.equals(LocalDate.ofInstant(now, SEOUL)) || candidate(candidate.event(), date, now) == null) continue;
                if (!claims.claim(candidate.event().id(), candidate.end(), channel, fresh.planKey(), now)) continue;
                String outcome;
                try {
                    outcome = delivery.send(channel, "DevDesk · TODAY 할 일 확인", message(fresh, candidate.end()));
                    if (!List.of("SENT", "FAILED", "UNKNOWN").contains(outcome)) outcome = "UNKNOWN";
                } catch (RuntimeException uncertain) { outcome = "UNKNOWN"; }
                // A save failure leaves PROCESSING. Every recorded state blocks automatic resend.
                claims.finish(candidate.event().id(), candidate.end(), channel, outcome, clock.instant());
            }
        } catch (RuntimeException unavailable) {
            // Do not log task content, provider errors, addresses or credentials.
            log.warn("Task follow-up skipped or delivery result needs inspection");
        }
    }

    private static List<Candidate> candidates(CalendarDay day, LocalDate date, Instant now) {
        if (day == null || !day.connected() || (day.error() != null && !day.error().isBlank())
                || !date.toString().equals(day.date()) || day.events() == null) return List.of();
        Map<String, Integer> counts = new HashMap<>();
        for (CalendarEvent event : day.events()) if (event != null && event.taskPlanKey() != null)
            counts.merge(event.taskPlanKey(), 1, Integer::sum);
        var candidates = new ArrayList<Candidate>();
        for (CalendarEvent event : day.events()) {
            Candidate value = candidate(event, date, now);
            if (value != null && counts.getOrDefault(event.taskPlanKey(), 0) == 1) candidates.add(value);
        }
        candidates.sort(java.util.Comparator.comparing(Candidate::end));
        return candidates;
    }

    private static Candidate candidate(CalendarEvent event, LocalDate date, Instant now) {
        if (event == null || event.allDay() || event.id() == null || event.id().isBlank() || event.id().length() > 1024
                || event.taskPlanKey() == null || !event.taskPlanKey().matches("[a-f0-9]{64}")) return null;
        try {
            Instant start = OffsetDateTime.parse(event.start()).toInstant();
            Instant end = OffsetDateTime.parse(event.end()).toInstant();
            if (!end.isAfter(start) || end.isAfter(now) || end.isBefore(now.minus(RECENT))
                    || !date.equals(LocalDate.ofInstant(end, SEOUL))) return null;
            return new Candidate(event, end);
        } catch (RuntimeException invalid) { return null; }
    }

    private static Map<String, LinkedTask> pendingTasks(List<TasksService.ListView> lists) {
        Map<String, LinkedTask> result = new HashMap<>();
        var duplicates = new HashSet<String>();
        if (lists == null) return result;
        for (var list : lists) {
            if (list == null || !"TODAY".equals(list.horizon()) || list.id() == null || list.id().isBlank() || list.tasks() == null) continue;
            for (var task : list.tasks()) {
                if (task == null || task.completed() || task.id() == null || task.id().isBlank() || task.planKey() == null) continue;
                if (result.putIfAbsent(task.planKey(), new LinkedTask(list.id(), task)) != null) duplicates.add(task.planKey());
            }
        }
        duplicates.forEach(result::remove);
        return result;
    }

    private static String message(TasksService.Task task, Instant end) {
        String title = task.title() == null ? "제목 없는 할 일" : task.title().replaceAll("[\\p{Cntrl}]", " ").strip();
        if (title.length() > 300) title = title.substring(0, Character.isHighSurrogate(title.charAt(299)) ? 299 : 300);
        return "배정한 시간이 끝났어요.\n\n할 일: " + title + "\n종료: "
            + DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(SEOUL).format(end)
            + " (Asia/Seoul)\n\nGoogle Tasks에는 아직 미완료로 표시됩니다. 끝냈다면 완료로 표시하고, 더 필요하면 시간을 다시 배정해 주세요.\n\nhttps://tasks.google.com/";
    }

    static final class JdbcClaims implements Claims {
        private final JdbcTemplate jdbc;
        JdbcClaims(JdbcTemplate jdbc) { this.jdbc = jdbc; }
        public boolean exists(String id, Instant end, String channel) {
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM task_followup_delivery WHERE event_id=? AND event_end=? AND channel=?",
                Integer.class, id, end.atOffset(ZoneOffset.UTC), channel);
            return count != null && count > 0;
        }
        public boolean claim(String id, Instant end, String channel, String taskKey, Instant now) {
            try {
                return jdbc.update("INSERT INTO task_followup_delivery(event_id,event_end,channel,task_plan_key,status,created_at,updated_at) "
                    + "VALUES (?,?,?,?,'PROCESSING',?,?)", id, end.atOffset(ZoneOffset.UTC), channel, taskKey,
                    now.atOffset(ZoneOffset.UTC), now.atOffset(ZoneOffset.UTC)) == 1;
            } catch (DuplicateKeyException duplicate) { return false; }
        }
        public void finish(String id, Instant end, String channel, String status, Instant now) {
            jdbc.update("UPDATE task_followup_delivery SET status=?,updated_at=? WHERE event_id=? AND event_end=? AND channel=?",
                status, now.atOffset(ZoneOffset.UTC), id, end.atOffset(ZoneOffset.UTC), channel);
        }
    }
}
