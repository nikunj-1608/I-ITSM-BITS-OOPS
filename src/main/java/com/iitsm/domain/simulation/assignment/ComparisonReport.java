package com.iitsm.domain.simulation.assignment;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * OOP: Immutable value object. The result of an offline strategy comparison (ADR-044, Phase L3): the parameters, the
 * reference strategy the differences are measured against, the number of simulated technicians, one
 * {@link StrategyReport} per strategy (alphabetical), the winner(s) of every metric (the lowest mean; several names
 * when tied) and the assumptions of the model. It holds no timing, so the same seed gives an equal report.
 */
public record ComparisonReport(
        ComparisonParameters parameters,
        String referenceStrategy,
        int technicianCount,
        List<StrategyReport> strategies,
        Map<ComparisonMetric, List<String>> winners,
        List<String> assumptions) {

    public ComparisonReport {
        Objects.requireNonNull(parameters, "parameters cannot be null");
        Objects.requireNonNull(referenceStrategy, "referenceStrategy cannot be null");
        // OOP (Immutability in a record): the compact constructor replaces mutable inputs with unmodifiable copies.
        strategies = List.copyOf(strategies);
        Map<ComparisonMetric, List<String>> copy = new EnumMap<>(ComparisonMetric.class);
        winners.forEach((metric, names) -> copy.put(metric, List.copyOf(names)));
        winners = Collections.unmodifiableMap(copy);
        assumptions = List.copyOf(assumptions);
    }
}
