package com.devdesk.summary.source;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.util.Timeout;

/** Cookie-free, credential-free, public-only downloads. Redirect targets go through the same policy. */
final class PublicArticleFetcher implements ArticlePageFetcher {
    private static final Set<Integer> REDIRECTS = Set.of(301, 302, 303, 307, 308);
    private static final ScheduledExecutorService DEADLINES = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "article-deadline");
        thread.setDaemon(true);
        return thread;
    });
    private final int maxBytes;

    PublicArticleFetcher(int maxBytes) {
        if (maxBytes < 1024 || maxBytes > 8_388_608) throw new IllegalArgumentException("Invalid article byte limit");
        this.maxBytes = maxBytes;
    }

    @Override
    public ArticlePage fetch(URI initial) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        var manager = PoolingHttpClientConnectionManagerBuilder.create()
            .setDnsResolver(new PublicWebPolicy())
            .setMaxConnTotal(1).setMaxConnPerRoute(1)
            .setDefaultConnectionConfig(ConnectionConfig.custom()
                .setConnectTimeout(Timeout.ofSeconds(5)).setSocketTimeout(Timeout.ofSeconds(8)).build())
            .build();
        try (var client = HttpClients.custom().setConnectionManager(manager)
                .disableRedirectHandling().disableCookieManagement().disableAutomaticRetries()
                .disableContentCompression()
                .setConnectionReuseStrategy((request, response, context) -> false)
                .setDefaultRequestConfig(RequestConfig.custom()
                    .setConnectionRequestTimeout(Timeout.ofSeconds(3))
                    .setResponseTimeout(Timeout.ofSeconds(8)).build())
                .build()) {
            URI current = PublicWebPolicy.checkUri(initial.toString());
            for (int hop = 0; hop <= 4; hop++) {
                if (System.nanoTime() >= deadline) throw new IOException("Article deadline exceeded");
                HttpGet request = new HttpGet(current);
                request.setHeader("User-Agent", "DevDesk/0.1 personal-news-reader");
                request.setHeader("Accept", "text/html, application/xhtml+xml, text/plain;q=0.8");
                request.setHeader("Accept-Encoding", "identity");
                URI responseUri = current;
                var cancellation = DEADLINES.schedule(() -> request.cancel(),
                    Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                Download result;
                try {
                    result = client.execute(request, response -> {
                        int status = response.getCode();
                        if (REDIRECTS.contains(status)) {
                            Header location = response.getFirstHeader("Location");
                            if (location == null) throw new IOException("Redirect without location");
                            URI next = PublicWebPolicy.checkUri(responseUri.resolve(location.getValue()).toString());
                            return new Download(next, null);
                        }
                        if (status < 200 || status >= 300) throw new IOException("Article unavailable");
                        Header encoding = response.getFirstHeader("Content-Encoding");
                        if (encoding != null && !encoding.getValue().equalsIgnoreCase("identity"))
                            throw new IOException("Unsupported compressed article");
                        var entity = response.getEntity();
                        if (entity == null) throw new IOException("Empty article response");
                        ContentType type;
                        try { type = ContentType.parse(entity.getContentType()); }
                        catch (RuntimeException invalid) { throw new IOException("Invalid article content type"); }
                        String mime = type == null ? "" : type.getMimeType().toLowerCase(Locale.ROOT);
                        if (!Set.of("text/html", "application/xhtml+xml", "text/plain").contains(mime))
                            throw new IOException("Unsupported article type");
                        if (entity.getContentLength() > maxBytes) throw new IOException("Article exceeds byte limit");
                        Charset charset = type.getCharset();
                        try (InputStream stream = entity.getContent()) {
                            byte[] body = readBounded(stream, maxBytes, deadline);
                            return new Download(null, new ArticlePage(responseUri, body,
                                charset == null ? null : charset.name(), mime));
                        }
                    });
                } finally { cancellation.cancel(false); }
                if (result.page() != null) return result.page();
                current = result.redirect();
            }
            throw new IOException("Too many article redirects");
        }
    }

    static byte[] readBounded(InputStream stream, int maxBytes, long deadline) throws IOException {
        var bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = stream.read(buffer)) != -1) {
            if (System.nanoTime() >= deadline) throw new IOException("Article deadline exceeded");
            if (count > maxBytes - bytes.size()) throw new IOException("Article exceeds byte limit");
            bytes.write(buffer, 0, count);
        }
        return bytes.toByteArray();
    }

    private record Download(URI redirect, ArticlePage page) {}
}
