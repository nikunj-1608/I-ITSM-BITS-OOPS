package com.iitsm.infrastructure.security;

import com.iitsm.domain.policy.RateLimiter;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Which requests are rate limited and by which limiter (ADR-046): one {@link Route} per limited path. Only
 * {@code POST} requests to these exact paths are limited; {@code enabled = false} switches every limit off.
 */
public record RateLimitRules(boolean enabled, Map<String, Route> routesByPath) {

    public RateLimitRules {
        routesByPath = Map.copyOf(Objects.requireNonNull(routesByPath, "routesByPath cannot be null"));
    }

    public Optional<Route> routeFor(String path) {
        return Optional.ofNullable(routesByPath.get(path));
    }

    /** A limited route: {@code name} prefixes the limiter key, so each route counts on its own. */
    public record Route(String name, RateLimiter limiter) {

        public Route {
            Objects.requireNonNull(name, "name cannot be null");
            Objects.requireNonNull(limiter, "limiter cannot be null");
        }
    }
}
