package com.cadence.app.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Determines the client IP used as a rate-limit identity.
 *
 * <p>The TCP peer ({@link HttpServletRequest#getRemoteAddr()}) is the default and the only value a client cannot
 * forge. {@code X-Forwarded-For} is honoured only when the peer itself is a configured trusted proxy
 * ({@code app.rate-limit.trusted-proxies}); the chain is then walked from the right and the first address that is not
 * a trusted proxy is the client (entries further left are client-supplied and ignored). When the chain contains a
 * malformed entry on the way, or consists only of trusted proxies, the peer address is used.
 */
@Component
public class ClientIpResolver {

    static final String FORWARDED_FOR = "X-Forwarded-For";

    private final List<Cidr> trustedProxies;

    public ClientIpResolver(RateLimitProperties properties) {
        this.trustedProxies = properties.trustedProxies().stream().map(Cidr::parse).toList();
    }

    public String resolve(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        byte[] peer = IpAddresses.parse(remote);
        if (peer == null) {
            return remote == null || remote.isBlank() ? "unknown" : remote;
        }
        if (!isTrusted(peer)) {
            return IpAddresses.format(peer);
        }
        List<String> entries = forwardedChain(request);
        for (int i = entries.size() - 1; i >= 0; i--) {
            byte[] hop = IpAddresses.parse(entries.get(i));
            if (hop == null) {
                return IpAddresses.format(peer);
            }
            if (!isTrusted(hop)) {
                return IpAddresses.format(hop);
            }
        }
        return IpAddresses.format(peer);
    }

    private boolean isTrusted(byte[] address) {
        for (Cidr cidr : trustedProxies) {
            if (cidr.contains(address)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> forwardedChain(HttpServletRequest request) {
        List<String> entries = new ArrayList<>();
        for (String header : Collections.list(request.getHeaders(FORWARDED_FOR))) {
            for (String part : header.split(",", -1)) {
                entries.add(part.trim());
            }
        }
        return entries;
    }

    /** An address prefix; a bare address is a /32 (IPv4) or /128 (IPv6). */
    record Cidr(byte[] network, int prefixBits) {

        static Cidr parse(String spec) {
            String value = spec == null ? "" : spec.trim();
            int slash = value.indexOf('/');
            String addressPart = slash < 0 ? value : value.substring(0, slash);
            byte[] address = IpAddresses.parse(addressPart);
            if (address == null || addressPart.contains("[") || addressPart.matches("\\d+\\.\\d+\\.\\d+\\.\\d+:\\d+")) {
                throw new IllegalArgumentException("app.rate-limit.trusted-proxies: not an IP address or CIDR: '" + spec + "'");
            }
            int max = address.length * 8;
            int bits = max;
            if (slash >= 0) {
                try {
                    bits = Integer.parseInt(value.substring(slash + 1));
                } catch (NumberFormatException ex) {
                    throw new IllegalArgumentException("app.rate-limit.trusted-proxies: bad prefix length in '" + spec + "'");
                }
                if (bits < 0 || bits > max) {
                    throw new IllegalArgumentException("app.rate-limit.trusted-proxies: prefix length out of range in '" + spec + "'");
                }
            }
            return new Cidr(address, bits);
        }

        boolean contains(byte[] address) {
            if (address.length != network.length) {
                return false;
            }
            int fullBytes = prefixBits / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (address[i] != network[i]) {
                    return false;
                }
            }
            int rest = prefixBits % 8;
            if (rest == 0) {
                return true;
            }
            int mask = 0xff << (8 - rest) & 0xff;
            return (address[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }
}
