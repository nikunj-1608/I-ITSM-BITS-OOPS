package com.iitsm.infrastructure.persistence.adapter;

import com.iitsm.domain.exception.PersistenceFailureException;
import com.iitsm.domain.model.MfaRecoveryCode;
import com.iitsm.domain.repository.MfaRecoveryCodeRepository;
import com.iitsm.infrastructure.persistence.entity.MfaRecoveryCodeJpaEntity;
import com.iitsm.infrastructure.persistence.repository.SpringDataMfaRecoveryCodeRepository;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// OOP (Adapter + Dependency Inversion): MfaRecoveryCodeRepositoryAdapter implements a domain port with Spring Data/JPA; the domain never sees the database.
/**
 * Pattern: Adapter / Repository. Stores the recovery codes of two-step verification (ADR-048). The write methods carry
 * their own transaction, so they also work outside a service transaction; inside one they join it.
 */
@Component
public class MfaRecoveryCodeRepositoryAdapter implements MfaRecoveryCodeRepository {

    private final SpringDataMfaRecoveryCodeRepository springData;

    // OOP (Dependency Injection): collaborators are passed through the constructor (no field injection), stored in final fields.
    public MfaRecoveryCodeRepositoryAdapter(SpringDataMfaRecoveryCodeRepository springData) {
        this.springData = Objects.requireNonNull(springData, "springData cannot be null");
    }

    @Override
    @Transactional
    public void replaceAll(String userId, List<String> codeHashes, Instant now) {
        Objects.requireNonNull(userId, "userId cannot be null");
        Objects.requireNonNull(codeHashes, "codeHashes cannot be null");
        guarded("store recovery codes", () -> {
            springData.deleteByUserId(userId);
            springData.saveAll(codeHashes.stream().map(hash -> toEntity(userId, hash, now)).toList());
            return null;
        });
    }

    @Override
    @Transactional(readOnly = true)
    public List<MfaRecoveryCode> findUnused(String userId) {
        Objects.requireNonNull(userId, "userId cannot be null");
        return guarded("read recovery codes", () ->
                springData.findByUserIdAndUsedAtIsNullOrderByIdAsc(userId).stream().map(this::toDomain).toList());
    }

    @Override
    @Transactional
    public boolean markUsed(long codeId, Instant now) {
        return guarded("use a recovery code", () -> springData.markUsedIfUnused(codeId, now) == 1);
    }

    @Override
    @Transactional
    public void deleteAll(String userId) {
        Objects.requireNonNull(userId, "userId cannot be null");
        guarded("delete recovery codes", () -> springData.deleteByUserId(userId));
    }

    private static <T> T guarded(String action, Supplier<T> work) {
        try {
            return work.get();
        } catch (DataAccessException ex) {
            throw new PersistenceFailureException("Failed to " + action, ex);
        }
    }

    private MfaRecoveryCodeJpaEntity toEntity(String userId, String hash, Instant now) {
        MfaRecoveryCodeJpaEntity entity = new MfaRecoveryCodeJpaEntity();
        entity.setUserId(userId);
        entity.setCodeHash(hash);
        entity.setCreatedAt(now);
        return entity;
    }

    private MfaRecoveryCode toDomain(MfaRecoveryCodeJpaEntity e) {
        return new MfaRecoveryCode(e.getId(), e.getUserId(), e.getCodeHash(), e.getUsedAt(), e.getCreatedAt());
    }
}
