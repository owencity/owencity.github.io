package com.devdesk.summary.source;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.apache.hc.client5.http.DnsResolver;

/** Fail-closed public HTTPS policy. Validated DNS answers are also the connection addresses. */
final class PublicWebPolicy implements DnsResolver {
    private static final ThreadPoolExecutor DNS = new ThreadPoolExecutor(0, 2, 30, TimeUnit.SECONDS,
        new SynchronousQueue<>(), task -> {
            Thread thread = new Thread(task, "article-dns");
            thread.setDaemon(true);
            return thread;
        });
    @FunctionalInterface
    interface Lookup { InetAddress[] resolve(String host) throws UnknownHostException; }
    private final Lookup lookup;

    PublicWebPolicy() { this(InetAddress::getAllByName); }
    PublicWebPolicy(Lookup lookup) { this.lookup = lookup; }

    static URI checkUri(String value) {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getRawUserInfo() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443) || value.length() > 4000
                    || value.chars().anyMatch(c -> c < 32 || c == 127)) {
                throw new IllegalArgumentException("Only public HTTPS article URLs are allowed");
            }
            host = host.toLowerCase(Locale.ROOT);
            // Literal hosts are intentionally excluded: article sources must have a public DNS name.
            if (host.contains(":") || host.contains("%") || host.matches("[0-9.]+")
                    || !host.contains(".") || host.endsWith(".")
                    || host.equals("localhost") || host.endsWith(".localhost")
                    || host.endsWith(".local") || host.endsWith(".internal")
                    || host.endsWith(".test") || host.endsWith(".invalid")) {
                throw new IllegalArgumentException("Article host is not a public DNS name");
            }
            // A fragment never needs to be sent upstream. Preserve escaped path/query bytes exactly.
            String normalized = uri.toASCIIString();
            int fragment = normalized.indexOf('#');
            return URI.create(fragment >= 0 ? normalized.substring(0, fragment) : normalized);
        } catch (NullPointerException failure) {
            throw new IllegalArgumentException("Missing article URL");
        }
    }

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        checkUri("https://" + host + "/");
        InetAddress[] addresses;
        java.util.concurrent.Future<InetAddress[]> work = null;
        try {
            work = DNS.submit(() -> lookup.resolve(host));
            addresses = work.get(3, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new UnknownHostException("Article DNS interrupted");
        } catch (Exception failure) {
            throw new UnknownHostException("Article DNS unavailable");
        } finally {
            if (work != null && !work.isDone()) work.cancel(true);
        }
        if (addresses == null || addresses.length == 0) throw new UnknownHostException("No public address");
        for (InetAddress address : addresses) {
            if (!isPublic(address)) throw new UnknownHostException("Non-public address denied");
        }
        return addresses; // HttpClient connects to these exact checked addresses; no second DNS lookup.
    }

    @Override
    public String resolveCanonicalHostname(String host) throws UnknownHostException {
        checkUri("https://" + host + "/");
        return host; // No reverse DNS lookup or canonical-name substitution.
    }

    static boolean isPublic(InetAddress address) {
        if (address == null || address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        int a = bytes[0] & 255, b = bytes[1] & 255;
        if (bytes.length == 4) {
            int c = bytes[2] & 255;
            return !(a == 0 || a == 10 || a == 127 || a >= 224
                    || (a == 100 && b >= 64 && b <= 127)
                    || (a == 169 && b == 254)
                    || (a == 172 && b >= 16 && b <= 31)
                    || (a == 192 && b == 168)
                    || (a == 192 && b == 0 && (c == 0 || c == 2))
                    || (a == 192 && b == 88 && c == 99)
                    || (a == 198 && (b == 18 || b == 19))
                    || (a == 198 && b == 51 && c == 100)
                    || (a == 203 && b == 0 && c == 113));
        }
        if (bytes.length != 16 || (a & 0xe0) != 0x20) return false;
        int c = bytes[2] & 255, d = bytes[3] & 255;
        // Global unicast only; also exclude special-purpose, documentation and transition ranges.
        return !((a == 0x20 && b == 0x01 && c <= 0x01)
                || (a == 0x20 && b == 0x01 && c == 0x0d && d == 0xb8)
                || (a == 0x20 && b == 0x02)
                || (a == 0x3f && b == 0xff && (c & 0xf0) == 0));
    }
}
