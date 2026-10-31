package com.iitsm.infrastructure.persistence.adapter;

import com.iitsm.infrastructure.persistence.repository.SpringDataTicketSequenceRepository;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * One attempt to reserve the next ticket number, run in its own transaction so the counter row is
 * locked only for the few milliseconds of the attempt and not for the whole ticket intake.
 * Kept as a separate bean so the transaction boundary goes through the Spring proxy.
 */
@Component
class TicketSequenceCounter {

    private static final long FIRST_SEQUENCE = 1L;

    private final SpringDataTicketSequenceRepository repository;

    TicketSequenceCounter(SpringDataTicketSequenceRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository cannot be null");
    }

    /**
     * Increments the counter, or creates it at 1 when absent. A concurrent creator makes the insert
     * fail with a duplicate key; the caller retries and then takes the increment path.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long reserve(String departmentCode, int year) {
        if (repository.increment(departmentCode, year) > 0) {
            return repository.currentValue(departmentCode, year);
        }
        repository.insertFirst(departmentCode, year);
        return FIRST_SEQUENCE;
    }
}
