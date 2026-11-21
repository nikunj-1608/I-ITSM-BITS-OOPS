package com.iitsm.domain.simulation.assignment;

import java.util.Arrays;
import java.util.List;

/**
 * OOP: Utility value computation. Statistics over the replications of the offline comparison (ADR-044, Phase L3): the
 * mean, the sample standard deviation and the half width of the 95 % confidence interval of the mean
 * ({@code t * s / sqrt(n)}), and the paired difference of a strategy against the reference, replication by
 * replication (the replications are paired because every strategy replays the same stream).
 *
 * <p>With fewer than two replications there is no spread to estimate: the standard deviation and the half width are 0
 * and no difference is called distinguishable.
 */
public final class ReplicationStatistics {

    /** Two-sided 95 % critical values of the t distribution for 1 to 30 degrees of freedom. */
    private static final double[] T_95 = {
        12.706, 4.303, 3.182, 2.776, 2.571, 2.447, 2.365, 2.306, 2.262, 2.228, 2.201, 2.179, 2.160, 2.145, 2.131,
        2.120, 2.110, 2.101, 2.093, 2.086, 2.080, 2.074, 2.069, 2.064, 2.060, 2.056, 2.052, 2.048, 2.045, 2.042};
    private static final double Z_95 = 1.959964;
    /** (z^3 + z) / 4: the first Cornish-Fisher term, used above 30 degrees of freedom. */
    private static final double TAIL_TERM = (Z_95 * Z_95 * Z_95 + Z_95) / 4.0;

    private ReplicationStatistics() {
    }

    /**
     * OOP: Immutable value object. Mean, sample standard deviation and 95 % half width of one metric.
     */
    public record Summary(double mean, double stdDev, double ci95HalfWidth) {
    }

    /**
     * OOP: Immutable value object. How much a strategy differs from the reference on one metric, with the 95 % half
     * width; {@code distinguishable} says the interval excludes 0, i.e. the difference is not just luck.
     */
    public record PairedDifference(double meanDifference, double ci95HalfWidth, boolean distinguishable) {
    }

    /** Summary of the values of one metric, one per replication. */
    public static Summary summarize(List<Double> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("at least one replication is needed");
        }
        double mean = mean(values);
        double stdDev = stdDev(values, mean);
        return new Summary(mean, stdDev, halfWidth(stdDev, values.size()));
    }

    /** The differences {@code values[i] - reference[i]} summarised; both lists have one value per replication. */
    public static PairedDifference pairedDifference(List<Double> values, List<Double> reference) {
        if (values.size() != reference.size() || values.isEmpty()) {
            throw new IllegalArgumentException("both lists need one value per replication");
        }
        double[] differences = new double[values.size()];
        for (int i = 0; i < differences.length; i++) {
            differences[i] = values.get(i) - reference.get(i);
        }
        Summary summary = summarize(Arrays.stream(differences).boxed().toList());
        boolean distinguishable = values.size() >= 2 && Math.abs(summary.mean()) > summary.ci95HalfWidth();
        return new PairedDifference(summary.mean(), summary.ci95HalfWidth(), distinguishable);
    }

    /** Two-sided 95 % critical value of the t distribution. */
    public static double tCritical95(int degreesOfFreedom) {
        if (degreesOfFreedom < 1) {
            throw new IllegalArgumentException("degrees of freedom must be at least 1");
        }
        if (degreesOfFreedom <= T_95.length) {
            return T_95[degreesOfFreedom - 1];
        }
        return Z_95 + TAIL_TERM / degreesOfFreedom;
    }

    private static double mean(List<Double> values) {
        double sum = 0.0;
        for (double value : values) {
            sum += value;
        }
        return sum / values.size();
    }

    private static double stdDev(List<Double> values, double mean) {
        if (values.size() < 2) {
            return 0.0;
        }
        double squares = 0.0;
        for (double value : values) {
            squares += (value - mean) * (value - mean);
        }
        return Math.sqrt(squares / (values.size() - 1));
    }

    private static double halfWidth(double stdDev, int n) {
        return n < 2 ? 0.0 : tCritical95(n - 1) * stdDev / Math.sqrt(n);
    }
}
