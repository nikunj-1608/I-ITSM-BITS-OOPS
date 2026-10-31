package com.iitsm.application.service;

import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.User;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Pattern: Factory.
 * Constructs new Incident aggregate roots, enforcing validation and temporal consistency via Clock.
 * The incident id is the ticket number issued for the reporter's department (e.g. FIN-2026-000142).
 */
@Component
public class IncidentFactory {

    private final Clock clock;
    private final TicketNumberGenerator ticketNumberGenerator;

    public IncidentFactory(Clock clock, TicketNumberGenerator ticketNumberGenerator) {
        this.clock = Objects.requireNonNull(clock, "Clock cannot be null");
        this.ticketNumberGenerator =
                Objects.requireNonNull(ticketNumberGenerator, "ticketNumberGenerator cannot be null");
    }

    public Incident createIncident(String title, String description, User reporter) {
        Objects.requireNonNull(reporter, "reporter cannot be null");
        String id = ticketNumberGenerator.nextFor(reporter).format();
        Instant now = clock.instant();
        return new Incident(id, title, description, reporter.getId(), now);
    }
}
