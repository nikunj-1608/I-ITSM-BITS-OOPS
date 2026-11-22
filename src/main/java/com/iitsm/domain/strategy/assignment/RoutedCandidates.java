package com.iitsm.domain.strategy.assignment;

import com.iitsm.domain.model.Technician;
import java.util.List;
import java.util.Objects;

/**
 * OOP: Immutable value object. The technicians who may take a ticket now, and which pool they came from (ADR-036,
 * ADR-044): the owning team, or skilled technicians of other teams when the owning team had nobody free. An empty list
 * means the ticket waits.
 */
public record RoutedCandidates(PoolKind pool, List<Technician> technicians) {

    public RoutedCandidates {
        Objects.requireNonNull(pool, "pool cannot be null");
        if (!pool.isRankedByStrategy()) {
            throw new IllegalArgumentException("routed candidates come from the owning team or skilled overflow, not " + pool);
        }
        technicians = List.copyOf(Objects.requireNonNull(technicians, "technicians cannot be null"));
    }

    /** Nobody may take the ticket now. */
    public static RoutedCandidates none() {
        return new RoutedCandidates(PoolKind.OWNING_TEAM, List.of());
    }

    public boolean isEmpty() {
        return technicians.isEmpty();
    }
}
