package com.iitsm.domain.simulation.assignment;

import com.iitsm.domain.model.Incident;
import java.util.Objects;

/**
 * OOP: Immutable pairing of a synthetic ticket with the in-memory {@link Incident} the strategies read (ADR-044,
 * Phase L3). The incident is built without any ticket-number sequence or repository (id {@code SIM-000001}), is never
 * assigned or saved, and is shared by every strategy of one replication, which is what makes the stream identical.
 */
public record PreparedTicket(SyntheticTicket spec, Incident incident) {

    public PreparedTicket {
        Objects.requireNonNull(spec, "spec cannot be null");
        Objects.requireNonNull(incident, "incident cannot be null");
    }
}
