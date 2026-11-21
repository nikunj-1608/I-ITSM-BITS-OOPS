package com.iitsm.infrastructure.persistence.adapter;

import com.iitsm.domain.exception.PersistenceFailureException;
import com.iitsm.domain.model.RefreshToken;
import com.iitsm.domain.repository.RefreshTokenRepository;
import com.iitsm.infrastructure.persistence.entity.RefreshTokenJpaEntity;
import com.iitsm.infrastructure.persistence.repository.SpringDataRefreshTokenRepository;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pattern: Adapter / Repository. Stores refresh tokens (ADR-047). The write methods carry their own transaction so
 * the conditional update statements work when called from a filter or a scheduler; inside a service transaction they
 * simply join it.
 */
@Component
public class RefreshTokenRepositoryAdapter implements RefreshTokenRepository {

    private final SpringDataRefreshTokenRepository springData;

    public RefreshTokenRepositoryAdapter(SpringDataRefreshTokenRepository springData) {
        this.springData = Objects.requireNonNull(springData, "springData cannot be null");
    }

    @Override
    @Transactional
    public RefreshToken save(RefreshToken token) {
        Objects.requireNonNull(token, "token cannot be null");
        return guarded("store a refresh token", () -> toDomain(springData.saveAndFlush(toEntity(token))));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RefreshToken> findByTokenHash(String tokenHash) {
        Objects.requireNonNull(tokenHash, "tokenHash cannot be null");
        return guarded("read a refresh token", () -> springData.findByTokenHash(tokenHash).map(this::toDomain));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isFamilyActive(String familyId, Instant now) {
        Objects.requireNonNull(familyId, "familyId cannot be null");
        Objects.requireNonNull(now, "now cannot be null");
        return guarded("check a session", () ->
                springData.existsByFamilyIdAndRevokedAtIsNullAndExpiresAtAfter(familyId, now));
    }

    @Override
    @Transactional
    public boolean claimForRotation(long tokenId, Instant now) {
        return guarded("rotate a refresh token", () ->
                springData.revokeIfActive(tokenId, RefreshToken.ROTATED, now) == 1);
    }

    @Override
    @Transactional
    public void markReplacedBy(long tokenId, long replacementId) {
        guarded("link a refresh token", () -> springData.setReplacedBy(tokenId, replacementId));
    }

    @Override
    @Transactional
    public void revokeFamily(String familyId, String reason, Instant now) {
        Objects.requireNonNull(familyId, "familyId cannot be null");
        guarded("revoke a session", () -> springData.revokeFamily(familyId, reason, now));
    }

    @Override
    @Transactional
    public void revokeAllForUser(String userId, String reason, Instant now) {
        Objects.requireNonNull(userId, "userId cannot be null");
        guarded("revoke the sessions of a user", () -> springData.revokeAllForUser(userId, reason, now));
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> activeFamiliesOf(String userId, Instant now) {
        Objects.requireNonNull(userId, "userId cannot be null");
        return guarded("list the sessions of a user", () ->
                springData.findByUserIdAndRevokedAtIsNullAndExpiresAtAfterOrderByIssuedAtAscIdAsc(userId, now).stream()
                        .map(RefreshTokenJpaEntity::getFamilyId)
                        .distinct()
                        .toList());
    }

    @Override
    @Transactional
    public int deleteExpiredBefore(Instant cutoff) {
        Objects.requireNonNull(cutoff, "cutoff cannot be null");
        return guarded("purge expired refresh tokens", () -> springData.deleteExpiredBefore(cutoff));
    }

    private static <T> T guarded(String action, java.util.function.Supplier<T> work) {
        try {
            return work.get();
        } catch (DataAccessException ex) {
            throw new PersistenceFailureException("Failed to " + action, ex);
        }
    }

    private RefreshToken toDomain(RefreshTokenJpaEntity e) {
        return new RefreshToken(e.getId(), e.getUserId(), e.getFamilyId(), e.getTokenHash(), e.getTokenVersion(),
                e.getIssuedAt(), e.getExpiresAt(), e.getRevokedAt(), e.getRevokedReason(), e.getReplacedById());
    }

    private RefreshTokenJpaEntity toEntity(RefreshToken t) {
        RefreshTokenJpaEntity e = new RefreshTokenJpaEntity();
        e.setUserId(t.userId());
        e.setFamilyId(t.familyId());
        e.setTokenHash(t.tokenHash());
        e.setTokenVersion(t.tokenVersion());
        e.setIssuedAt(t.issuedAt());
        e.setExpiresAt(t.expiresAt());
        e.setRevokedAt(t.revokedAt());
        e.setRevokedReason(t.revokedReason());
        e.setReplacedById(t.replacedById());
        return e;
    }
}
