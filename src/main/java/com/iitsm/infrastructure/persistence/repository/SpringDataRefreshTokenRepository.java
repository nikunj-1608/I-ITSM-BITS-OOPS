package com.iitsm.infrastructure.persistence.repository;

import com.iitsm.infrastructure.persistence.entity.RefreshTokenJpaEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data access to {@code refresh_tokens} (ADR-047). Every update is a conditional single statement
 * ({@code revokedAt IS NULL}), so a token's first revocation wins and two concurrent refreshes of one token cannot both
 * rotate it.
 */
public interface SpringDataRefreshTokenRepository extends JpaRepository<RefreshTokenJpaEntity, Long> {

    Optional<RefreshTokenJpaEntity> findByTokenHash(String tokenHash);

    boolean existsByFamilyIdAndRevokedAtIsNullAndExpiresAtAfter(String familyId, Instant now);

    List<RefreshTokenJpaEntity> findByUserIdAndRevokedAtIsNullAndExpiresAtAfterOrderByIssuedAtAscIdAsc(
            String userId, Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE RefreshTokenJpaEntity t SET t.revokedAt = :now, t.revokedReason = :reason "
            + "WHERE t.id = :id AND t.revokedAt IS NULL")
    int revokeIfActive(@Param("id") long id, @Param("reason") String reason, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE RefreshTokenJpaEntity t SET t.replacedById = :replacementId WHERE t.id = :id")
    int setReplacedBy(@Param("id") long id, @Param("replacementId") long replacementId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE RefreshTokenJpaEntity t SET t.revokedAt = :now, t.revokedReason = :reason "
            + "WHERE t.familyId = :familyId AND t.revokedAt IS NULL")
    int revokeFamily(@Param("familyId") String familyId, @Param("reason") String reason, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE RefreshTokenJpaEntity t SET t.revokedAt = :now, t.revokedReason = :reason "
            + "WHERE t.userId = :userId AND t.revokedAt IS NULL")
    int revokeAllForUser(@Param("userId") String userId, @Param("reason") String reason, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM RefreshTokenJpaEntity t WHERE t.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
