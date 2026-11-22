package com.devdesk.notification;

import com.devdesk.news.NewsItem;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DigestComposerTest {
    @Test
    void oldFavoritesCannotDisplaceFreshUnreadArticles() {
        var oldFavorite = item(1, "2026-10-01T00:00:00Z", "INTERESTED");
        var fresh = item(2, "2026-11-22T00:00:00Z", "NONE");
        var freshRead = item(3, "2026-11-22T01:00:00Z", "READ");
        var recentSaved = item(4, "2026-11-21T03:00:00Z", "LATER");
        var known = item(5, "2026-11-22T02:00:00Z", "KNOWN");
        var hidden = item(6, "2026-11-22T03:00:00Z", "NOT_INTERESTED");
        var result = DigestComposer.recentArticles(
            List.of(oldFavorite, fresh, freshRead, recentSaved, known, hidden), LocalDate.of(2026, 11, 22));
        assertEquals(List.of(2L, 4L), result.stream().map(NewsItem::id).toList());
    }

    @Test
    void collectionWindowUsesSeoulDatesAndExcludesNextDay() {
        var before = item(1, "2026-11-20T14:59:59Z", "NONE");
        var boundary = item(2, "2026-11-20T15:00:00Z", "NONE");
        var nextDay = item(3, "2026-11-22T15:00:00Z", "NONE");
        var result = DigestComposer.recentArticles(List.of(before, boundary, nextDay), LocalDate.of(2026, 11, 22));
        assertEquals(List.of(2L), result.stream().map(NewsItem::id).toList());
    }

    @Test
    void digestHasAtMostFiveItemsInNewestCollectionOrder() {
        var result = DigestComposer.recentArticles(List.of(
            item(1, "2026-11-22T00:00:00Z", "NONE"), item(2, "2026-11-22T01:00:00Z", "NONE"),
            item(3, "2026-11-22T02:00:00Z", "NONE"), item(4, "2026-11-22T03:00:00Z", "NONE"),
            item(5, "2026-11-22T04:00:00Z", "NONE"), item(6, "2026-11-22T05:00:00Z", "NONE")),
            LocalDate.of(2026, 11, 22));
        assertEquals(List.of(6L, 5L, 4L, 3L, 2L), result.stream().map(NewsItem::id).toList());
    }

    private static NewsItem item(long id, String collected, String feedback) {
        return new NewsItem(id, "source-" + id, "News " + id, "https://example.com/" + id, "",
            null, Instant.parse(collected), "AI", feedback);
    }
}
