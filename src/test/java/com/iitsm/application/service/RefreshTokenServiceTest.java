package com.iitsm.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.iitsm.application.port.OpaqueTokenPort;
import com.iitsm.application.port.TokenPort;
import com.iitsm.domain.exception.InvalidRefreshTokenException;
import com.iitsm.domain.model.Designation;
import com.iitsm.domain.model.Email;
import com.iitsm.domain.model.Employee;
import com.iitsm.domain.model.RefreshToken;
import com.iitsm.domain.model.User;
import com.iitsm.domain.repository.UserRepository;
import com.iitsm.testsupport.InMemoryRefreshTokenRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Authentication hardening, Phase S3 (ADR-047): the refresh-token rules, with a fixed wall clock, an in-memory
 * repository and deterministic tokens. Nothing here starts Spring.
 */
class RefreshTokenServiceTest {

    private static final Instant T0 = Instant.parse("2026-11-20T10:00:00Z");
    private static final int TTL_HOURS = 8;
    private static final int MAX_SESSIONS = 3;

    private final InMemoryRefreshTokenRepository tokens = new InMemoryRefreshTokenRepository();
    private final UserRepository users = mock(UserRepository.class);
    private final SecurityAuditLog audit = mock(SecurityAuditLog.class);
    private final MutableClock wallClock = new MutableClock(T0);
    private final MutableClock sharedClock = new MutableClock(T0);
    private RefreshTokenService service;
    private Employee user;

    /** Deterministic stand-ins: token n is "token-n", its hash is "hash(token-n)". */
    private static final class CountingTokens implements OpaqueTokenPort {
        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public String newToken() {
            return "token-" + counter.incrementAndGet();
        }

