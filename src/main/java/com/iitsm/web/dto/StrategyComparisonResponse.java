package com.iitsm.web.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

// OOP (Immutability): StrategyComparisonResponse is a record, so it is a read-only data carrier with no setters.
/**
 * Answer of the offline strategy comparison (ADR-044, Phase L3), for administrators. It is read-only and, apart from
 * {@code runMillis}, equal for equal requests.
 *
 * @param notice  states that these are simulated results from a synthetic work-time model, not production data
 * @param winners per metric key the strategy name(s) with the lowest mean
 */
public record StrategyComparisonResponse(
        Parameters parameters,
        String referenceStrategy,
        String poolSource,
        int technicianCount,
        List<MetricInfo> metrics,
        List<StrategyView> strategies,
        Map<String, List<String>> winners,
        List<String> assumptions,
        long runMillis,
        String notice) {

    /** The parameters the run used, defaults filled in. */
    public record Parameters(long seed, int ticketCount, int replications, double arrivalsPerHour,
            List<String> strategies, Instant startInstant) {
    }

    /** What a metric key means. */
    public record MetricInfo(String key, String label, String unit) {
    }

    /** One strategy's results by metric key, in the order of {@code metrics}. */
    public record StrategyView(String name, boolean reference, Map<String, MetricView> metrics) {
    }

    /**
     * One metric of one strategy: the mean over the replications, its standard deviation and 95 % half width, every
     * replication's value, and (not for the reference) the paired difference against the reference with its half width
     * and whether the difference is distinguishable from luck.
     */
    public record MetricView(
            double mean,
            double stdDev,
            double ci95HalfWidth,
            List<Double> perReplication,
            Double differenceVsReference,
            Double differenceCi95HalfWidth,
            Boolean distinguishable) {
    }
}
