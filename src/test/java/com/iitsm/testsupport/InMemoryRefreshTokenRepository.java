package com.iitsm.testsupport;

import com.iitsm.domain.model.RefreshToken;
import com.iitsm.domain.repository.RefreshTokenRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Test double of the {@link RefreshTokenRepository} port with the same rules as the database adapter: ids are assigned
 * on save, a revocation touches only tokens that are not revoked yet, and a token's first revocation reason is kept.
 */
public class InMemoryRefreshTokenRepository implements RefreshTokenRepository {

    private final Map<Long, RefreshToken> rows = new LinkedHashMap<>();
    private final AtomicLong ids = new AtomicLong();

    @Override
    public synchronized RefreshToken save(RefreshToken token) {
        long id = ids.incrementAndGet();
        RefreshToken stored = new RefreshToken(id, token.userId(), token.familyId(), token.tokenHash(),
                token.tokenVersion(), token.issuedAt(), token.expiresAt(), token.revokedAt(), token.revokedReason(),
                token.replacedById());
        rows.put(id, stored);
        return stored;
    }

    @Override
    public synchronized Optional<RefreshToken> findByTokenHash(String tokenHash) {
        return rows.values().stream().filter(t -> t.tokenHash().equals(tokenHash)).findFirst();
    }

    @Override
    public synchronized boolean isFamilyActive(String familyId, Instant now) {
        return rows.values().stream().anyMatch(t -> t.familyId().equals(familyId) && !t.isRevoked() && !t.isExpired(now));
    }

    @Override
    public synchronized boolean claimForRotation(long tokenId, Instant now) {
        RefreshToken token = rows.get(tokenId);
        if (token == null || token.isRevoked()) {
            return false;
        }
        rows.put(tokenId, withRevocation(token, now, RefreshToken.ROTATED));
        return true;
    }

    @Override
    public synchronized void markReplacedBy(long tokenId, long replacementId) {
        RefreshToken t = rows.get(tokenId);
        rows.put(tokenId, new RefreshToken(t.id(), t.userId(), t.familyId(), t.tokenHash(), t.tokenVersion(),
                t.issuedAt(), t.expiresAt(), t.revokedAt(), t.revokedReason(), replacementId));
    }

    @Override
    public synchronized void revokeFamily(String familyId, String reason, Instant now) {
        rows.replaceAll((id, t) -> t.familyId().equals(familyId) && !t.isRevoked() ? withRevocation(t, now, reason) : t);
    }

    @Override
    public synchronized void revokeAllForUser(String userId, String reason, Instant now) {
        rows.replaceAll((id, t) -> t.userId().equals(userId) && !t.isRevoked() ? withRevocation(t, now, reason) : t);
    }

    @Override
    public synchronized List<String> activeFamiliesOf(String userId, Instant now) {
        return rows.values().stream()
                .filter(t -> t.userId().equals(userId) && !t.isRevoked() && !t.isExpired(now))
                .sorted(Comparator.comparing(RefreshToken::issuedAt).thenComparing(RefreshToken::id))
                .map(RefreshToken::familyId).distinct().toList();
    }

    @Override
    public synchronized int deleteExpiredBefore(Instant cutoff) {
        int before = rows.size();
        rows.values().removeIf(t -> t.expiresAt().isBefore(cutoff));
        return before - rows.size();
    }

    public synchronized List<RefreshToken> all() {
        return new ArrayList<>(rows.values());
    }

    private static RefreshToken withRevocation(RefreshToken t, Instant now, String reason) {
        return new RefreshToken(t.id(), t.userId(), t.familyId(), t.tokenHash(), t.tokenVersion(), t.issuedAt(),
                t.expiresAt(), now, reason, t.replacedById());
    }
}
