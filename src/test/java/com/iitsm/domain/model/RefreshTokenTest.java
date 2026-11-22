package com.iitsm.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Authentication hardening, Phase S3 (ADR-047): the refresh token value object. */
class RefreshTokenTest {

    private static final Instant ISSUED = Instant.parse("2026-11-20T10:00:00Z");
    private static final Instant EXPIRES = ISSUED.plusSeconds(8 * 3600);

    private static RefreshToken fresh() {
        return RefreshToken.issue("U-1", "family-1", "a".repeat(64), 3, ISSUED, EXPIRES);
    }

    @Test
    @DisplayName("a new token is stored later (no id), not revoked and not expired")
    void freshToken() {
        RefreshToken token = fresh();

        assertThat(token.id()).isNull();
        assertThat(token.isRevoked()).isFalse();
        assertThat(token.wasRotated()).isFalse();
        assertThat(token.isExpired(ISSUED)).isFalse();
        assertThat(token.tokenVersion()).isEqualTo(3);
    }

    @Test
    @DisplayName("it expires exactly at expiresAt: valid one instant before, expired at that instant")
    void expiryBoundary() {
        RefreshToken token = fresh();

        assertThat(token.isExpired(EXPIRES.minusNanos(1))).isFalse();
        assertThat(token.isExpired(EXPIRES)).isTrue();
        assertThat(token.isExpired(EXPIRES.plusSeconds(1))).isTrue();
    }

    @Test
    @DisplayName("revoked as ROTATED it was rotated; revoked for another reason it was not")
    void rotatedVersusRevoked() {
        RefreshToken rotated = new RefreshToken(1L, "U-1", "f", "h", 1, ISSUED, EXPIRES, ISSUED, "ROTATED", 2L);
        RefreshToken signedOut = new RefreshToken(1L, "U-1", "f", "h", 1, ISSUED, EXPIRES, ISSUED, "LOGOUT", null);

        assertThat(rotated.isRevoked()).isTrue();
        assertThat(rotated.wasRotated()).isTrue();
        assertThat(signedOut.isRevoked()).isTrue();
        assertThat(signedOut.wasRotated()).isFalse();
    }

    @Test
    @DisplayName("a token must expire after it was issued and have an owner, a family and a hash")
    void invariants() {
        assertThatThrownBy(() -> RefreshToken.issue("U-1", "f", "h", 1, ISSUED, ISSUED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RefreshToken.issue(null, "f", "h", 1, ISSUED, EXPIRES))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> RefreshToken.issue("U-1", null, "h", 1, ISSUED, EXPIRES))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> RefreshToken.issue("U-1", "f", null, 1, ISSUED, EXPIRES))
                .isInstanceOf(NullPointerException.class);
    }
}
