package com.iitsm.domain.repository;

import com.iitsm.domain.model.MfaRecoveryCode;
import java.time.Instant;
import java.util.List;

/** Port for the recovery codes of two-step verification (ADR-048). Pattern: Repository. */
public interface MfaRecoveryCodeRepository {

    /** Replaces all of the user's codes by new ones made from these hashes (earlier codes stop working). */
    void replaceAll(String userId, List<String> codeHashes, Instant now);

    /** The user's codes that have not been used yet, oldest first. */
    List<MfaRecoveryCode> findUnused(String userId);

    /** Marks a code used only if nobody used it before; false when someone else got there first. */
    boolean markUsed(long codeId, Instant now);

    void deleteAll(String userId);
}
