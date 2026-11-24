package com.devdesk.summary;

import java.util.List;

/** Variable-length source-based briefing. Developer interpretation is kept separate. */
public record SummaryResult(List<Section> sections, List<String> developerNotes, List<String> caveats) {
    public record Section(String heading, List<String> paragraphs) {}
}