        @Override
        public String hash(String token) {
            return "hash(" + token + ")";
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
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

    @BeforeEach
    void setUp() {
        user = new Employee("EMP-1", "Jane Doe", new Email("jane@nimbusfinserve.com"), "hash",
                Designation.ASSOCIATE, "Engineering", 1);
        when(users.findById("EMP-1")).thenAnswer(call -> Optional.of(user));
        TokenPort accessTokens = mock(TokenPort.class);
        when(accessTokens.generateToken(org.mockito.ArgumentMatchers.any(User.class), org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(call -> "access-for-" + call.getArgument(1));
        when(accessTokens.getAccessTtlSeconds()).thenReturn(900L);
        service = new RefreshTokenService(tokens, users, new CountingTokens(), accessTokens, audit, wallClock,
                sharedClock, TTL_HOURS, MAX_SESSIONS);
    }

    private String familyOf(String refreshToken) {
        return tokens.findByTokenHash("hash(" + refreshToken + ")").orElseThrow().familyId();
    }

    @Test
    @DisplayName("sign-in issues an access token for the new session and a refresh token that is stored only as a hash")
    void issueSession() {
        AuthSession session = service.issueSession(user);

        assertThat(session.refreshToken()).isEqualTo("token-1");
        assertThat(session.accessToken()).isEqualTo("access-for-" + familyOf("token-1"));
        assertThat(session.expiresInSeconds()).isEqualTo(900);
        assertThat(session.user()).isSameAs(user);
        RefreshToken stored = tokens.all().get(0);
        assertThat(stored.tokenHash()).isEqualTo("hash(token-1)").doesNotContain("token-1" + "x");
        assertThat(stored.expiresAt()).isEqualTo(T0.plus(Duration.ofHours(TTL_HOURS)));
        assertThat(stored.tokenVersion()).isEqualTo(user.getTokenVersion());
    }

    @Test
    @DisplayName("refresh rotates: the old token is revoked as ROTATED and linked, the new one lives in the same family")
    void refreshRotates() {
        AuthSession first = service.issueSession(user);
        wallClock.advance(Duration.ofMinutes(10));

        AuthSession second = service.refresh(first.refreshToken());

        assertThat(second.refreshToken()).isNotEqualTo(first.refreshToken());
        assertThat(familyOf(second.refreshToken())).isEqualTo(familyOf(first.refreshToken()));
        RefreshToken old = tokens.findByTokenHash("hash(" + first.refreshToken() + ")").orElseThrow();
        RefreshToken next = tokens.findByTokenHash("hash(" + second.refreshToken() + ")").orElseThrow();
        assertThat(old.wasRotated()).isTrue();
        assertThat(old.replacedById()).isEqualTo(next.id());
        assertThat(next.isRevoked()).isFalse();
        assertThat(next.expiresAt()).as("the session's end is absolute, not renewed by use").isEqualTo(old.expiresAt());
        assertThat(next.issuedAt()).isEqualTo(T0.plus(Duration.ofMinutes(10)));
    }

    @Test
    @DisplayName("a rotated token used again revokes the whole session, newest token included")
    void reuseRevokesTheFamily() {
        AuthSession first = service.issueSession(user);
        AuthSession second = service.refresh(first.refreshToken());

        assertThatThrownBy(() -> service.refresh(first.refreshToken())).isInstanceOf(InvalidRefreshTokenException.class);

        assertThat(tokens.isFamilyActive(familyOf(second.refreshToken()), wallClock.instant())).isFalse();
        assertThatThrownBy(() -> service.refresh(second.refreshToken())).isInstanceOf(InvalidRefreshTokenException.class);
        org.mockito.Mockito.verify(audit).refreshReuseDetected("EMP-1", familyOf(second.refreshToken()));
    }

    @Test
    @DisplayName("an expired token is refused, at the instant of expiry and after")
    void expiredToken() {
        AuthSession session = service.issueSession(user);
        wallClock.advance(Duration.ofHours(TTL_HOURS));

        assertThatThrownBy(() -> service.refresh(session.refreshToken())).isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    @DisplayName("just before the end of the session a refresh still works")
    void justBeforeExpiry() {
        AuthSession session = service.issueSession(user);
        wallClock.advance(Duration.ofHours(TTL_HOURS).minusSeconds(1));

        assertThat(service.refresh(session.refreshToken()).refreshToken()).isNotBlank();
    }

    @Test
    @DisplayName("a deactivated user cannot refresh, and the session is revoked")
    void inactiveUser() {
        AuthSession session = service.issueSession(user);
        user.deactivate();

        assertThatThrownBy(() -> service.refresh(session.refreshToken())).isInstanceOf(InvalidRefreshTokenException.class);
        assertThat(tokens.isFamilyActive(familyOf(session.refreshToken()), wallClock.instant())).isFalse();
    }

    @Test
    @DisplayName("a locked user cannot refresh; the lock is judged with the shared clock, not the wall clock")
    void lockedUser() {
        AuthSession session = service.issueSession(user);
        for (int i = 0; i < 5; i++) {
            user.recordFailedLogin(5, T0, Duration.ofMinutes(15));
        }

        assertThatThrownBy(() -> service.refresh(session.refreshToken())).isInstanceOf(InvalidRefreshTokenException.class);
        assertThat(tokens.isFamilyActive(familyOf(session.refreshToken()), wallClock.instant())).isFalse();
    }

    @Test
    @DisplayName("a changed token version (sign out everywhere) ends the session")
    void tokenVersionMismatch() {
        AuthSession session = service.issueSession(user);
        user.bumpTokenVersion();

        assertThatThrownBy(() -> service.refresh(session.refreshToken())).isInstanceOf(InvalidRefreshTokenException.class);
        assertThat(tokens.isFamilyActive(familyOf(session.refreshToken()), wallClock.instant())).isFalse();
    }

    @Test
    @DisplayName("signing in more often than the cap ends the oldest session, and only that one")
    void sessionCap() {
        AuthSession oldest = service.issueSession(user);
        wallClock.advance(Duration.ofMinutes(1));
        AuthSession second = service.issueSession(user);
        wallClock.advance(Duration.ofMinutes(1));
        AuthSession third = service.issueSession(user);
        assertThat(tokens.activeFamiliesOf("EMP-1", wallClock.instant())).hasSize(MAX_SESSIONS);

        wallClock.advance(Duration.ofMinutes(1));
        AuthSession fourth = service.issueSession(user);

        assertThat(tokens.isFamilyActive(familyOf(oldest.refreshToken()), wallClock.instant())).isFalse();
        for (AuthSession alive : new AuthSession[] {second, third, fourth}) {
            assertThat(tokens.isFamilyActive(familyOf(alive.refreshToken()), wallClock.instant())).isTrue();
        }
    }

    @Test
    @DisplayName("logout ends the session; doing it again, with an unknown token, blank or null is silent")
    void logoutIsIdempotent() {
        AuthSession session = service.issueSession(user);

        service.logout(session.refreshToken());

        assertThat(tokens.isFamilyActive(familyOf(session.refreshToken()), wallClock.instant())).isFalse();
        assertThatCode(() -> {
            service.logout(session.refreshToken());
            service.logout("never-issued");
            service.logout("");
            service.logout(null);
        }).doesNotThrowAnyException();
        assertThatThrownBy(() -> service.refresh(session.refreshToken())).isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    @DisplayName("logout of one session leaves the user's other sessions alone")
    void logoutIsPerSession() {
        AuthSession a = service.issueSession(user);
        AuthSession b = service.issueSession(user);

        service.logout(a.refreshToken());

        assertThat(tokens.isFamilyActive(familyOf(b.refreshToken()), wallClock.instant())).isTrue();
    }

    @Test
    @DisplayName("logout-all ends every session of the user and raises the token version")
    void logoutAll() {
        AuthSession a = service.issueSession(user);
        AuthSession b = service.issueSession(user);
        int before = user.getTokenVersion();

        service.logoutAll("EMP-1");

        assertThat(tokens.activeFamiliesOf("EMP-1", wallClock.instant())).isEmpty();
        assertThat(user.getTokenVersion()).isEqualTo(before + 1);
        org.mockito.Mockito.verify(users).save(user);
        assertThatThrownBy(() -> service.refresh(a.refreshToken())).isInstanceOf(InvalidRefreshTokenException.class);
        assertThatThrownBy(() -> service.refresh(b.refreshToken())).isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    @DisplayName("an unknown, blank or null token is the same exception with the same message")
    void unknownTokenGivesTheGenericAnswer() {
        AuthSession session = service.issueSession(user);
        service.logout(session.refreshToken());
        String[] attempts = {"never-issued", "", "   ", null, session.refreshToken()};

        for (String attempt : attempts) {
            assertThatThrownBy(() -> service.refresh(attempt))
                    .isInstanceOf(InvalidRefreshTokenException.class)
                    .hasMessage(InvalidRefreshTokenException.MESSAGE)
                    .extracting(e -> ((InvalidRefreshTokenException) e).getErrorCode())
                    .isEqualTo("ERR_INVALID_REFRESH_TOKEN");
        }
    }

    @Test
    @DisplayName("settings below 1 stop the start-up")
    void badSettings() {
        TokenPort accessTokens = mock(TokenPort.class);

        assertThatThrownBy(() -> new RefreshTokenService(tokens, users, new CountingTokens(), accessTokens, audit,
                wallClock, sharedClock, 0, 10)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new RefreshTokenService(tokens, users, new CountingTokens(), accessTokens, audit,
                wallClock, sharedClock, 8, 0)).isInstanceOf(IllegalStateException.class);
    }
}
