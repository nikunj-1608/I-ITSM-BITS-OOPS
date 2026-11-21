package com.iitsm.infrastructure.security;

import com.iitsm.domain.policy.RateLimitDecision;
import io.micrometer.core.instrument.Counter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

// OOP (Inheritance / Realization): RateLimitFilter extends or implements a framework or port type, so it plugs into the framework without changing callers.
/**
 * Pattern: Intercepting Filter. Limits {@code POST} requests to the sign-in endpoints per client address and route
 * (ADR-046). A refused request is answered here with HTTP 429 {@value #ERROR_CODE} and a {@code Retry-After} header,
 * so it never reaches the controller: it can neither guess a password nor count as a failed login (so it cannot lock
 * an account either).
 *
 * <p>Deliberately not a Spring bean: {@code SecurityConfig} builds it with {@code new} and adds it to the security
 * filter chain only. As a bean, Spring Boot would also register it in the servlet container's own chain.
 *
 * <p>The time comes from the real wall clock, never from the simulation clock, which can be moved by years.
 */
public final class RateLimitFilter extends OncePerRequestFilter {

    public static final String ERROR_CODE = "ERR_RATE_LIMITED";
    public static final String METRIC = "iitsm.security.rate_limited";
    static final String MESSAGE = "Too many attempts from this network. Try again later.";

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final String LIMITED_METHOD = "POST";
    private static final HttpStatus TOO_MANY = HttpStatus.TOO_MANY_REQUESTS;

    private final RateLimitRules rules;
    private final ClientIpResolver clientIpResolver;
    private final Clock wallClock;
    private final Counter rateLimited;

    public RateLimitFilter(RateLimitRules rules, ClientIpResolver clientIpResolver, Clock wallClock, Counter rateLimited) {
        this.rules = Objects.requireNonNull(rules, "rules cannot be null");
        this.clientIpResolver = Objects.requireNonNull(clientIpResolver, "clientIpResolver cannot be null");
        this.wallClock = Objects.requireNonNull(wallClock, "wallClock cannot be null");
        this.rateLimited = Objects.requireNonNull(rateLimited, "rateLimited cannot be null");
    }

    // OOP (Template Method): the framework calls this hook once per request.
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !rules.enabled()
                || !LIMITED_METHOD.equalsIgnoreCase(request.getMethod())
                || rules.routeFor(pathOf(request)).isEmpty();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        RateLimitRules.Route route = rules.routeFor(pathOf(request)).orElseThrow();
        String key = route.name() + ":" + clientIpResolver.resolve(request);
        RateLimitDecision decision = route.limiter().tryAcquire(key, wallClock.instant());
        if (decision.allowed()) {
            chain.doFilter(request, response);
            return;
        }
        rateLimited.increment();
        log.debug("Rate limit reached on route '{}'", route.name());
        reject(response, decision);
    }

    private void reject(HttpServletResponse response, RateLimitDecision decision) throws IOException {
        response.setStatus(TOO_MANY.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(decision.retryAfterSeconds()));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(errorBody());
    }

    /** The standard error shape of ADR-027; every value is fixed text, so no escaping is needed. */
    private String errorBody() {
        return "{\"timestamp\":\"" + wallClock.instant() + "\","
                + "\"status\":" + TOO_MANY.value() + ","
                + "\"error\":\"" + TOO_MANY.getReasonPhrase() + "\","
                + "\"code\":\"" + ERROR_CODE + "\","
                + "\"message\":\"" + MESSAGE + "\","
                + "\"fieldErrors\":[]}";
    }

    private static String pathOf(HttpServletRequest request) {
        String uri = Objects.requireNonNullElse(request.getRequestURI(), "");
        String context = Objects.requireNonNullElse(request.getContextPath(), "");
        return uri.startsWith(context) ? uri.substring(context.length()) : uri;
    }
}
