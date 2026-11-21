package com.iitsm.infrastructure.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The client address a rate limit is counted against (ADR-046).
 *
 * <p>By default ({@code trustedProxyHops = 0}) it is the address of the TCP peer, and {@code X-Forwarded-For} is
 * ignored: any client can write that header, so trusting it would let an attacker pick a fresh address for every
 * attempt. Behind {@code N} reverse proxies that each append the address they received the request from, the client
 * is the {@code N}-th entry from the right of {@code X-Forwarded-For} (entries further left were written by the
 * client and are not trusted). A header that is missing, too short or holds something that is not an IPv4 or IPv6
 * address falls back to the TCP peer.
 */
public final class ClientIpResolver {

    static final String FORWARDED_FOR = "X-Forwarded-For";
    private static final String UNKNOWN = "unknown";
    private static final Pattern IP_ADDRESS = Pattern.compile("[0-9A-Fa-f:.]{2,45}");

    private final int trustedProxyHops;

    public ClientIpResolver(int trustedProxyHops) {
        if (trustedProxyHops < 0) {
            throw new IllegalArgumentException("trustedProxyHops must not be negative");
        }
        this.trustedProxyHops = trustedProxyHops;
    }

    public String resolve(HttpServletRequest request) {
        Objects.requireNonNull(request, "request cannot be null");
        String peer = Objects.requireNonNullElse(request.getRemoteAddr(), UNKNOWN);
        if (trustedProxyHops == 0) {
            return peer;
        }
        List<String> chain = forwardedChain(request);
        if (chain.size() < trustedProxyHops) {
            return peer;
        }
        String candidate = chain.get(chain.size() - trustedProxyHops);
        return IP_ADDRESS.matcher(candidate).matches() ? candidate : peer;
    }

    /** Every entry of every {@code X-Forwarded-For} header, in order (several headers count as one list). */
    private static List<String> forwardedChain(HttpServletRequest request) {
        List<String> chain = new ArrayList<>();
        Enumeration<String> headers = request.getHeaders(FORWARDED_FOR);
        while (headers != null && headers.hasMoreElements()) {
            for (String entry : headers.nextElement().split(",", -1)) {
                chain.add(entry.strip());
            }
        }
        return chain;
    }
}
