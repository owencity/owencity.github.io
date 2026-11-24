package com.devdesk.summary.source;

/** Exact input retained with the summary so its coverage is inspectable. */
public record SourceContent(String title, String url, String text, String scope, String warning) {}
