package com.devdesk.news;
import java.time.Instant;
public record NewsItem(long id, String sourceId, String title, String url, String description,
                       Instant publishedAt, Instant collectedAt, String topic, String feedback) {}
