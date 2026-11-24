package com.devdesk.summary.source;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

/** Network-free guard tests can run without Spring or the HTML parser. */
class PublicWebPolicyTest {
    @ParameterizedTest
    @ValueSource(strings = {"http://example.com/a", "https://localhost/a", "https://169.254.169.254/latest/meta-data",
        "https://127.0.0.1/a", "https://[::1]/a", "https://[::ffff:127.0.0.1]/a",
        "https://[fe80::1%25eth0]/a", "https://example.com:8443/a", "https://user:pass@example.com/a",
        "https://2130706433/a", "https://service.internal/a", "file:///etc/passwd"})
    void rejectsUnsafeUrls(String url) {
        assertThatThrownBy(() -> PublicWebPolicy.checkUri(url)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.0.0.0", "10.0.0.1", "127.0.0.1", "100.100.100.200", "169.254.169.254",
        "172.16.0.1", "192.168.0.1", "192.0.0.10", "192.0.2.1", "192.88.99.1", "198.18.0.1",
        "198.51.100.1", "203.0.113.1", "224.0.0.1", "255.255.255.255", "::1", "fc00::1", "fe80::1",
        "::ffff:127.0.0.1", "64:ff9b::a00:1", "2001:db8::1", "2002:7f00:1::1", "2001::1", "3fff::1"})
    void rejectsNonPublicDnsAddresses(String address) throws Exception {
        assertThat(PublicWebPolicy.isPublic(InetAddress.getByName(address))).isFalse();
    }

    @Test void refusesMixedDnsAnswersAndReturnsExactCheckedPublicAddresses() throws Exception {
        InetAddress publicIp = InetAddress.getByName("93.184.216.34");
        InetAddress localIp = InetAddress.getByName("10.0.0.1");
        var mixed = new PublicWebPolicy(host -> new InetAddress[] {publicIp, localIp});
        assertThatThrownBy(() -> mixed.resolve("example.com")).isInstanceOf(UnknownHostException.class);
        InetAddress[] publicOnly = {publicIp, InetAddress.getByName("2606:4700:4700::1111")};
        assertThat(new PublicWebPolicy(host -> publicOnly).resolve("example.com")).isSameAs(publicOnly);
    }

    @Test void redirectResolutionRechecksPrivateTargetsAndPreservesEncodedQuery() {
        URI publicUri = PublicWebPolicy.checkUri("https://example.com/a?q=a%2Fb#fragment");
        assertThat(publicUri.toString()).isEqualTo("https://example.com/a?q=a%2Fb");
        assertThatThrownBy(() -> PublicWebPolicy.checkUri(publicUri.resolve("//127.0.0.1/private").toString()))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
