package com.cadence.app.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientIpResolverTest {

    private static ClientIpResolver resolver(String... trusted) {
        return new ClientIpResolver(new RateLimitProperties(5, 3, 20, 20, 1000, List.of(trusted)));
    }

    private static MockHttpServletRequest request(String peer, String... forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(peer);
        for (String value : forwardedFor) {
            request.addHeader("X-Forwarded-For", value);
        }
        return request;
    }

    @Test
    void usesThePeerAddressByDefault() {
        assertThat(resolver().resolve(request("198.51.100.7"))).isEqualTo("198.51.100.7");
    }

    @Test
    void ignoresForwardedForFromAnUntrustedPeer() {
        // The spoofing case: with no trusted proxies a client-supplied header must not change its identity.
        assertThat(resolver().resolve(request("198.51.100.7", "1.2.3.4"))).isEqualTo("198.51.100.7");
        assertThat(resolver("10.0.0.0/8").resolve(request("198.51.100.7", "10.1.1.1, 1.2.3.4")))
                .isEqualTo("198.51.100.7");
    }

    @Test
    void takesTheRightMostUntrustedAddressWhenThePeerIsATrustedProxy() {
        ClientIpResolver resolver = resolver("10.0.0.0/8");
        assertThat(resolver.resolve(request("10.0.0.5", "203.0.113.9"))).isEqualTo("203.0.113.9");
        // A client-supplied left part cannot win over what the proxy appended.
        assertThat(resolver.resolve(request("10.0.0.5", "6.6.6.6, 203.0.113.9"))).isEqualTo("203.0.113.9");
    }

    @Test
    void skipsTrustedHopsFromTheRight() {
        ClientIpResolver resolver = resolver("10.0.0.0/8", "192.168.1.1");
        assertThat(resolver.resolve(request("10.0.0.5", "6.6.6.6, 203.0.113.9, 10.2.2.2, 192.168.1.1")))
                .isEqualTo("203.0.113.9");
    }

    @Test
    void joinsRepeatedForwardedForHeaders() {
        assertThat(resolver("10.0.0.0/8").resolve(request("10.0.0.5", "6.6.6.6", "203.0.113.9")))
                .isEqualTo("203.0.113.9");
    }

    @Test
    void fallsBackToThePeerWhenTheTrustedProxySentNothingUsable() {
        ClientIpResolver resolver = resolver("10.0.0.0/8");
        assertThat(resolver.resolve(request("10.0.0.5"))).isEqualTo("10.0.0.5");
        assertThat(resolver.resolve(request("10.0.0.5", ""))).isEqualTo("10.0.0.5");
        assertThat(resolver.resolve(request("10.0.0.5", "10.1.1.1, 10.2.2.2"))).isEqualTo("10.0.0.5");
    }

    @Test
    void malformedEntriesOnThePathFallBackToThePeer() {
        ClientIpResolver resolver = resolver("10.0.0.0/8");
        assertThat(resolver.resolve(request("10.0.0.5", "203.0.113.9, not-an-ip"))).isEqualTo("10.0.0.5");
        assertThat(resolver.resolve(request("10.0.0.5", "999.1.1.1"))).isEqualTo("10.0.0.5");
        assertThat(resolver.resolve(request("10.0.0.5", "evil.example.com"))).isEqualTo("10.0.0.5");
        assertThat(resolver.resolve(request("10.0.0.5", "1.2.3"))).isEqualTo("10.0.0.5");
        assertThat(resolver.resolve(request("10.0.0.5", ":::"))).isEqualTo("10.0.0.5");
        assertThat(resolver.resolve(request("10.0.0.5", "203.0.113.9,"))).isEqualTo("10.0.0.5");
    }

    @Test
    void entriesLeftOfTheFirstUntrustedHopAreNeverParsed() {
        assertThat(resolver("10.0.0.0/8").resolve(request("10.0.0.5", "garbage, 203.0.113.9"))).isEqualTo("203.0.113.9");
    }

    @Test
    void acceptsPortsOnForwardedEntries() {
        ClientIpResolver resolver = resolver("10.0.0.0/8");
        assertThat(resolver.resolve(request("10.0.0.5", "203.0.113.9:51234"))).isEqualTo("203.0.113.9");
        assertThat(resolver.resolve(request("10.0.0.5", "[2001:db8::7]:443"))).isEqualTo("2001:db8:0:0:0:0:0:7");
    }

    @Test
    void handlesIpv6PeersAndChains() {
        ClientIpResolver resolver = resolver("2001:db8:ffff::/48", "::1");
        // Tomcat reports the IPv6 loopback in the long form.
        assertThat(resolver.resolve(request("0:0:0:0:0:0:0:1", "2001:db8:1::5"))).isEqualTo("2001:db8:1:0:0:0:0:5");
        assertThat(resolver.resolve(request("2001:db8:ffff::1", "2001:DB8:1::5"))).isEqualTo("2001:db8:1:0:0:0:0:5");
        assertThat(resolver.resolve(request("2001:db8:abcd::1", "2001:db8:1::5"))).isEqualTo("2001:db8:abcd:0:0:0:0:1");
        assertThat(resolver.resolve(request("2001:db8:ffff::1", "2001:db8:1::5, 2001:db8:ffff::2")))
                .isEqualTo("2001:db8:1:0:0:0:0:5");
    }

    @Test
    void sameIpv6AddressInDifferentSpellingsIsOneIdentity() {
        ClientIpResolver resolver = resolver();
        assertThat(resolver.resolve(request("2001:db8::1"))).isEqualTo(resolver.resolve(request("2001:0DB8:0:0:0:0:0:1")));
    }

    @Test
    void ipv4MappedIpv6IsTreatedAsIpv4() {
        ClientIpResolver resolver = resolver("10.0.0.0/8");
        assertThat(resolver.resolve(request("::ffff:198.51.100.7"))).isEqualTo("198.51.100.7");
        assertThat(resolver.resolve(request("::ffff:10.0.0.5", "203.0.113.9"))).isEqualTo("203.0.113.9");
    }

    @Test
    void cidrBoundariesAreRespected() {
        ClientIpResolver resolver = resolver("172.16.0.0/12", "192.0.2.4/30", "203.0.113.50");
        assertThat(resolver.resolve(request("172.31.255.255", "9.9.9.9"))).isEqualTo("9.9.9.9");
        assertThat(resolver.resolve(request("172.32.0.1", "9.9.9.9"))).isEqualTo("172.32.0.1");
        assertThat(resolver.resolve(request("192.0.2.7", "9.9.9.9"))).isEqualTo("9.9.9.9");
        assertThat(resolver.resolve(request("192.0.2.8", "9.9.9.9"))).isEqualTo("192.0.2.8");
        assertThat(resolver.resolve(request("203.0.113.50", "9.9.9.9"))).isEqualTo("9.9.9.9");
        assertThat(resolver.resolve(request("203.0.113.51", "9.9.9.9"))).isEqualTo("203.0.113.51");
    }

    @Test
    void rejectsInvalidTrustedProxyConfiguration() {
        for (String bad : List.of("not-an-ip", "10.0.0.0/33", "10.0.0.0/x", "10.0.0.0/-1", "::1/129", "", "10.0.0.1:80")) {
            assertThatThrownBy(() -> resolver(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void masksAddressesForTheAuditLog() {
        assertThat(IpAddresses.mask("203.0.113.77")).isEqualTo("203.0.113.0");
        assertThat(IpAddresses.mask("2001:db8:1:2:aaaa:bbbb:cccc:dddd")).isEqualTo("2001:db8:1:2::");
        assertThat(IpAddresses.mask("garbage")).isEqualTo("unknown");
    }
}
