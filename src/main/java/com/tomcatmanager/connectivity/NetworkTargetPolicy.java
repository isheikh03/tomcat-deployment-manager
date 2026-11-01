package com.tomcatmanager.connectivity;

import org.springframework.stereotype.Component;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * Server-side request forgery guard for configured hosts and Manager URLs. Every address a
 * name resolves to must be permitted (a mixed answer is refused), and connections are then
 * made to the vetted address rather than re-resolving the name.
 */
@Component
public class NetworkTargetPolicy {
    private final ConnectivityProperties.TargetPolicy policy;
    private final List<Cidr> allowed = new ArrayList<>();
    private final List<Cidr> denied = new ArrayList<>();

    public NetworkTargetPolicy(ConnectivityProperties properties) {
        this.policy = properties.targetPolicy();
        policy.allowedCidrs().stream().filter(s -> !s.isBlank()).map(Cidr::parse).forEach(allowed::add);
        policy.deniedCidrs().stream().filter(s -> !s.isBlank()).map(Cidr::parse).forEach(denied::add);
    }

    /** Resolves {@code hostname} and returns the first address, or throws if any resolved address is not permitted. */
    public InetAddress resolve(String hostname) throws TargetNotAllowedException, UnknownHostException {
        if (hostname == null || hostname.isBlank()) throw new TargetNotAllowedException("No hostname configured.");
        InetAddress[] addresses = InetAddress.getAllByName(hostname.trim());
        for (InetAddress address : addresses) {
            String reason = rejectionReason(address);
            if (reason != null) {
                throw new TargetNotAllowedException(hostname + " resolves to " + address.getHostAddress() + ", which is not a permitted target (" + reason + ").");
            }
        }
        return addresses[0];
    }

    String rejectionReason(InetAddress address) {
        for (Cidr cidr : denied) if (cidr.contains(address)) return "listed in app.connection.target-policy.denied-cidrs";
        if (address.isAnyLocalAddress()) return "wildcard address";
        if (address.isMulticastAddress()) return "multicast address";
        if (address.isLoopbackAddress() && !policy.allowLoopback()) return "loopback addresses are disabled";
        if (isLinkLocal(address) && !policy.allowLinkLocal()) return "link-local addresses, including cloud metadata endpoints, are disabled";
        if (isPrivate(address) && !policy.allowPrivate()) return "private network addresses are disabled";
        if (!allowed.isEmpty() && allowed.stream().noneMatch(c -> c.contains(address))) return "not within app.connection.target-policy.allowed-cidrs";
        return null;
    }

    private static boolean isLinkLocal(InetAddress a) { return a.isLinkLocalAddress(); }

    private static boolean isPrivate(InetAddress a) {
        if (a.isSiteLocalAddress()) return true;
        if (a instanceof Inet6Address) return (a.getAddress()[0] & 0xFE) == 0xFC; // fc00::/7 unique local
        if (a instanceof Inet4Address) {
            byte[] b = a.getAddress();
            return (b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 64; // 100.64.0.0/10 carrier-grade NAT
        }
        return false;
    }

    /** Minimal IPv4/IPv6 CIDR matcher. */
    record Cidr(byte[] network, int prefix) {
        static Cidr parse(String text) {
            String[] parts = text.trim().split("/", 2);
            try {
                byte[] address = InetAddress.getByName(literal(parts[0])).getAddress();
                int prefix = parts.length == 2 ? Integer.parseInt(parts[1]) : address.length * 8;
                if (prefix < 0 || prefix > address.length * 8) throw new IllegalArgumentException("Invalid prefix in CIDR " + text);
                return new Cidr(address, prefix);
            } catch (UnknownHostException | NumberFormatException e) {
                throw new IllegalArgumentException("Invalid CIDR: " + text);
            }
        }
        private static String literal(String s) {
            if (!s.matches("[0-9A-Fa-f:.]+")) throw new IllegalArgumentException("CIDR must use an IP literal: " + s);
            return s;
        }
        boolean contains(InetAddress candidate) {
            byte[] c = candidate.getAddress();
            if (c.length != network.length) return false;
            int full = prefix / 8, rest = prefix % 8;
            for (int i = 0; i < full; i++) if (c[i] != network[i]) return false;
            if (rest == 0) return true;
            int mask = (0xFF << (8 - rest)) & 0xFF;
            return (c[full] & mask) == (network[full] & mask);
        }
    }
}
