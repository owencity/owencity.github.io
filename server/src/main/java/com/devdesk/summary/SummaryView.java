package com.devdesk.summary;

import java.time.Instant;
import java.util.List;

public record SummaryView(long newsId, String status, String sourceScope, String sourceUrl,
        String sourceWarning, String model, Instant generatedAt, List<SummaryResult.Section> sections,
        List<String> developerNotes, List<String> caveats, String error, boolean canRetry) {
    static SummaryView empty(long newsId, String status, String error) {
        return new SummaryView(newsId, status, null, null, null, null, null,
            List.of(), List.of(), List.of(), error, false);
    }
}
