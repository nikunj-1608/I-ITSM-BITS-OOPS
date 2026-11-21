package com.iitsm.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.iitsm.domain.model.AdminLevel;
import com.iitsm.domain.model.Administrator;
import com.iitsm.domain.model.Email;
import com.iitsm.domain.repository.RefreshTokenRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;

/**
 * Authentication hardening, Phase S4 (ADR-048): the challenge token that proves the password step. It is not an access
 * token: the filter never accepts it as a credential, and an access token is never accepted as a challenge. Spring-free.
 */
class MfaChallengeTokenTest {

    private static final String SECRET = "unit-test-secret-key-that-is-at-least-32-bytes-long!";
    private static final Instant T0 = Instant.parse("2026-11-20T10:00:00Z");
    private static final long CHALLENGE_MINUTES = 5;

    private static final class MovableClock extends Clock {
        private Instant now = T0;

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

    private final MovableClock clock = new MovableClock();
    private final JwtTokenProvider provider =
            new JwtTokenProvider(SECRET, 900_000, new MockEnvironment(), clock, CHALLENGE_MINUTES);
    private final Administrator admin = new Administrator("ADM-1", "Ada Admin", new Email("ada@nimbusfinserve.com"),
            "hash", AdminLevel.SYSTEM_ADMIN);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("a challenge names the user and is recognised as a challenge")
    void roundTrip() {
        String challenge = provider.generateMfaChallenge(admin);

        assertThat(provider.parseMfaChallenge(challenge)).contains("ADM-1");
        assertThat(provider.isMfaChallenge(challenge)).isTrue();
        assertThat(provider.getSessionId(challenge)).isNull();
    }

    @Test
    @DisplayName("an access token is not a challenge, and is not recognised as one")
    void accessTokenIsNotAChallenge() {
        String access = provider.generateToken(admin, "family-1");

        assertThat(provider.parseMfaChallenge(access)).isEmpty();
        assertThat(provider.isMfaChallenge(access)).isFalse();
    }

    @Test
    @DisplayName("a challenge lives 5 minutes on the injected clock")
    void expiry() {
        String challenge = provider.generateMfaChallenge(admin);

        clock.now = T0.plus(Duration.ofMinutes(CHALLENGE_MINUTES)).minusSeconds(1);
        assertThat(provider.parseMfaChallenge(challenge)).isPresent();
        clock.now = T0.plus(Duration.ofMinutes(CHALLENGE_MINUTES)).plusSeconds(1);
        assertThat(provider.parseMfaChallenge(challenge)).isEmpty();
    }

    @Test
    @DisplayName("garbage, a tampered token and a token signed with another key are not challenges")
    void invalidTokens() {
        String challenge = provider.generateMfaChallenge(admin);
        String tampered = challenge.substring(0, challenge.length() - 3) + (challenge.endsWith("AAA") ? "BBB" : "AAA");
        JwtTokenProvider other = new JwtTokenProvider("another-secret-key-that-is-also-32-bytes-long!!", 900_000,
                new MockEnvironment(), clock, CHALLENGE_MINUTES);

        assertThat(provider.parseMfaChallenge("not-a-token")).isEmpty();
        assertThat(provider.parseMfaChallenge("")).isEmpty();
        assertThat(provider.parseMfaChallenge(tampered)).isEmpty();
        assertThat(provider.parseMfaChallenge(other.generateMfaChallenge(admin))).isEmpty();
    }

    @Test
    @DisplayName("the challenge lifetime must be positive")
    void positiveLifetime() {
        assertThatThrownBy(() -> new JwtTokenProvider(SECRET, 900_000, new MockEnvironment(), clock, 0))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("challenge-minutes");
    }

    @Test
    @DisplayName("the filter never accepts a challenge as a Bearer credential: 401 and nobody is signed in")
    void filterRefusesAChallenge() throws Exception {
        UserDetailsService details = email -> new IitsmUserDetails(admin,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")), T0);
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(provider, details,
                mock(RefreshTokenRepository.class), clock);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/stats");
        request.addHeader("Authorization", "Bearer " + provider.generateMfaChallenge(admin));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
