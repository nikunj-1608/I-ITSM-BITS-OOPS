package com.iitsm.domain.simulation.assignment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The Gini coefficient of the offline comparison (ADR-044, Phase L3). */
class GiniCoefficientTest {

    @Test
    @DisplayName("everybody equal gives 0, whatever the number of technicians and the amount")
    void equalShares() {
        for (int n = 1; n <= 30; n++) {
            double[] values = new double[n];
            java.util.Arrays.fill(values, 7.5);
            assertThat(GiniCoefficient.of(values)).as("n=%d", n).isCloseTo(0.0, within(1e-12));
        }
    }

    @Test
    @DisplayName("one technician with everything gives (n - 1) / n")
    void oneHasEverything() {
        for (int n = 2; n <= 30; n++) {
            double[] values = new double[n];
            values[n / 2] = 120.0;
            assertThat(GiniCoefficient.of(values)).as("n=%d", n).isCloseTo((n - 1.0) / n, within(1e-12));
        }
    }

    @Test
    @DisplayName("a known distribution: 1, 2, 3, 4 gives 0.25")
    void knownValue() {
        assertThat(GiniCoefficient.of(1, 2, 3, 4)).isCloseTo(0.25, within(1e-12));
        assertThat(GiniCoefficient.of(4, 1, 3, 2)).as("order does not matter").isCloseTo(0.25, within(1e-12));
        assertThat(GiniCoefficient.of(0, 2, 2)).isCloseTo(1.0 / 3.0, within(1e-12));
    }

    @Test
    @DisplayName("empty, single and all-zero lists have no inequality to measure: 0")
    void definedWhereThereIsNothingToMeasure() {
        assertThat(GiniCoefficient.of()).isZero();
        assertThat(GiniCoefficient.of((double[]) null)).isZero();
        assertThat(GiniCoefficient.of(5.0)).isZero();
        assertThat(GiniCoefficient.of(0, 0, 0, 0)).isZero();
    }

    @Test
    @DisplayName("it is always in [0, 1) and the input array is left alone")
    void rangeAndNoSideEffect() {
        Random random = new Random(7);
        for (int run = 0; run < 500; run++) {
            double[] values = random.doubles(1 + random.nextInt(40), 0, 100).toArray();
            double[] copy = values.clone();
            double gini = GiniCoefficient.of(values);
            assertThat(gini).isBetween(0.0, 1.0).isLessThan(1.0);
            assertThat(values).isEqualTo(copy);
        }
    }

    @Test
    @DisplayName("negative and non-finite amounts are refused")
    void refusesBadValues() {
        assertThatThrownBy(() -> GiniCoefficient.of(1, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GiniCoefficient.of(1, Double.NaN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GiniCoefficient.of(1, Double.POSITIVE_INFINITY)).isInstanceOf(IllegalArgumentException.class);
    }
}
