package com.iitsm.domain.repository;

/**
 * Domain port for the per-department, per-year ticket counters.
 */
public interface TicketSequenceRepository {

    /**
     * Atomically reserves and returns the next sequence number (starting at 1) for the given
     * department code and year. Numbers are unique per (code, year) under concurrent callers and
     * are rolled back together with the surrounding transaction, so no gaps are left behind.
     */
    long next(String departmentCode, int year);
}
