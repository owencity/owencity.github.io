package com.devdesk.summary.source;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.Locale;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ArticleContentService {
    private final ArticlePageFetcher fetcher;
    private final int maxChars;
    private final int minChars;

    @Autowired
    public ArticleContentService(
            @Value("${devdesk.summary.source-max-bytes:2097152}") int maxBytes,
            @Value("${devdesk.summary.source-max-chars:120000}") int maxChars,
            @Value("${devdesk.summary.source-min-chars:200}") int minChars) {
        this(new PublicArticleFetcher(maxBytes), maxChars, minChars);
    }

    ArticleContentService(ArticlePageFetcher fetcher, int maxChars, int minChars) {
        if (minChars < 30 || maxChars < minChars || maxChars > 500_000)
            throw new IllegalArgumentException("Invalid article text limits");
        this.fetcher = fetcher;
        this.maxChars = maxChars;
        this.minChars = minChars;
    }

    public SourceContent fetch(String title, String url, String feedDescription) {
        URI source = PublicWebPolicy.checkUri(url);
        try {
            ArticlePage page = fetcher.fetch(source);
            if (isGeekNewsTopic(page.uri())) {
                Document topic = parse(page);
                URI original = originalGeekNewsLink(topic, page.uri());
                if (original == null) return fallback(title, source,
                    feedDescription, "연결된 원문을 확인하지 못해 RSS 설명을 기준으로 정리했습니다.");
                page = fetcher.fetch(original);
            }
            String body = extract(page);
            if (body.length() < minChars) throw new IOException("Not enough article text");
            if (body.length() > maxChars) return fallback(title, source, feedDescription,
                "원문이 처리 길이 제한을 넘어 RSS 설명을 기준으로 정리했습니다. 원문 전체 요약이 아닙니다.");
            return new SourceContent(title, page.uri().toString(), body, "ARTICLE_BODY",
                "웹페이지에서 추출한 텍스트 기준입니다. 이미지·동영상·첨부파일의 내용은 포함되지 않습니다.");
        } catch (IOException | IllegalArgumentException failure) {
            return fallback(title, source, feedDescription,
                "원문 본문을 확보하지 못해 RSS 설명을 기준으로 정리했습니다. 원문에서 세부 내용을 확인하세요.");
        }
    }

    private SourceContent fallback(String title, URI source, String description, String warning) {
        // FeedParser already removed markup. Preserve technical <T>, operators and whitespace here.
        String text = description == null ? "" : description.strip();
        if (text.length() < minChars) throw new IllegalStateException("요약할 원문이나 충분한 RSS 설명을 확보하지 못했습니다.");
        if (text.length() > maxChars) throw new IllegalStateException("요약 입력이 처리 길이 제한을 초과했습니다. 내용을 임의로 자르지 않았습니다.");
        return new SourceContent(title, source.toString(), text, "FEED_DESCRIPTION", warning);
    }

    private static boolean isGeekNewsTopic(URI uri) {
        return "news.hada.io".equalsIgnoreCase(uri.getHost()) && "/topic".equals(uri.getPath());
    }

    static URI originalGeekNewsLink(Document document, URI base) {
        // Only the post title's outbound source link; never follow arbitrary links from comments.
        for (Element link : document.select(".topictitle a[href], .topic-title a[href], .topicurl a[href]")) {
            try {
                URI candidate = PublicWebPolicy.checkUri(base.resolve(link.attr("href")).toString());
                if (!candidate.getHost().equalsIgnoreCase(base.getHost())) return candidate;
            } catch (IllegalArgumentException ignored) { /* Skip unsafe or non-source links. */ }
        }
        return null;
    }

    static Document parse(ArticlePage page) throws IOException {
        return Jsoup.parse(new ByteArrayInputStream(page.bytes()), page.charset(), page.uri().toString());
    }

    String extract(ArticlePage page) throws IOException {
        if ("text/plain".equals(page.mime())) {
            Charset charset = page.charset() == null ? StandardCharsets.UTF_8 : Charset.forName(page.charset());
            return new String(page.bytes(), charset).strip();
        }
        Document document = parse(page);
        if (!document.select("meta[itemprop=isAccessibleForFree][content=false],.paywall,#paywall,.subscription-wall").isEmpty())
            throw new IOException("Article requires access");
        // Do not execute scripts, reveal hidden text, or include comments/navigation in the model input.
        document.select("script,style,noscript,template,svg,nav,footer,aside,form,button,input,select,textarea,"
            + "[hidden],[aria-hidden=true],.comments,#comments,.comment-list,.advertisement,.cookie-banner,"
            + ".related-posts,.related-articles,.recommended-articles,.social-share,.share-buttons").remove();
        document.select("[style]").stream().filter(element -> {
            String style = element.attr("style").replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
            return style.contains("display:none") || style.contains("visibility:hidden");
        }).toList().forEach(Element::remove);
        var candidates = document.select("[itemprop=articleBody],article,.post-content,.entry-content,.article-content,.markdown-body");
        if (candidates.isEmpty()) candidates = document.select("main,[role=main]");
        Element article = candidates.stream().max(Comparator.comparingInt(element -> element.text().length()))
            .orElse(null);
        if (article == null) throw new IOException("Article body could not be identified");
        TextOutput result = new TextOutput();
        appendText(article, result, false);
        return result.finish();
    }

    private static void appendText(Node node, TextOutput text, boolean preformatted) {
        if (node instanceof TextNode leaf) {
            if (preformatted) text.literal(leaf.getWholeText());
            else text.prose(leaf.getWholeText());
            return;
        }
        if (!(node instanceof Element element)) return;
        String tag = element.tagName().toLowerCase(Locale.ROOT);
        if (tag.equals("br")) {
            if (preformatted) text.literal("\n");
            else text.lineBreak();
            return;
        }
        // Block code-line wrappers still need separators; inline syntax spans preserve raw text.
        // lineBreak() never trims literal whitespace or collapses existing blank lines.
        boolean block = element.isBlock();
        if (block) text.lineBreak();
        boolean isPre = preformatted || tag.equals("pre") || tag.equals("code");
        for (Node child : element.childNodes()) appendText(child, text, isPre);
        if (!isPre && (tag.equals("td") || tag.equals("th"))) text.prose(" | ");
        if (block) text.lineBreak();
    }

    /** Normalize prose as it is appended; never run whitespace cleanup over verbatim code. */
    private static final class TextOutput {
        private final StringBuilder value = new StringBuilder();
        private int preservedThrough;

        void literal(String text) {
            value.append(text);
            preservedThrough = value.length();
        }

        void prose(String text) {
            for (int index = 0; index < text.length(); index++) {
                char current = text.charAt(index);
                if (Character.isWhitespace(current) || current == '\u00a0') {
                    if (!value.isEmpty() && !Character.isWhitespace(value.charAt(value.length() - 1)))
                        value.append(' ');
                } else {
                    value.append(current);
                }
            }
        }

        void lineBreak() {
            while (value.length() > preservedThrough && value.charAt(value.length() - 1) == ' ')
                value.setLength(value.length() - 1);
            if (!value.isEmpty() && value.charAt(value.length() - 1) != '\n') value.append('\n');
        }

        String finish() {
            while (value.length() > preservedThrough && Character.isWhitespace(value.charAt(value.length() - 1)))
                value.setLength(value.length() - 1);
            return value.toString();
        }
    }
}
