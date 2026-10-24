package com.cadence.app.ratelimit;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

/**
 * Strict IP literal handling. Nothing here ever performs a DNS lookup: a value is only handed to
 * {@link InetAddress#getByName} after it was checked to be an IPv4 dotted quad or consist of IPv6 literal characters.
 * IPv4-mapped IPv6 addresses ({@code ::ffff:1.2.3.4}) are normalized to IPv4.
 */
final class IpAddresses {

    private static final Pattern IPV4 = Pattern.compile("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})");
    private static final Pattern IPV4_WITH_PORT = Pattern.compile("(\\d{1,3}(?:\\.\\d{1,3}){3}):\\d{1,5}");
    private static final Pattern IPV6_CHARS = Pattern.compile("[0-9a-fA-F:.]{2,45}");

    private IpAddresses() {
    }

    /** 4 bytes (IPv4) or 16 bytes (IPv6), or {@code null} when {@code raw} is not an IP literal. */
    static byte[] parse(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.startsWith("[")) {
            int end = value.indexOf(']');
            if (end < 0) {
                return null;
            }
            String rest = value.substring(end + 1);
            if (!rest.isEmpty() && !rest.matches(":\\d{1,5}")) {
                return null;
            }
            value = value.substring(1, end);
            if (!value.contains(":")) {
                return null;
            }
        } else if (IPV4_WITH_PORT.matcher(value).matches()) {
            value = value.substring(0, value.lastIndexOf(':'));
        }
        if (value.contains(":")) {
            if (!IPV6_CHARS.matcher(value).matches()) {
                return null;
            }
        } else {
            var m = IPV4.matcher(value);
            if (!m.matches()) {
                return null;
            }
            for (int i = 1; i <= 4; i++) {
                if (Integer.parseInt(m.group(i)) > 255) {
                    return null;
                }
            }
        }
        try {
            return InetAddress.getByName(value).getAddress();
        } catch (UnknownHostException ex) {
            return null;
        }
    }

    /** Canonical text: dotted quad, or eight colon-separated hex groups without zero compression. */
    static String format(byte[] address) {
        StringBuilder sb = new StringBuilder();
        if (address.length == 4) {
            for (int i = 0; i < 4; i++) {
                sb.append(i == 0 ? "" : ".").append(address[i] & 0xff);
            }
        } else {
            for (int i = 0; i < 16; i += 2) {
                sb.append(i == 0 ? "" : ":").append(Integer.toHexString((address[i] & 0xff) << 8 | address[i + 1] & 0xff));
            }
        }
        return sb.toString();
    }

    /**
     * Coarse form for the audit log: the last IPv4 octet is zeroed, an IPv6 address keeps its /64 prefix. Anything
     * that is not an IP literal is reported as {@code unknown}.
     */
    static String mask(String ip) {
        byte[] address = parse(ip);
        if (address == null) {
            return "unknown";
        }
        if (address.length == 4) {
            return (address[0] & 0xff) + "." + (address[1] & 0xff) + "." + (address[2] & 0xff) + ".0";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i += 2) {
            sb.append(Integer.toHexString((address[i] & 0xff) << 8 | address[i + 1] & 0xff)).append(':');
        }
        return sb.append(':').toString();
    }
}
