package com.devdesk.summary.source;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class ArticleContentServiceTest {
    private static final String URL = "https://example.com/article";
    private static final String FEED = "피드에서 제공한 기술 설명입니다. 호환 버전과 주요 변경 사항을 함께 확인해야 합니다. ".repeat(5);

    @Test void extractsConditionsTablesAndCodeButExcludesNavigationAndHiddenContent() {
        String html = """
            <html><body><nav>광고 메뉴</nav><article><h1>Runtime update</h1>
            <p>Only Java 21 or later is supported. Breaking changes require a migration.</p>
            <h2>Measurements</h2><table><tr><th>Threads</th><th>Latency</th></tr>
            <tr><td>16 threads</td><td>p99 45ms</td></tr></table>
            <pre>if (version &gt;= 21) {\n    upgrade();\n}</pre>
            <p>This benchmark uses a warm cache and does not measure cold startup.</p>
            <div hidden>hidden secret</div><p style="display: none">invisible content</p>
            <script>runEvil()</script><section id="comments">unrelated user comment</section>
            </article></body></html>
            """;
        var service = new ArticleContentService(uri -> page(uri, html), 10_000, 100);
        SourceContent content = service.fetch("Runtime update", URL, FEED);
        assertThat(content.scope()).isEqualTo("ARTICLE_BODY");
        assertThat(content.url()).isEqualTo(URL);
        assertThat(content.text()).contains("Only Java 21", "Breaking changes", "16 threads", "p99 45ms",
            "warm cache", "if (version >= 21) {\n    upgrade();\n}");
        assertThat(content.text()).doesNotContain("광고 메뉴", "hidden secret", "invisible content", "runEvil", "unrelated user");
        assertThat(content.warning()).contains("이미지");
    }

    @Test void preservesCodeTabsIndentationAndInternalBlankLinesAcrossSyntaxSpans() throws Exception {
        String html = "<article>\n  <p>  Before   the\n code. </p>\n"
            + "<pre><code><span class='keyword'>if</span> (ready) {\n\t    "
            + "<span class='function'>run</span>();\n\n\n\tfinish();\n}</code></pre>\n"
            + "  <p> After\t the    code. </p>\n</article>";
        var service = new ArticleContentService(uri -> page(uri, html), 10_000, 100);

        assertThat(service.extract(page(URI.create(URL), html)))
            .isEqualTo("Before the code.\nif (ready) {\n\t    run();\n\n\n\tfinish();\n}\nAfter the code.");
    }

    @Test void preservesCodeWhitespaceAtArticleEdges() throws Exception {
        String code = "    first();\n\n\n\tsecond();  \n\t";
        String html = "<article><pre><code>" + code + "</code></pre></article>";
        var service = new ArticleContentService(uri -> page(uri, html), 10_000, 100);

        assertThat(service.extract(page(URI.create(URL), html))).isEqualTo(code);
    }

    @Test void preservesInlineCodeSpacingWithoutExpandingProseWhitespace() throws Exception {
        String html = "<article><p>  Use   <code>key\t  value</code>   exactly. </p></article>";
        var service = new ArticleContentService(uri -> page(uri, html), 10_000, 100);

        assertThat(service.extract(page(URI.create(URL), html))).isEqualTo("Use key\t  value exactly.");
    }

    @Test void separatesBlockCodeLineWrappersWithoutLosingTheirIndentation() throws Exception {
        String html = "<article><pre><code><div><span>first</span>();</div>"
            + "<div>\t    <span>second</span>();</div></code></pre></article>";
        var service = new ArticleContentService(uri -> page(uri, html), 10_000, 100);

        assertThat(service.extract(page(URI.create(URL), html))).isEqualTo("first();\n\t    second();");
    }

    @Test void fetchesOnlyRecognizedGeekNewsTitleSourceLink() {
        var requested = new ArrayList<URI>();
        var service = new ArticleContentService(uri -> {
            requested.add(uri);
            if (uri.getHost().equals("news.hada.io")) return page(uri,
                "<div class='topictitle'><a href='https://example.com/original'>Original source</a></div>"
                    + "<div id='comments'><a rel='external' href='https://other.example.com'>Comment</a></div>");
            return page(uri, "<article><p>" + FEED + "</p></article>");
        }, 10_000, 100);
        SourceContent content = service.fetch("Post", "https://news.hada.io/topic?id=1", FEED);
        assertThat(requested).containsExactly(URI.create("https://news.hada.io/topic?id=1"), URI.create("https://example.com/original"));
        assertThat(content.url()).isEqualTo("https://example.com/original");
        assertThat(content.scope()).isEqualTo("ARTICLE_BODY");
    }

    @Test void missingOriginalLinkDoesNotSummarizeGeekNewsCommentsAsArticle() {
        var service = new ArticleContentService(uri -> page(uri,
            "<main><div id='comments'><a rel='external' href='https://example.com/unrelated'>Comment</a>" + FEED + "</div></main>"),
            10_000, 100);
        SourceContent content = service.fetch("Post", "https://news.hada.io/topic?id=2", FEED);
        assertThat(content.scope()).isEqualTo("FEED_DESCRIPTION");
        assertThat(content.text()).isEqualTo(FEED.strip());
    }

    @Test void failedFetchUsesExplicitlyLabeledFeedContentWithoutClaimingFullArticle() {
        var service = new ArticleContentService(uri -> { throw new IOException("Forbidden"); }, 10_000, 100);
        SourceContent content = service.fetch("Post", URL, FEED);
        assertThat(content.scope()).isEqualTo("FEED_DESCRIPTION");
        assertThat(content.warning()).contains("RSS 설명");
        assertThat(content.text()).isEqualTo(FEED.strip());
    }

    @Test void feedFallbackPreservesTechnicalGenericTypeSyntax() {
        var service = new ArticleContentService(uri -> { throw new IOException(); }, 10_000, 100);
        String feed = "Use List<T> when the input size is < 100; preserve the bounds and types. " + FEED;
        assertThat(service.fetch("Post", URL, feed).text()).contains("List<T>", "< 100");
    }

    @Test void oversizedArticleFallsBackInsteadOfSilentlyTruncating() {
        var service = new ArticleContentService(uri -> page(uri,
            "<article>" + "very long important source text ".repeat(100) + "</article>"), 500, 100);
        SourceContent content = service.fetch("Post", URL, FEED);
        assertThat(content.scope()).isEqualTo("FEED_DESCRIPTION");
        assertThat(content.warning()).contains("길이 제한");
        assertThat(content.text()).isEqualTo(FEED.strip());
    }

    @Test void insufficientInputFailsWithoutInventingAFullArticle() {
        var service = new ArticleContentService(uri -> { throw new IOException(); }, 500, 100);
        assertThatThrownBy(() -> service.fetch("Post", URL, "short title"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("확보하지 못했습니다");
    }

    @Test void paywallFallsBackWithoutUsingHiddenSubscriberText() {
        var service = new ArticleContentService(uri -> page(uri,
            "<article>" + FEED + "</article><div class='paywall'>Subscribe</div>"), 10_000, 100);
        assertThat(service.fetch("Post", URL, FEED).scope()).isEqualTo("FEED_DESCRIPTION");
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://example.com/a", "https://localhost/a", "https://169.254.169.254/latest/meta-data",
        "https://127.0.0.1/a", "https://[::1]/a", "https://[::ffff:127.0.0.1]/a",
        "https://[fe80::1%25eth0]/a", "https://example.com:8443/a", "https://user:pass@example.com/a",
        "https://2130706433/a", "https://service.internal/a", "file:///etc/passwd"})
    void rejectsUnsafeUrlsBeforeAnyFetch(String url) {
        var service = new ArticleContentService(uri -> { throw new AssertionError("No request should be sent"); }, 10_000, 100);
        assertThatThrownBy(() -> service.fetch("Post", url, FEED)).isInstanceOf(IllegalArgumentException.class);
    }

    private static ArticlePage page(URI uri, String html) {
        return new ArticlePage(uri, html.getBytes(StandardCharsets.UTF_8), "UTF-8", "text/html");
    }
}
