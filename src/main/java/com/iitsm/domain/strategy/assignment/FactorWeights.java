package com.iitsm.domain.strategy.assignment;

import com.iitsm.domain.model.Priority;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * OOP: Immutable value object. The weight of every assignment factor for every priority (ADR-044). The weights of one
 * priority must sum to 1, so a composite score stays in 0..1.
 *
 * <p>The default weights are the ones the weighted composite strategy always used: critical tickets (P1, P2) favour
 * skills and experience, low-urgency tickets (P4) favour free capacity, P3 is the baseline.
 */
public final class FactorWeights {

    /** P1 and P2: skills and experience before load. */
    public static final Map<String, Double> CRITICAL = Map.of(
            SkillMatchFactor.KEY, 0.45, LoadFactor.KEY, 0.20, ExperienceFactor.KEY, 0.35);
    /** P3: the baseline. */
    public static final Map<String, Double> BASELINE = Map.of(
            SkillMatchFactor.KEY, 0.50, LoadFactor.KEY, 0.30, ExperienceFactor.KEY, 0.20);
    /** P4: balance the load first. */
    public static final Map<String, Double> LOW_URGENCY = Map.of(
            SkillMatchFactor.KEY, 0.30, LoadFactor.KEY, 0.50, ExperienceFactor.KEY, 0.20);

    private static final double SUM_TOLERANCE = 1e-9;

    private final Map<Priority, Map<String, Double>> byPriority;

    /** Weights for every priority; each priority's weights must sum to 1. */
    public FactorWeights(Map<Priority, Map<String, Double>> byPriority) {
        Objects.requireNonNull(byPriority, "byPriority cannot be null");
        EnumMap<Priority, Map<String, Double>> copy = new EnumMap<>(Priority.class);
        for (Priority priority : Priority.values()) {
            Map<String, Double> weights = byPriority.get(priority);
            if (weights == null || weights.isEmpty()) {
                throw new IllegalArgumentException("no factor weights for priority " + priority);
            }
            requireSumOfOne(priority, weights.values());
            copy.put(priority, Map.copyOf(weights));
        }
        this.byPriority = copy;
    }

    /** The weights the weighted composite strategy has always used. */
    public static FactorWeights defaults() {
        return new FactorWeights(Map.of(Priority.P1, CRITICAL, Priority.P2, CRITICAL,
                Priority.P3, BASELINE, Priority.P4, LOW_URGENCY));
    }

    /**
     * A table with one more factor of weight {@code weight} (0 up to but excluding 1): every existing weight is scaled by
     * {@code 1 - weight}, so each priority still sums to 1 and the factors keep their proportions. A weight of 0 returns
     * this table unchanged (the factor is off).
     */
    public FactorWeights withFactor(String factorKey, double weight) {
        if (!(weight >= 0.0) || !(weight < 1.0)) {
            throw new IllegalArgumentException("a factor weight must be at least 0 and below 1");
        }
        if (covers(factorKey)) {
            throw new IllegalArgumentException("the table already has a weight for " + factorKey);
        }
        if (weight == 0.0) {
            return this;
        }
        Map<Priority, Map<String, Double>> next = new EnumMap<>(Priority.class);
        for (Map.Entry<Priority, Map<String, Double>> entry : byPriority.entrySet()) {
            Map<String, Double> scaled = new java.util.HashMap<>();
            entry.getValue().forEach((key, value) -> scaled.put(key, value * (1.0 - weight)));
            scaled.put(factorKey, weight);
            next.put(entry.getKey(), scaled);
        }
        return new FactorWeights(next);
    }

    /** The weight of {@code factorKey} for {@code priority}. */
    public double weightOf(Priority priority, String factorKey) {
        Double weight = byPriority.get(Objects.requireNonNull(priority, "priority cannot be null")).get(factorKey);
        if (weight == null) {
            throw new IllegalArgumentException("no weight for factor " + factorKey + " at priority " + priority);
        }
        return weight;
    }

    /** Whether every priority has a weight for {@code factorKey}. */
    public boolean covers(String factorKey) {
        return byPriority.values().stream().allMatch(weights -> weights.containsKey(factorKey));
    }

    private static void requireSumOfOne(Priority priority, Collection<Double> weights) {
        double sum = 0.0;
        for (Double weight : weights) {
            if (weight == null || !Double.isFinite(weight) || weight < 0.0) {
                throw new IllegalArgumentException("weights must be finite and not negative (" + priority + ")");
            }
            sum += weight;
        }
        if (Math.abs(sum - 1.0) > SUM_TOLERANCE) {
            throw new IllegalArgumentException("the weights of " + priority + " sum to " + sum + ", not 1");
        }
    }
}
