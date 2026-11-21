package com.iitsm.domain.simulation.assignment;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * OOP: Immutable value object. The result of one strategy in a comparison (ADR-044, Phase L3): per metric the value of
 * every replication, the summary over the replications and, for every strategy but the reference, the paired
 * difference against the reference strategy.
 */
public record StrategyReport(
        String strategyName,
        Map<ComparisonMetric, List<Double>> perReplication,
        Map<ComparisonMetric, ReplicationStatistics.Summary> summaries,
        Map<ComparisonMetric, ReplicationStatistics.PairedDifference> versusReference) {

    public StrategyReport {
        Objects.requireNonNull(strategyName, "strategyName cannot be null");
        perReplication = copy(perReplication);
        summaries = copy(summaries);
        versusReference = copy(versusReference);
    }

    /** Whether this is the reference strategy (it has no difference against itself). */
    public boolean isReference() {
        return versusReference.isEmpty();
    }

    private static <T> Map<ComparisonMetric, T> copy(Map<ComparisonMetric, T> source) {
        Map<ComparisonMetric, T> copy = new EnumMap<>(ComparisonMetric.class);
        copy.putAll(source);
        return Collections.unmodifiableMap(copy);
    }
}
