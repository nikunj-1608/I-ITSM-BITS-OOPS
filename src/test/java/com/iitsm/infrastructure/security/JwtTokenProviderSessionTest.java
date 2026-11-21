package com.iitsm.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.iitsm.domain.model.Designation;
import com.iitsm.domain.model.Email;
import com.iitsm.domain.model.Employee;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Authentication hardening, Phase S3 (ADR-047): the session id claim, the lifetime and the rule that token times come
 * from the injected wall clock (R1), never from the real time or the shared simulation clock.
 */
class JwtTokenProviderSessionTest {

    private static final String SECRET = "unit-test-secret-key-that-is-at-least-32-bytes-long!";
    private static final Instant T0 = Instant.parse("2026-11-20T10:00:00Z");
    private static final long TTL_MS = Duration.ofMinutes(15).toMillis();

    private static final class MovableClock extends Clock {
        private Instant now;

        MovableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MovableClock clock = new MovableClock(T0);
    private final JwtTokenProvider provider =
            new JwtTokenProvider(SECRET, TTL_MS, new MockEnvironment(), clock, 5);
    private final Employee user = new Employee("EMP-1", "Jane Doe", new Email("jane@nimbusfinserve.com"), "hash",
            Designation.ASSOCIATE, "Engineering", 1);

    @Test
    @DisplayName("a token issued for a session carries its id; the other claims are unchanged")
    void sessionIdClaim() {
        String token = provider.generateToken(user, "family-42");

        assertThat(provider.getSessionId(token)).isEqualTo("family-42");
        assertThat(provider.getEmailFromToken(token)).isEqualTo("jane@nimbusfinserve.com");
        assertThat(provider.getTokenVersionFromToken(token)).isEqualTo(user.getTokenVersion());
        assertThat(provider.validateToken(token)).isTrue();
    }

    @Test
    @DisplayName("a legacy token without a session id (generateToken(User)) has none and is still valid")
    void legacyTokenHasNoSession() {
        String token = provider.generateToken(user);

        assertThat(provider.getSessionId(token)).isNull();
        assertThat(provider.validateToken(token)).isTrue();
        assertThat(provider.generateToken(user, null)).isNotBlank();
        assertThat(provider.getSessionId(provider.generateToken(user, null))).isNull();
    }

    @Test
    @DisplayName("the lifetime is the configured one, valid just before it and expired at it, on the injected clock")
    void expiryFollowsTheInjectedClock() {
        String token = provider.generateToken(user, "family-1");

        clock.now = T0.plusMillis(TTL_MS - 1_000);
        assertThat(provider.validateToken(token)).isTrue();
        clock.now = T0.plusMillis(TTL_MS + 1_000);
        assertThat(provider.validateToken(token)).isFalse();
        assertThat(provider.getAccessTtlSeconds()).isEqualTo(900);
    }

    @Test
    @DisplayName("a clock that stands far in the past makes a token issued by another clock look expired or not yet valid")
    void usesTheInjectedClockNotTheRealTime() {
        String token = provider.generateToken(user, "family-1");

        // the token was issued in 2026-11-20; with the clock a year later it is long expired, whatever the real date is
        clock.now = T0.plus(Duration.ofDays(365));

        assertThat(provider.validateToken(token)).isFalse();
        assertThatThrownBy(() -> provider.getSessionId(token)).isInstanceOf(io.jsonwebtoken.JwtException.class);
    }

    @Test
    @DisplayName("a tampered token is invalid")
    void tamperedToken() {
        String token = provider.generateToken(user, "family-1");
        String tampered = token.substring(0, token.length() - 3) + (token.endsWith("AAA") ? "BBB" : "AAA");

        assertThat(provider.validateToken(tampered)).isFalse();
    }

    @Test
    @DisplayName("a non-positive lifetime stops the start-up")
    void badLifetime() {
        assertThatThrownBy(() -> new JwtTokenProvider(SECRET, 0, new MockEnvironment(), clock, 5))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("expiration-ms");
    }
}
