package com.devdesk.summary;

/** Categorized failure; the supplied message must never contain provider payloads or credentials. */
public final class SummaryGenerationException extends RuntimeException {
    public enum Category {
        INVALID_OUTPUT,
        /** Unknown or shared quota exhaustion: pause all models conservatively. */
        RATE_LIMIT,
        /** A confirmed per-minute/per-second request or token limit; retry the same model later. */
        RATE_LIMIT_TRANSIENT,
        /** A confirmed daily request limit scoped to the requested model. */
        DAILY_MODEL_LIMIT,
        TRANSIENT, PERMANENT
    }
    private final Category category;

    public SummaryGenerationException(Category category, String safeMessage) {
        super(safeMessage);
        this.category = category;
    }

    public Category category() { return category; }
}
