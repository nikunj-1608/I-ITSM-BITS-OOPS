package com.iitsm.infrastructure.persistence.repository;

import com.iitsm.infrastructure.persistence.entity.MfaRecoveryCodeJpaEntity;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// OOP (Interface inheritance): extends the Spring Data interface, so CRUD methods are inherited and the framework writes the implementation.
/** Spring Data access to {@code mfa_recovery_codes} (ADR-048). */
public interface SpringDataMfaRecoveryCodeRepository extends JpaRepository<MfaRecoveryCodeJpaEntity, Long> {

    List<MfaRecoveryCodeJpaEntity> findByUserIdAndUsedAtIsNullOrderByIdAsc(String userId);

    /** One conditional statement: a code can be used once even when two sign-ins present it at the same moment. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE MfaRecoveryCodeJpaEntity c SET c.usedAt = :now WHERE c.id = :id AND c.usedAt IS NULL")
    int markUsedIfUnused(@Param("id") long id, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM MfaRecoveryCodeJpaEntity c WHERE c.userId = :userId")
    int deleteByUserId(@Param("userId") String userId);
}
