package com.iitsm.domain.repository;

/**
 * Domain port for the per-department, per-year ticket counters.
 */
public interface TicketSequenceRepository {

    /**
     * Atomically reserves and returns the next sequence number (starting at 1) for the given
     * department code and year. Must be called inside the caller's transaction: the number is
     * reserved with that transaction, so it is unique under concurrent callers (they wait for the
     * transaction to finish) and it is given back if the transaction rolls back, so the sequence
     * never skips a number. The price is that tickets of one department and year are issued one
     * transaction at a time.
     */
    long next(String departmentCode, int year);
}
