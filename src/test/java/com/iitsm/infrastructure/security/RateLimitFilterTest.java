package com.iitsm.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iitsm.infrastructure.config.RateLimitConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Authentication hardening, Phase S2 (ADR-046): what the filter limits and how it answers. */
class RateLimitFilterTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-11-20T10:00:00Z"), ZoneOffset.UTC);

    private SimpleMeterRegistry registry;
    private Counter counter;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        counter = Counter.builder(RateLimitFilter.METRIC).register(registry);
    }

    private RateLimitFilter filter(boolean enabled) {
        Map<String, RateLimitRules.Route> routes = Map.of(
                RateLimitConfig.LOGIN_PATH, route("login"),
                RateLimitConfig.REGISTER_PATH, route("register"),
                RateLimitConfig.REFRESH_PATH, route("refresh"),
                RateLimitConfig.MFA_VERIFY_PATH, route("mfa"));
        return new RateLimitFilter(new RateLimitRules(enabled, routes), new ClientIpResolver(0), FIXED, counter);
    }

    private static RateLimitRules.Route route(String name) {
        return new RateLimitRules.Route(name, new InMemoryRateLimiter(1, Duration.ofSeconds(60), 100));
    }

    private static MockHttpServletResponse send(RateLimitFilter filter, String method, String path, String ip)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr(ip);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        if (chain.getRequest() != null) {
            response.setStatus(200);
        }
        return response;
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/auth/login", "/api/auth/register", "/api/auth/refresh", "/api/auth/mfa/verify"})
    @DisplayName("each listed POST route is limited on its own")
    void listedRoutesAreLimited(String path) throws Exception {
        RateLimitFilter filter = filter(true);

        assertThat(send(filter, "POST", path, "10.0.0.1").getStatus()).isEqualTo(200);
        assertThat(send(filter, "POST", path, "10.0.0.1").getStatus()).isEqualTo(429);
        assertThat(send(filter, "POST", "/api/auth/login".equals(path) ? "/api/auth/register" : "/api/auth/login",
                "10.0.0.1").getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("the 429 has the standard error body, a whole-second Retry-After and counts in the metric")
    void refusalShape() throws Exception {
        RateLimitFilter filter = filter(true);
        send(filter, "POST", "/api/auth/login", "10.0.0.2");

        MockHttpServletResponse refused = send(filter, "POST", "/api/auth/login", "10.0.0.2");
        JsonNode body = new ObjectMapper().readTree(refused.getContentAsString());

        assertThat(refused.getHeader("Retry-After")).isEqualTo("60");
        assertThat(refused.getContentType()).startsWith("application/json");
        assertThat(body.get("status").asInt()).isEqualTo(429);
        assertThat(body.get("error").asText()).isEqualTo("Too Many Requests");
        assertThat(body.get("code").asText()).isEqualTo("ERR_RATE_LIMITED");
        assertThat(body.get("message").asText()).isEqualTo(RateLimitFilter.MESSAGE);
        assertThat(body.get("fieldErrors").isArray()).isTrue();
        assertThat(Instant.parse(body.get("timestamp").asText())).isEqualTo(FIXED.instant());
        assertThat(registry.get(RateLimitFilter.METRIC).counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("another address is not affected")
    void otherAddressPasses() throws Exception {
        RateLimitFilter filter = filter(true);
        send(filter, "POST", "/api/auth/login", "10.0.0.3");
        send(filter, "POST", "/api/auth/login", "10.0.0.3");

        assertThat(send(filter, "POST", "/api/auth/login", "10.0.0.4").getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("GET and other paths are never limited")
    void getAndOtherPathsPass() throws Exception {
        RateLimitFilter filter = filter(true);
        for (int i = 0; i < 5; i++) {
            assertThat(send(filter, "GET", "/api/auth/login", "10.0.0.5").getStatus()).isEqualTo(200);
            assertThat(send(filter, "GET", "/api/auth/me", "10.0.0.5").getStatus()).isEqualTo(200);
            assertThat(send(filter, "POST", "/api/employee/incidents", "10.0.0.5").getStatus()).isEqualTo(200);
            assertThat(send(filter, "POST", "/api/auth/logout", "10.0.0.5").getStatus()).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("disabled: every request passes")
    void disabledPassesThrough() throws Exception {
        RateLimitFilter filter = filter(false);
        for (int i = 0; i < 5; i++) {
            assertThat(send(filter, "POST", "/api/auth/login", "10.0.0.6").getStatus()).isEqualTo(200);
        }
        assertThat(registry.get(RateLimitFilter.METRIC).counter().count()).isZero();
    }
}
