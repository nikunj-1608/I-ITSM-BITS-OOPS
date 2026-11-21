package com.iitsm.domain.simulation.assignment;

/**
 * OOP: Enumeration. The metrics of the offline strategy comparison (ADR-044, Phase L3). Every metric is "lower is
 * better", so the winner of a metric is the strategy with the lowest mean over the replications.
 */
public enum ComparisonMetric {
    MEAN_TIME_TO_RESOLVE("Mean time to resolve", "min"),
    P95_TIME_TO_RESOLVE("95th percentile time to resolve", "min"),
    SLA_BREACH_RATE("SLA resolution breach rate", "share"),
    MEAN_WAIT("Mean wait before assignment", "min"),
    MAX_QUEUE_LENGTH("Maximum queue length", "tickets"),
    UNSKILLED_SHARE("Tickets given to a technician without the category skill", "share"),
    TICKETS_GINI("Fairness: Gini of tickets handled", "gini"),
    BUSY_TIME_GINI("Fairness: Gini of busy time", "gini");

    private final String label;
    private final String unit;

    ComparisonMetric(String label, String unit) {
        this.label = label;
        this.unit = unit;
    }

    /** Stable key used in the API. */
    public String key() {
        return name();
    }

    /** Human label. */
    public String label() {
        return label;
    }

    /** Unit: {@code min}, {@code share} (0..1), {@code tickets} or {@code gini} (0..1). */
    public String unit() {
        return unit;
    }
}
