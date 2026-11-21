package com.iitsm.application.service;

import com.iitsm.domain.exception.ConcurrentUpdateException;
import org.springframework.dao.ConcurrencyFailureException;

/**
 * Tells a lost write race (expected under contention, ADR-017) from a real failure. A lost race surfaces either
 * directly (at commit) or wrapped in a persistence failure (inside a query), so the whole cause chain is searched.
 */
final class ConcurrencyConflicts {

    private ConcurrencyConflicts() { }

    static boolean isConflict(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof ConcurrencyFailureException || cause instanceof ConcurrentUpdateException) {
                return true;
            }
        }
        return false;
    }
}
