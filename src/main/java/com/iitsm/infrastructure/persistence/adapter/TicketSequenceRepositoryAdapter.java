package com.iitsm.infrastructure.persistence.adapter;

import com.iitsm.domain.exception.PersistenceFailureException;
import com.iitsm.domain.repository.TicketSequenceRepository;
import java.util.Objects;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * Adapter implementing the domain TicketSequenceRepository port. Deliberately not transactional
 * itself: each attempt runs in {@link TicketSequenceCounter}'s own transaction, and a lost race on
 * creating a new counter row is retried here, outside the failed transaction.
 */
@Component
public class TicketSequenceRepositoryAdapter implements TicketSequenceRepository {

    /** Only the very first number of a department/year can collide, so one retry suffices. */
    private static final int MAX_ATTEMPTS = 3;

    private final TicketSequenceCounter counter;

    public TicketSequenceRepositoryAdapter(TicketSequenceCounter counter) {
        this.counter = Objects.requireNonNull(counter, "counter cannot be null");
    }

    @Override
    public long next(String departmentCode, int year) {
        Objects.requireNonNull(departmentCode, "departmentCode cannot be null");
        DataAccessException lastFailure = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                return counter.reserve(departmentCode, year);
            } catch (DataIntegrityViolationException ex) {
                lastFailure = ex;
            } catch (DataAccessException ex) {
                throw new PersistenceFailureException(
                        "Failed to reserve ticket number for " + departmentCode + "-" + year, ex);
            }
        }
        throw new PersistenceFailureException(
                "Could not reserve a ticket number for " + departmentCode + "-" + year
                        + " after " + MAX_ATTEMPTS + " attempts", lastFailure);
    }
}
