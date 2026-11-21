package com.iitsm.domain.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitsm.domain.model.RefreshToken;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Authentication hardening, Phase S3 (ADR-047): every outcome of the rotation decision. */
class RefreshTokenRotationTest {

    private static final Instant ISSUED = Instant.parse("2026-11-20T10:00:00Z");
    private static final Instant EXPIRES = ISSUED.plusSeconds(8 * 3600);
    private static final Instant NOW = ISSUED.plusSeconds(60);

    private static RefreshToken token(Instant revokedAt, String reason) {
        return new RefreshToken(1L, "U-1", "f", "h", 1, ISSUED, EXPIRES, revokedAt, reason, null);
    }

    @Test
    @DisplayName("a live, unrevoked token is rotated")
    void rotate() {
        RefreshTokenRotation decision = RefreshTokenRotation.decide(token(null, null), NOW);

        assertThat(decision).isEqualTo(RefreshTokenRotation.ROTATE);
        assertThat(decision.permitsRotation()).isTrue();
    }

    @Test
    @DisplayName("a token that was already rotated is a replay")
    void reused() {
        RefreshTokenRotation decision = RefreshTokenRotation.decide(token(NOW, "ROTATED"), NOW);

        assertThat(decision).isEqualTo(RefreshTokenRotation.REUSED);
        assertThat(decision.permitsRotation()).isFalse();
    }

    @Test
    @DisplayName("a token revoked by a sign-out or another reason is revoked, not a replay")
    void revoked() {
        assertThat(RefreshTokenRotation.decide(token(NOW, "LOGOUT"), NOW)).isEqualTo(RefreshTokenRotation.REVOKED);
        assertThat(RefreshTokenRotation.decide(token(NOW, "LOGOUT_ALL"), NOW)).isEqualTo(RefreshTokenRotation.REVOKED);
        assertThat(RefreshTokenRotation.decide(token(NOW, null), NOW)).isEqualTo(RefreshTokenRotation.REVOKED);
    }

    @Test
    @DisplayName("an unrevoked token past its expiry is expired, at the instant itself")
    void expired() {
        assertThat(RefreshTokenRotation.decide(token(null, null), EXPIRES.minusNanos(1)))
                .isEqualTo(RefreshTokenRotation.ROTATE);
        assertThat(RefreshTokenRotation.decide(token(null, null), EXPIRES)).isEqualTo(RefreshTokenRotation.EXPIRED);
        assertThat(RefreshTokenRotation.decide(token(null, null), EXPIRES.plusSeconds(1)))
                .isEqualTo(RefreshTokenRotation.EXPIRED);
    }

    @Test
    @DisplayName("revocation is checked before expiry: an expired rotated token is still a replay")
    void revocationBeforeExpiry() {
        Instant late = EXPIRES.plusSeconds(1);

        assertThat(RefreshTokenRotation.decide(token(NOW, "ROTATED"), late)).isEqualTo(RefreshTokenRotation.REUSED);
        assertThat(RefreshTokenRotation.decide(token(NOW, "LOGOUT"), late)).isEqualTo(RefreshTokenRotation.REVOKED);
    }

    @Test
    @DisplayName("only ROTATE permits a rotation")
    void onlyRotatePermits() {
        for (RefreshTokenRotation outcome : RefreshTokenRotation.values()) {
            assertThat(outcome.permitsRotation()).isEqualTo(outcome == RefreshTokenRotation.ROTATE);
        }
    }
}
