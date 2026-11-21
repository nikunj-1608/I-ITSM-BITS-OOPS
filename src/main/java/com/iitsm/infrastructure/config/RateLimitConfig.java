package com.iitsm.infrastructure.config;

import com.iitsm.infrastructure.security.ClientIpResolver;
import com.iitsm.infrastructure.security.InMemoryRateLimiter;
import com.iitsm.infrastructure.security.RateLimitRules;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the per-address rate limits of the sign-in endpoints from {@code iitsm.security.rate-limit.*} (ADR-046):
 * one in-memory token bucket limiter per route. A value that is not positive stops the start-up with a message that
 * names the property. On by default in the prod profile only.
 */
@Configuration
public class RateLimitConfig {

    public static final String LOGIN_PATH = "/api/auth/login";
    public static final String REGISTER_PATH = "/api/auth/register";
    public static final String REFRESH_PATH = "/api/auth/refresh";
    public static final String MFA_VERIFY_PATH = "/api/auth/mfa/verify";

    private static final String PREFIX = "iitsm.security.rate-limit.";

    private final boolean enabled;
    private final int trustedProxyHops;
    private final int maxTrackedClients;
    private final Limit login;
    private final Limit register;
    private final Limit refresh;
    private final Limit mfa;

    @SuppressWarnings("java:S107") // one constructor argument per configuration property, by design
    public RateLimitConfig(
            @Value("${iitsm.security.rate-limit.enabled:false}") boolean enabled,
            @Value("${iitsm.security.rate-limit.trusted-proxy-hops:0}") int trustedProxyHops,
            @Value("${iitsm.security.rate-limit.max-tracked-clients:10000}") int maxTrackedClients,
            @Value("${iitsm.security.rate-limit.login-capacity:10}") int loginCapacity,
            @Value("${iitsm.security.rate-limit.login-window-seconds:60}") long loginWindowSeconds,
            @Value("${iitsm.security.rate-limit.register-capacity:5}") int registerCapacity,
            @Value("${iitsm.security.rate-limit.register-window-seconds:600}") long registerWindowSeconds,
            @Value("${iitsm.security.rate-limit.refresh-capacity:60}") int refreshCapacity,
            @Value("${iitsm.security.rate-limit.refresh-window-seconds:60}") long refreshWindowSeconds,
            @Value("${iitsm.security.rate-limit.mfa-capacity:10}") int mfaCapacity,
            @Value("${iitsm.security.rate-limit.mfa-window-seconds:60}") long mfaWindowSeconds) {
        this.enabled = enabled;
        this.trustedProxyHops = requireAtLeast(trustedProxyHops, 0, "trusted-proxy-hops");
        this.maxTrackedClients = requireAtLeast(maxTrackedClients, 1, "max-tracked-clients");
        this.login = new Limit("login", loginCapacity, loginWindowSeconds);
        this.register = new Limit("register", registerCapacity, registerWindowSeconds);
        this.refresh = new Limit("refresh", refreshCapacity, refreshWindowSeconds);
        this.mfa = new Limit("mfa", mfaCapacity, mfaWindowSeconds);
    }

    @Bean
    public ClientIpResolver clientIpResolver() {
        return new ClientIpResolver(trustedProxyHops);
    }

    @Bean
    public RateLimitRules rateLimitRules() {
        return new RateLimitRules(enabled, Map.of(
                LOGIN_PATH, route(login),
                REGISTER_PATH, route(register),
                REFRESH_PATH, route(refresh),
                MFA_VERIFY_PATH, route(mfa)));
    }

    private RateLimitRules.Route route(Limit limit) {
        return new RateLimitRules.Route(limit.name(),
                new InMemoryRateLimiter(limit.capacity(), Duration.ofSeconds(limit.windowSeconds()), maxTrackedClients));
    }

    private static int requireAtLeast(long value, long minimum, String property) {
        if (value < minimum || value > Integer.MAX_VALUE) {
            throw new IllegalStateException(PREFIX + property + " must be at least " + minimum + " (was " + value + ")");
        }
        return (int) value;
    }

    /** The capacity and refill window of one route, checked when it is read. */
    private record Limit(String name, int capacity, long windowSeconds) {

        Limit {
            requireAtLeast(capacity, 1, name + "-capacity");
            requireAtLeast(windowSeconds, 1, name + "-window-seconds");
        }
    }
}
