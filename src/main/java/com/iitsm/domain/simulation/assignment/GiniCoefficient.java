package com.iitsm.domain.simulation.assignment;

import java.util.Arrays;

/**
 * OOP: Utility value computation. The Gini coefficient of a distribution of non-negative amounts (ADR-044, Phase L3),
 * the measure of how unevenly work is shared: 0 when everybody has the same, {@code (n - 1) / n} when one of
 * {@code n} has everything, always below 1.
 *
 * <p>Definition used: with the values sorted ascending, {@code G = 2 * sum(i * x_i) / (n * sum(x)) - (n + 1) / n}.
 * An empty list, a single value and a list that is all zeros have no inequality to measure: the result is 0.
 */
public final class GiniCoefficient {

    private GiniCoefficient() {
    }

    /** The coefficient of {@code values}; negative or non-finite values are refused. */
    public static double of(double... values) {
        if (values == null || values.length == 0) {
            return 0.0;
        }
        double[] sorted = values.clone();
        double total = 0.0;
        for (double value : sorted) {
            if (!(value >= 0.0) || !Double.isFinite(value)) {
                throw new IllegalArgumentException("values must be non-negative finite numbers");
            }
            total += value;
        }
        if (total == 0.0) {
            return 0.0;
        }
        Arrays.sort(sorted);
        int n = sorted.length;
        double weighted = 0.0;
        for (int i = 0; i < n; i++) {
            weighted += (i + 1) * sorted[i];
        }
        double gini = 2.0 * weighted / (n * total) - (n + 1.0) / n;
        return Math.max(0.0, gini);
    }
}
