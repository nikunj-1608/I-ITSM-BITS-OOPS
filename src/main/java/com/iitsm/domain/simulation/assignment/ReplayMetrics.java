package com.iitsm.domain.simulation.assignment;

import com.iitsm.domain.model.Priority;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pattern: Utility computation. Turns the outcome of one replay into the eight metrics of the comparison (ADR-044,
 * Phase L3): mean and 95th percentile (nearest rank) of the time to resolve, the SLA resolution breach rate against the
 * resolution target of each ticket's priority, the mean wait before assignment, the longest queue, the share of tickets
 * given to a technician without the category skill, and the Gini coefficient of the tickets handled and of the busy
 * time over every technician of the pool (one who handled nothing counts as 0).
 */
public final class ReplayMetrics {

    private static final double P95 = 0.95;

    private ReplayMetrics() {
    }

    /** The metrics of a replay; {@code resolutionTargets} must hold a target for every priority. */
    public static Map<ComparisonMetric, Double> of(ReplayResult result, Map<Priority, Duration> resolutionTargets) {
        Objects.requireNonNull(result, "result cannot be null");
        List<ReplayedTicket> tickets = result.tickets();
        if (tickets.isEmpty()) {
            throw new IllegalArgumentException("a replay without tickets has no metrics");
        }
        double[] resolveMinutes = tickets.stream().mapToDouble(ReplayedTicket::timeToResolveMinutes).toArray();
        // Collections: EnumMap is the fastest, ordered map when the key is an enum.
        Map<ComparisonMetric, Double> metrics = new EnumMap<>(ComparisonMetric.class);
        metrics.put(ComparisonMetric.MEAN_TIME_TO_RESOLVE, Arrays.stream(resolveMinutes).average().orElse(0.0));
        metrics.put(ComparisonMetric.P95_TIME_TO_RESOLVE, percentile(resolveMinutes, P95));
        metrics.put(ComparisonMetric.SLA_BREACH_RATE, breachRate(tickets, resolutionTargets));
        metrics.put(ComparisonMetric.MEAN_WAIT,
                tickets.stream().mapToDouble(ReplayedTicket::waitMinutes).average().orElse(0.0));
        metrics.put(ComparisonMetric.MAX_QUEUE_LENGTH, (double) result.maxQueueLength());
        metrics.put(ComparisonMetric.UNSKILLED_SHARE,
                tickets.stream().filter(ReplayedTicket::unskilled).count() / (double) tickets.size());
        metrics.put(ComparisonMetric.TICKETS_GINI, GiniCoefficient.of(
                result.ticketsHandled().values().stream().mapToDouble(Integer::doubleValue).toArray()));
        metrics.put(ComparisonMetric.BUSY_TIME_GINI,
                GiniCoefficient.of(result.busyMinutes().values().stream().mapToDouble(Double::doubleValue).toArray()));
        return Collections.unmodifiableMap(metrics);
    }

    /** Nearest-rank percentile: the smallest value with at least {@code share} of the values at or below it. */
    static double percentile(double[] values, double share) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int rank = (int) Math.ceil(share * sorted.length);
        return sorted[Math.min(sorted.length, Math.max(1, rank)) - 1];
    }

    private static double breachRate(List<ReplayedTicket> tickets, Map<Priority, Duration> targets) {
        long breached = tickets.stream().filter(t -> {
            Duration target = Objects.requireNonNull(targets.get(t.priority()), "no resolution target for " + t.priority());
            return Duration.between(t.arrivedAt(), t.completedAt()).compareTo(target) > 0;
        }).count();
        return breached / (double) tickets.size();
    }
}
