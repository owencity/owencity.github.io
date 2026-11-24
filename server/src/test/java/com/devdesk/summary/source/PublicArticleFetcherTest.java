package com.devdesk.summary.source;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PublicArticleFetcherTest {
    @Test void responseReaderRefusesOversizedBytesInsteadOfTruncating() {
        long future = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        assertThatThrownBy(() -> PublicArticleFetcher.readBounded(new ByteArrayInputStream(new byte[20]), 10, future))
            .isInstanceOf(IOException.class).hasMessageContaining("byte limit");
    }

    @Test void responseReaderRejectsExpiredDeadline() {
        assertThatThrownBy(() -> PublicArticleFetcher.readBounded(new ByteArrayInputStream(new byte[1]), 10, System.nanoTime() - 1))
            .isInstanceOf(IOException.class).hasMessageContaining("deadline");
    }

    @Test void readerPreservesAllBytesWithinLimit() throws Exception {
        byte[] source = "Complete source including final caveat.".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(PublicArticleFetcher.readBounded(new ByteArrayInputStream(source), source.length,
            System.nanoTime() + TimeUnit.SECONDS.toNanos(1))).isEqualTo(source);
    }
}
