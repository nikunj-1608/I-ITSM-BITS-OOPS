package com.iitsm.domain.simulation.assignment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Mean, spread, 95 % interval and paired difference over the replications (ADR-044, Phase L3). */
class ReplicationStatisticsTest {

    @Test
    @DisplayName("mean, sample standard deviation and the t-based 95 % half width of a known sample")
    void summaryOfAKnownSample() {
        ReplicationStatistics.Summary summary = ReplicationStatistics.summarize(List.of(2.0, 4.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0));

        assertThat(summary.mean()).isEqualTo(5.0);
        assertThat(summary.stdDev()).isCloseTo(Math.sqrt(32.0 / 7.0), within(1e-12));
        assertThat(summary.ci95HalfWidth()).isCloseTo(2.365 * Math.sqrt(32.0 / 7.0) / Math.sqrt(8), within(1e-9));
    }

    @Test
    @DisplayName("one replication has no spread: standard deviation and half width are 0")
    void singleReplication() {
        ReplicationStatistics.Summary summary = ReplicationStatistics.summarize(List.of(12.5));

        assertThat(summary.mean()).isEqualTo(12.5);
        assertThat(summary.stdDev()).isZero();
        assertThat(summary.ci95HalfWidth()).isZero();
        assertThatThrownBy(() -> ReplicationStatistics.summarize(List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("t critical values: tabulated up to 30 degrees, a close approximation above, never increasing")
    void tCriticalValues() {
        assertThat(ReplicationStatistics.tCritical95(1)).isEqualTo(12.706);
        assertThat(ReplicationStatistics.tCritical95(9)).isEqualTo(2.262);
        assertThat(ReplicationStatistics.tCritical95(30)).isEqualTo(2.042);
        assertThat(ReplicationStatistics.tCritical95(49)).isCloseTo(2.010, within(0.004));
        assertThat(ReplicationStatistics.tCritical95(1000)).isCloseTo(1.962, within(0.001));
        double previous = Double.MAX_VALUE;
        for (int df = 1; df <= 200; df++) {
            double value = ReplicationStatistics.tCritical95(df);
            assertThat(value).as("df=%d", df).isLessThanOrEqualTo(previous);
            previous = value;
        }
        assertThatThrownBy(() -> ReplicationStatistics.tCritical95(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("paired difference: identical values are not distinguishable, a constant offset is")
    void pairedDifference() {
        List<Double> reference = List.of(10.0, 12.0, 9.0, 11.0, 13.0);

        ReplicationStatistics.PairedDifference same = ReplicationStatistics.pairedDifference(reference, reference);
        assertThat(same.meanDifference()).isZero();
        assertThat(same.distinguishable()).isFalse();

        List<Double> slower = reference.stream().map(v -> v + 5.0).toList();
        ReplicationStatistics.PairedDifference offset = ReplicationStatistics.pairedDifference(slower, reference);
        assertThat(offset.meanDifference()).isCloseTo(5.0, within(1e-12));
        assertThat(offset.ci95HalfWidth()).isCloseTo(0.0, within(1e-9));
        assertThat(offset.distinguishable()).isTrue();
    }

    @Test
    @DisplayName("a difference smaller than its own noise is luck; one replication never proves anything")
    void noisyAndSingleDifferences() {
        List<Double> reference = List.of(10.0, 10.0, 10.0, 10.0);
        List<Double> noisy = List.of(8.0, 12.5, 9.0, 11.0);
        assertThat(ReplicationStatistics.pairedDifference(noisy, reference).distinguishable()).isFalse();

        ReplicationStatistics.PairedDifference single = ReplicationStatistics.pairedDifference(List.of(50.0), List.of(10.0));
        assertThat(single.meanDifference()).isEqualTo(40.0);
        assertThat(single.ci95HalfWidth()).isZero();
        assertThat(single.distinguishable()).isFalse();
    }

    @Test
    @DisplayName("both lists need one value per replication")
    void sizesMustMatch() {
        assertThatThrownBy(() -> ReplicationStatistics.pairedDifference(List.of(1.0), List.of(1.0, 2.0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReplicationStatistics.pairedDifference(List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
