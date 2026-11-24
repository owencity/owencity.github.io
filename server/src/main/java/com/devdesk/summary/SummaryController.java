package com.devdesk.summary;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SummaryController {
    private final SummaryService summaries;
    public SummaryController(SummaryService summaries) { this.summaries = summaries; }

    @GetMapping("/api/news/{id}/summary")
    public SummaryView get(@PathVariable long id) { return summaries.get(id); }

    @PostMapping("/api/news/{id}/summary")
    public SummaryView request(@PathVariable long id) { return summaries.request(id); }

    @GetMapping("/api/summary/status")
    public SummaryService.Status status() { return summaries.status(); }
}
