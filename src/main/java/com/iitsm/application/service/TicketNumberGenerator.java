package com.iitsm.application.service;

import com.iitsm.domain.model.TicketNumber;
import com.iitsm.domain.model.User;
import com.iitsm.domain.policy.DepartmentCodeResolver;
import com.iitsm.domain.repository.TicketSequenceRepository;
import java.time.Clock;
import java.time.Year;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Pattern: Domain service facade. Issues the next {@link TicketNumber} for a reporter by combining
 * the reporter's department (via the resolver strategy), the year from the injected clock, and the
 * persisted per-department, per-year counter.
 */
@Service
public class TicketNumberGenerator {

    private final DepartmentCodeResolver departmentCodeResolver;
    private final TicketSequenceRepository sequenceRepository;
    private final Clock clock;

    public TicketNumberGenerator(
            DepartmentCodeResolver departmentCodeResolver,
            TicketSequenceRepository sequenceRepository,
            Clock clock) {
        this.departmentCodeResolver =
                Objects.requireNonNull(departmentCodeResolver, "departmentCodeResolver cannot be null");
        this.sequenceRepository = Objects.requireNonNull(sequenceRepository, "sequenceRepository cannot be null");
        this.clock = Objects.requireNonNull(clock, "Clock cannot be null");
    }

    public TicketNumber nextFor(User reporter) {
        Objects.requireNonNull(reporter, "reporter cannot be null");
        String code = departmentCodeResolver.codeFor(reporter.getDepartment());
        int year = Year.now(clock).getValue();
        return new TicketNumber(code, year, sequenceRepository.next(code, year));
    }
}
