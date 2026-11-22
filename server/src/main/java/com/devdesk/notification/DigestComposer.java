package com.devdesk.notification;

import com.devdesk.calendar.CalendarEvent;
import com.devdesk.calendar.CalendarService;
import com.devdesk.calendar.CalendarToday;
import com.devdesk.news.NewsItem;
import com.devdesk.news.NewsService;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class DigestComposer {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final NewsService news;
    private final CalendarService calendar;

    public DigestComposer(NewsService news, CalendarService calendar) {
        this.news = news;
        this.calendar = calendar;
    }

    public Content compose(LocalDate day) {
        var body = new StringBuilder("오늘 일정\n");
        CalendarToday today = calendar.today();
        if (!today.connected()) {
            body.append("Google Calendar 연결이 필요합니다.\n");
        } else if (today.error() != null && !today.error().isBlank()) {
            body.append("일정을 가져오지 못했습니다. Google Calendar에서 확인해 주세요.\n");
        } else if (today.events().isEmpty()) {
            body.append("오늘 등록된 일정이 없습니다.\n");
        } else {
            for (CalendarEvent event : today.events().stream().limit(20).toList()) {
                body.append("• ").append(time(event)).append(" ").append(shortTitle(event.title())).append('\n');
            }
            if (today.events().size() > 20) body.append("나머지 일정은 Google Calendar에서 확인하세요.\n");
        }
        body.append("https://calendar.google.com/calendar/u/0/r/day/")
            .append(day.getYear()).append('/').append(day.getMonthValue()).append('/').append(day.getDayOfMonth())
            .append("\n\n어제부터 새로 수집한 개발 소식\n");
        var articles = recentArticles(news.list("all", ""), day);
        if (articles.isEmpty()) body.append("새로 수집한 미읽음 소식이 없습니다.\n");
        for (NewsItem article : articles) {
            body.append("• ").append(shortTitle(article.title())).append('\n');
            body.append(article.url()).append('\n');
        }
        body.append("\n일정은 조회 시점 기준입니다. 변경 사항은 Google Calendar에서 확인하세요.");
        return new Content(day + " · DevDesk 오늘 일정과 개발 소식", body.toString());
    }

    // Digest freshness is independent of recommendation ranking. Old favorites must not
    // occupy the daily digest forever. Collection timestamps also support feeds with no pubDate.
    static List<NewsItem> recentArticles(List<NewsItem> articles, LocalDate day) {
        var start = day.minusDays(1).atStartOfDay(SEOUL).toInstant();
        var end = day.plusDays(1).atStartOfDay(SEOUL).toInstant();
        Set<String> excluded = Set.of("READ", "KNOWN", "NOT_INTERESTED");
        return articles.stream()
            .filter(article -> !excluded.contains(article.feedback()))
            .filter(article -> article.collectedAt() != null
                && !article.collectedAt().isBefore(start) && article.collectedAt().isBefore(end))
            .sorted(Comparator.comparing(NewsItem::collectedAt).reversed()
                .thenComparing(Comparator.comparingLong(NewsItem::id).reversed()))
            .limit(5).toList();
    }

    private String shortTitle(String value) {
        if (value == null || value.isBlank()) return "제목 없음";
        String singleLine = value.replaceAll("[\\r\\n\\t]+", " ");
        return singleLine.length() > 250 ? singleLine.substring(0, 250) + "…" : singleLine;
    }

    private String time(CalendarEvent event) {
        if (event.allDay()) return "종일";
        try {
            return OffsetDateTime.parse(event.start()).atZoneSameInstant(SEOUL)
                .format(DateTimeFormatter.ofPattern("HH:mm"));
        } catch (RuntimeException e) {
            return event.start();
        }
    }

    public record Content(String subject, String text) {}
}
