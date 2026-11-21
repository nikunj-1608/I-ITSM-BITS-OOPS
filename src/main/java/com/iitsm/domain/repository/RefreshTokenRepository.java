package com.iitsm.domain.repository;

import com.iitsm.domain.model.RefreshToken;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Port for refresh tokens (ADR-047). Pattern: Repository. Revocations only touch tokens that are not yet revoked, so a
 * token keeps the reason it was first revoked with (a rotated token stays {@code ROTATED}, which is how a replay is
 * recognised).
 */
public interface RefreshTokenRepository {

    /** Stores a new token and returns it with its id. */
    RefreshToken save(RefreshToken token);

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** True while the family has a token that is neither revoked nor expired: the session is alive. */
    boolean isFamilyActive(String familyId, Instant now);

    /**
     * Revokes {@code tokenId} as rotated only if nobody revoked it before. Returns false when someone else got there
     * first (two refreshes of the same token at once): the caller treats that as a replay.
     */
    boolean claimForRotation(long tokenId, Instant now);

    void markReplacedBy(long tokenId, long replacementId);

    void revokeFamily(String familyId, String reason, Instant now);

    void revokeAllForUser(String userId, String reason, Instant now);

    /** Family ids of the user's live sessions, least recently refreshed first. */
    List<String> activeFamiliesOf(String userId, Instant now);

    /** Deletes tokens that expired before {@code cutoff}; returns how many. */
    int deleteExpiredBefore(Instant cutoff);
}
