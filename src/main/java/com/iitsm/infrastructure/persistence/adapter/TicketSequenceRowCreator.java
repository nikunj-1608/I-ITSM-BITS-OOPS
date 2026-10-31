package com.iitsm.infrastructure.persistence.adapter;

import com.iitsm.infrastructure.persistence.repository.SpringDataTicketSequenceRepository;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the counter row for a department and year that has not issued a ticket yet. It runs in
 * its own transaction so the row is committed (and visible to concurrent callers) at once and a
 * lost creation race only fails this small transaction, never the ticket intake. Kept as a separate
 * bean so the transaction boundary goes through the Spring proxy. The row starts at 0, which is
 * not an issued number, so creating it can never leave a gap.
 */
@Component
class TicketSequenceRowCreator {

    private final SpringDataTicketSequenceRepository repository;

    TicketSequenceRowCreator(SpringDataTicketSequenceRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository cannot be null");
    }

    /** Fails with a duplicate-key error when another transaction created the row first. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void create(String departmentCode, int year) {
        repository.insertEmpty(departmentCode, year);
    }
}
