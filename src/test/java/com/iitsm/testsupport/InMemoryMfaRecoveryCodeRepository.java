package com.iitsm.testsupport;

import com.iitsm.domain.model.MfaRecoveryCode;
import com.iitsm.domain.repository.MfaRecoveryCodeRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Test double of the {@link MfaRecoveryCodeRepository} port with the rules of the database adapter. */
public class InMemoryMfaRecoveryCodeRepository implements MfaRecoveryCodeRepository {

    private final Map<Long, MfaRecoveryCode> rows = new LinkedHashMap<>();
    private final AtomicLong ids = new AtomicLong();

    @Override
    public synchronized void replaceAll(String userId, List<String> codeHashes, Instant now) {
        deleteAll(userId);
        for (String hash : codeHashes) {
            long id = ids.incrementAndGet();
            rows.put(id, new MfaRecoveryCode(id, userId, hash, null, now));
        }
    }

    @Override
    public synchronized List<MfaRecoveryCode> findUnused(String userId) {
        return rows.values().stream().filter(c -> c.userId().equals(userId) && !c.isUsed()).toList();
    }

    @Override
    public synchronized boolean markUsed(long codeId, Instant now) {
        MfaRecoveryCode code = rows.get(codeId);
        if (code == null || code.isUsed()) {
            return false;
        }
        rows.put(codeId, new MfaRecoveryCode(code.id(), code.userId(), code.codeHash(), now, code.createdAt()));
        return true;
    }

    @Override
    public synchronized void deleteAll(String userId) {
        rows.values().removeIf(c -> c.userId().equals(userId));
    }

    public synchronized List<MfaRecoveryCode> all() {
        return new ArrayList<>(rows.values());
    }
}
