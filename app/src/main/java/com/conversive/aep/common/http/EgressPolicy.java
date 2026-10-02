package com.conversive.aep.common.http;

import com.conversive.aep.common.ErrorCodes;
import com.conversive.aep.common.ExecutionMode;
import com.conversive.aep.common.NonRetryableError;
import com.conversive.aep.common.RetryableError;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;

/**
 * SSRF and mode-aware egress rules for {@link OutboundClient}. Outside {@code LIVE} a request goes out only when
 * the dry-run policy allowed it (request flag or {@link NonLiveEgress} permit), allow-listed hosts included. The address check resolves the host
 * before the call; DNS rebinding between check and connect is an accepted prototype gap.
 */
public final class EgressPolicy {

    private final List<String> allowHosts;
    private final List<String> selfHosts;

    public EgressPolicy(OutboundProperties properties) {
        this.allowHosts = normalize(properties.allowHosts());
        this.selfHosts = normalize(properties.selfHosts());
    }

    public void check(URI uri, ExecutionMode mode, boolean allowInNonLive) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw denied("scheme not allowed: " + scheme);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw denied("missing host");
        }
        if (matches(selfHosts, uri)) {
            throw denied("calls to the platform's own host are refused");
        }
        if (mode != ExecutionMode.LIVE && !allowInNonLive) {
            throw denied("egress not allowed by the dry-run policy in " + mode + " mode: " + host);
        }
        boolean allowListed = matches(allowHosts, uri);
        if (!allowListed) {
            requirePublicAddress(host);
        }
    }

    private static void requirePublicAddress(String host) {
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new RetryableError(ErrorCodes.UPSTREAM_IO, "cannot resolve host: " + host, null, e);
        }
        for (InetAddress address : addresses) {
            if (isInternal(address)) {
                throw denied("host resolves to a non-public address: " + host);
            }
        }
    }

    static boolean isInternal(InetAddress a) {
        if (a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
                || a.isAnyLocalAddress() || a.isMulticastAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        if (a instanceof Inet6Address) {
            if ((b[0] & 0xFE) == 0xFC) {
                return true;
            }
            byte[] embedded = embeddedIpv4(b);
            return embedded != null && isInternal(ipv4(embedded));
        }
        return isInternalIpv4(b);
    }

    /** The IPv4 carried by a NAT64 (64:ff9b::/96) or 6to4 (2002::/16) address, else null. */
    private static byte[] embeddedIpv4(byte[] b) {
        if (b[0] == 0x00 && b[1] == 0x64 && (b[2] & 0xFF) == 0xFF && (b[3] & 0xFF) == 0x9B) {
            for (int i = 4; i < 12; i++) {
                if (b[i] != 0) {
                    return null;
                }
            }
            return java.util.Arrays.copyOfRange(b, 12, 16);
        }
        if (b[0] == 0x20 && b[1] == 0x02) {
            return java.util.Arrays.copyOfRange(b, 2, 6);
        }
        return null;
    }

    private static InetAddress ipv4(byte[] four) {
        try {
            return InetAddress.getByAddress(four);
        } catch (UnknownHostException e) {
            throw new IllegalStateException("4 bytes is always a valid IPv4 address", e);
        }
    }

    private static boolean isInternalIpv4(byte[] b) {
        int first = b[0] & 0xFF;
        int second = b[1] & 0xFF;
        boolean carrierGradeNat = first == 100 && second >= 64 && second <= 127;
        return first == 0 || carrierGradeNat || (first == 192 && second == 0 && (b[2] & 0xFF) == 0);
    }

    private static boolean matches(List<String> entries, URI uri) {
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        String hostPort = host + ":" + effectivePort(uri);
        return entries.stream().anyMatch(e -> e.equals(host) || e.equals(hostPort));
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static List<String> normalize(List<String> hosts) {
        return hosts.stream().map(h -> h.trim().toLowerCase(Locale.ROOT)).filter(h -> !h.isEmpty()).toList();
    }

    private static NonRetryableError denied(String message) {
        return new NonRetryableError(ErrorCodes.EGRESS_DENIED, message);
    }
}
