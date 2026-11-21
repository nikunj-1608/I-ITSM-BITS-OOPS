package com.iitsm.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * OOP: Value object (immutable). One refresh token of a session (ADR-047). Only the SHA-256 hash of the token is kept,
 * never the token. All the tokens of one sign-in form a <em>family</em> ({@code familyId}, also the {@code sid} claim
 * of its access tokens): each refresh revokes the presented token as {@value #ROTATED} and issues the next one of the
 * same family.
 *
 * @param id           null until the token is stored
 * @param tokenVersion the user's token version when this token was issued; a later change revokes the session
 * @param replacedById the token that replaced this one, when it was rotated
 */
public record RefreshToken(
        Long id,
        String userId,
        String familyId,
        String tokenHash,
        int tokenVersion,
        Instant issuedAt,
        Instant expiresAt,
        Instant revokedAt,
        String revokedReason,
        Long replacedById) {

    public static final String ROTATED = "ROTATED";

    public RefreshToken {
        Objects.requireNonNull(userId, "userId cannot be null");
        Objects.requireNonNull(familyId, "familyId cannot be null");
        Objects.requireNonNull(tokenHash, "tokenHash cannot be null");
        Objects.requireNonNull(issuedAt, "issuedAt cannot be null");
        Objects.requireNonNull(expiresAt, "expiresAt cannot be null");
        if (!expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("A refresh token must expire after it was issued");
        }
    }

    /** A new, unrevoked token that is not stored yet. */
    public static RefreshToken issue(
            String userId, String familyId, String tokenHash, int tokenVersion, Instant issuedAt, Instant expiresAt) {
        return new RefreshToken(null, userId, familyId, tokenHash, tokenVersion, issuedAt, expiresAt, null, null, null);
    }

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    /** True when this token was revoked because the session moved on to the next token (as opposed to a sign-out). */
    public boolean wasRotated() {
        return isRevoked() && ROTATED.equals(revokedReason);
    }
}
