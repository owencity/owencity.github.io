package com.devdesk.summary;

import com.devdesk.summary.source.SourceContent;

public interface SummaryGenerator extends AutoCloseable {
    SummaryResult generate(SourceContent source);
    default SummaryResult generateForModel(SourceContent source, String model) { return generate(source); }
    @Override default void close() {}
}
