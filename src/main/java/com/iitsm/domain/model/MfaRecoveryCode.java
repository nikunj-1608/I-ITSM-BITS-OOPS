package com.iitsm.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * OOP: Value object. One stored recovery code of a user (ADR-048): only the BCrypt hash of the code is kept, and
 * {@code usedAt} is set when it has been used (each code works once).
 */
public record MfaRecoveryCode(Long id, String userId, String codeHash, Instant usedAt, Instant createdAt) {

    public MfaRecoveryCode {
        Objects.requireNonNull(userId, "userId cannot be null");
        Objects.requireNonNull(codeHash, "codeHash cannot be null");
        Objects.requireNonNull(createdAt, "createdAt cannot be null");
    }

    public boolean isUsed() {
        return usedAt != null;
    }
}
