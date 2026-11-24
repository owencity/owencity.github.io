package com.devdesk;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

/** Bounded responses, deadline, no redirects, and no response bodies in error messages. */
public final class HttpFetch {
    private static final HttpClient CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(7)).followRedirects(HttpClient.Redirect.NEVER).build();
    private HttpFetch() {}

    public record Response(int status, byte[] body) {}

    public static byte[] send(HttpRequest request, int maxBytes) throws IOException {
        Response response = exchange(request, maxBytes);
        if (response.status() / 100 != 2) throw new IOException("Upstream HTTP " + response.status());
        return response.body();
    }

    /** Status-aware response for conditional upstream mutations. No automatic retries. */
    public static Response exchange(HttpRequest request, int maxBytes) throws IOException {
        var future = CLIENT.sendAsync(request, info -> new LimitedBody(maxBytes));
        try {
            var response = future.orTimeout(25, TimeUnit.SECONDS).join();
            return new Response(response.statusCode(), response.body());
        } catch (RuntimeException failure) {
            future.cancel(true);
            throw new IOException("Upstream request failed or exceeded deadline", failure);
        }
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        LimitedBody(int limit) { this.limit = limit; }
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(Long.MAX_VALUE); }
        public void onNext(List<ByteBuffer> chunks) {
            for (ByteBuffer chunk : chunks) {
                if (chunk.remaining() > limit - buffer.size()) {
                    subscription.cancel(); result.completeExceptionally(new IOException("Upstream response too large")); return;
                }
                byte[] bytes = new byte[chunk.remaining()]; chunk.get(bytes); buffer.writeBytes(bytes);
            }
        }
        public void onError(Throwable failure) { result.completeExceptionally(failure); }
        public void onComplete() { result.complete(buffer.toByteArray()); }
    }
}
