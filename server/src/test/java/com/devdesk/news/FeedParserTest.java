package com.devdesk.news;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class FeedParserTest {
    @Test void readsAtomAndKeepsPublishedTime()throws Exception {
        String xml="""
            <feed xmlns="http://www.w3.org/2005/Atom"><entry><id>tag:hada:1</id>
            <title>새 기술</title><link rel="alternate" href="https://news.hada.io/topic?id=1"/>
            <published>2026-11-22T10:00:00+09:00</published>
            <summary type="html">&lt;p&gt;설명&lt;/p&gt;&lt;script&gt;alert(1)&lt;/script&gt;</summary>
            </entry></feed>
            """;
        var items=FeedParser.parse(xml.getBytes(StandardCharsets.UTF_8));
        assertThat(items).hasSize(1);
        assertThat(items.getFirst().publishedAt()).isEqualTo(Instant.parse("2026-11-22T01:00:00Z"));
        assertThat(items.getFirst().description()).isEqualTo("설명");
    }
    @Test void refusesExternalEntities() {
        String xml="<!DOCTYPE rss [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]><rss><channel><item><title>&xxe;</title></item></channel></rss>";
        assertThatThrownBy(()->FeedParser.parse(xml.getBytes(StandardCharsets.UTF_8))).isInstanceOf(Exception.class);
    }
    @Test void omitsUnsafeLinksAndPreservesMissingDates()throws Exception {
        String xml="""
            <rss><channel><item><guid>one</guid><title>valid</title><link>https://example.com/a</link></item>
            <item><guid>two</guid><title>unsafe</title><link>javascript:alert(1)</link></item></channel></rss>
            """;
        var items=FeedParser.parse(xml.getBytes(StandardCharsets.UTF_8));
        assertThat(items).hasSize(1);
        assertThat(items.getFirst().publishedAt()).isNull();
    }
}
