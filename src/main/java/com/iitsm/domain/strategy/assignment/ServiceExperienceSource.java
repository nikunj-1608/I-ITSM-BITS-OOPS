package com.iitsm.domain.strategy.assignment;

import java.util.Collection;
import java.util.Map;

/**
 * Pattern: Port (hexagonal). Where the assignment strategies learn how much past experience technicians have with an IT
 * service (ADR-044, Phase L4): the number of tickets of the service each technician has resolved. The strategies and
 * the {@link ServiceExperienceFactor} depend on this interface only; the application wires an adapter that reads the
 * resolved tickets, the offline comparison wires an in-memory one, so no strategy ever queries a repository itself.
 *
 * <p>Implementations are read-only and answer for the requested technicians only; a technician with no resolved ticket of
 * the service may be absent from the answer (the same as 0).
 */
@FunctionalInterface
public interface ServiceExperienceSource {

    /**
     * Tickets of {@code serviceId} resolved by each of {@code technicianIds}, counting only fixes that stand (a ticket the
     * employee sent back is not counted until it is resolved again).
     */
    Map<String, Integer> resolvedTicketCounts(String serviceId, Collection<String> technicianIds);

    /** A source that knows nothing: every technician is as (in)experienced as every other. */
    static ServiceExperienceSource none() {
        return (serviceId, technicianIds) -> Map.of();
    }
}
