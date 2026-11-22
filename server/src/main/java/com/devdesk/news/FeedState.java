package com.devdesk.news;
import java.time.Instant;
public record FeedState(Instant lastCollectedAt, String error) {}
