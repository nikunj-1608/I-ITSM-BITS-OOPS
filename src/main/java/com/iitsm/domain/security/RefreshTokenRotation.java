package com.iitsm.domain.security;

import com.iitsm.domain.model.RefreshToken;
import java.time.Instant;
import java.util.Objects;

/**
 * Pattern: Policy (pure decision). What to do with a refresh token that a client presents (ADR-047). Pure: no clock, no
 * repository, so every case is a unit test.
 *
 * <ul>
 *   <li>{@link #REUSED}: the token was already rotated. Its owner holds the newer token, so this is a replay (or a lost
 *       response); the whole session family is revoked.
 *   <li>{@link #REVOKED}: the token was revoked by a sign-out or another reason; nothing to rotate.
 *   <li>{@link #EXPIRED}: past its expiry.
 *   <li>{@link #ROTATE}: valid; issue the next token of the family.
 * </ul>
 */
public enum RefreshTokenRotation {
    ROTATE,
    REUSED,
    EXPIRED,
    REVOKED;

    /** Revocation is checked before expiry: a rotated token that has also expired is still a replay. */
    public static RefreshTokenRotation decide(RefreshToken token, Instant now) {
        Objects.requireNonNull(token, "token cannot be null");
        Objects.requireNonNull(now, "now cannot be null");
        if (token.wasRotated()) {
            return REUSED;
        }
        if (token.isRevoked()) {
            return REVOKED;
        }
        if (token.isExpired(now)) {
            return EXPIRED;
        }
        return ROTATE;
    }

    public boolean permitsRotation() {
        return this == ROTATE;
    }
}
