package com.iitsm.domain.simulation.assignment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.Priority;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The eight metrics of a replay, on a hand-made outcome whose numbers are worked out in the comments. */
class ReplayMetricsTest {

    private static final Instant START = SimulationFixtures.START;

    private static ReplayedTicket ticket(int index, Priority priority, long arrivedMin, long assignedMin, long completedMin,
            String technician, boolean unskilled) {
        Instant arrived = START.plus(Duration.ofMinutes(arrivedMin));
        Instant completed = START.plus(Duration.ofMinutes(completedMin));
        Instant assigned = START.plus(Duration.ofMinutes(assignedMin));
        return new ReplayedTicket(index, priority, IncidentCategory.NETWORK, arrived, assigned, completed, technician,
                unskilled, Duration.between(assigned, completed));
    }

    private ReplayResult handMade() {
        // time to resolve (minutes): 20, 45, 300, 1500; waits: 0, 10, 0, 60
        List<ReplayedTicket> tickets = List.of(
                ticket(0, Priority.P1, 0, 0, 20, "A", false),          // target 30 min: met
                ticket(1, Priority.P1, 0, 10, 45, "A", false),         // 45 > 30: breached
                ticket(2, Priority.P3, 5, 5, 305, "B", true),          // 300 <= 480: met
                ticket(3, Priority.P4, 10, 70, 1510, "B", false));     // 1500 > 1440: breached
        Map<String, Integer> handled = new LinkedHashMap<>();
        handled.put("A", 2);
        handled.put("B", 2);
        handled.put("C", 0);
        Map<String, Double> busy = new LinkedHashMap<>();
        busy.put("A", 55.0);
        busy.put("B", 1795.0);
        busy.put("C", 0.0);
        return new ReplayResult("X", tickets, handled, busy, Map.of("A", 2, "B", 1, "C", 0), 4, START.plus(Duration.ofMinutes(1510)));
    }

    @Test
    @DisplayName("every metric of the hand-made outcome")
    void metricsOfAKnownOutcome() {
        Map<ComparisonMetric, Double> m = ReplayMetrics.of(handMade(), SimulationFixtures.targets());

        assertThat(m.get(ComparisonMetric.MEAN_TIME_TO_RESOLVE)).isCloseTo((20 + 45 + 300 + 1500) / 4.0, within(1e-9));
        assertThat(m.get(ComparisonMetric.P95_TIME_TO_RESOLVE)).as("nearest rank: ceil(0.95 x 4) = 4th value").isEqualTo(1500.0);
        assertThat(m.get(ComparisonMetric.SLA_BREACH_RATE)).isEqualTo(0.5);
        assertThat(m.get(ComparisonMetric.MEAN_WAIT)).isCloseTo((0 + 10 + 0 + 60) / 4.0, within(1e-9));
        assertThat(m.get(ComparisonMetric.MAX_QUEUE_LENGTH)).isEqualTo(4.0);
        assertThat(m.get(ComparisonMetric.UNSKILLED_SHARE)).isEqualTo(0.25);
        assertThat(m.get(ComparisonMetric.TICKETS_GINI)).as("handled 2, 2, 0").isCloseTo(1.0 / 3.0, within(1e-12));
        assertThat(m.get(ComparisonMetric.BUSY_TIME_GINI)).isCloseTo(GiniCoefficient.of(55, 1795, 0), within(1e-12));
        assertThat(m.keySet()).containsExactlyInAnyOrder(ComparisonMetric.values());
    }

    @Test
    @DisplayName("a ticket resolved exactly on its target is not a breach")
    void exactTargetIsNotABreach() {
        ReplayResult result = new ReplayResult("X", List.of(ticket(0, Priority.P1, 0, 0, 30, "A", false)),
                Map.of("A", 1), Map.of("A", 30.0), Map.of("A", 1), 0, START);

        assertThat(ReplayMetrics.of(result, SimulationFixtures.targets()).get(ComparisonMetric.SLA_BREACH_RATE)).isZero();
    }

    @Test
    @DisplayName("percentile by nearest rank")
    void percentile() {
        double[] oneToHundred = java.util.stream.IntStream.rangeClosed(1, 100).asDoubleStream().toArray();
        double[] oneToTwenty = java.util.stream.IntStream.rangeClosed(1, 20).asDoubleStream().toArray();

        assertThat(ReplayMetrics.percentile(oneToHundred, 0.95)).isEqualTo(95.0);
        assertThat(ReplayMetrics.percentile(oneToTwenty, 0.95)).isEqualTo(19.0);
        assertThat(ReplayMetrics.percentile(new double[] {42.0}, 0.95)).isEqualTo(42.0);
        assertThat(ReplayMetrics.percentile(new double[] {3, 1, 2}, 0.95)).as("unsorted input").isEqualTo(3.0);
    }

    @Test
    @DisplayName("an empty replay has no metrics, and a missing target is named")
    void refusesWhatItCannotMeasure() {
        ReplayResult empty = new ReplayResult("X", List.of(), Map.of(), Map.of(), Map.of(), 0, START);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ReplayMetrics.of(empty, SimulationFixtures.targets()))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ReplayMetrics.of(handMade(), Map.of()))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("no resolution target");
    }
}
