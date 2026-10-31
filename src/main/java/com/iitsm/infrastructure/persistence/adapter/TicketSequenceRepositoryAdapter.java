package com.iitsm.infrastructure.persistence.adapter;

import com.iitsm.domain.exception.PersistenceFailureException;
import com.iitsm.domain.repository.TicketSequenceRepository;
import com.iitsm.infrastructure.persistence.repository.SpringDataTicketSequenceRepository;
import java.util.Objects;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adapter implementing the domain TicketSequenceRepository port. The increment is a single atomic
 * UPDATE inside the caller's transaction (MANDATORY: outside one, the increment and the read could
 * interleave with another caller), so the row lock queues concurrent callers and a rollback gives
 * the number back. Only the very first ticket of a department and year needs a separate, short
 * transaction to create the counter row.
 */
@Component
public class TicketSequenceRepositoryAdapter implements TicketSequenceRepository {

    private final SpringDataTicketSequenceRepository repository;
    private final TicketSequenceRowCreator rowCreator;

    public TicketSequenceRepositoryAdapter(
            SpringDataTicketSequenceRepository repository, TicketSequenceRowCreator rowCreator) {
        this.repository = Objects.requireNonNull(repository, "repository cannot be null");
        this.rowCreator = Objects.requireNonNull(rowCreator, "rowCreator cannot be null");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public long next(String departmentCode, int year) {
        Objects.requireNonNull(departmentCode, "departmentCode cannot be null");
        try {
            if (repository.increment(departmentCode, year) == 0) {
                createRowIfAbsent(departmentCode, year);
                if (repository.increment(departmentCode, year) == 0) {
                    throw new PersistenceFailureException(
                            "Ticket counter missing right after creation: " + departmentCode + "-" + year);
                }
            }
            return repository.currentValue(departmentCode, year);
        } catch (DataAccessException ex) {
            throw new PersistenceFailureException(
                    "Failed to reserve ticket number for " + departmentCode + "-" + year, ex);
        }
    }

    /** A duplicate key only means another caller created the row first, which is what we wanted. */
    private void createRowIfAbsent(String departmentCode, int year) {
        try {
            rowCreator.create(departmentCode, year);
        } catch (DataIntegrityViolationException alreadyCreated) {
            // expected race: the row now exists
        }
    }
}
