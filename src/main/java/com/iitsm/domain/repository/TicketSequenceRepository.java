package com.iitsm.domain.repository;

/**
 * Domain port for the per-department, per-year ticket counters.
 */
public interface TicketSequenceRepository {

    /**
     * Atomically reserves and returns the next sequence number (starting at 1) for the given
     * department code and year. Numbers are unique per (code, year) under concurrent callers.
     * Each number is reserved in its own short transaction, so callers never queue behind a
     * long-running ticket intake; the price is that a number is not given back if the caller
     * later rolls back, which leaves a gap in the sequence.
     */
    long next(String departmentCode, int year);
}
