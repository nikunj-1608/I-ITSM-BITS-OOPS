package com.iitsm.domain.simulation.assignment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.Priority;
import com.iitsm.domain.model.Technician;
import com.iitsm.domain.strategy.assignment.AssignmentRecommendation;
import com.iitsm.domain.strategy.assignment.AssignmentStrategy;
import com.iitsm.domain.strategy.assignment.LoadBasedStrategy;
import com.iitsm.domain.strategy.assignment.WeightedCompositeStrategy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The discrete-event engine of the offline comparison (ADR-044, Phase L3). */
class StrategyReplayTest {

    private static final Instant START = SimulationFixtures.START;
    private static final double MS_PER_MIN = 60_000.0;

    private static final IncidentCategory NET = IncidentCategory.NETWORK;

    private ReplayResult replay(SimulationPool pool, AssignmentStrategy strategy, SyntheticTicket... tickets) {
        return new StrategyReplay(pool, WorkTimeModel.defaults())
                .run(strategy, SimulationFixtures.prepared(List.of(tickets)), START);
    }

    @Test
    @DisplayName("waiting tickets are served most urgent first: P1 before P3 before P4, whatever their arrival order")
    void waitingQueueIsUrgentFirst() {
        SimulationPool pool = SimulationFixtures.singleTechnicianPool(1, 5, NET);

        ReplayResult result = replay(pool, new LoadBasedStrategy(),
                SimulationFixtures.ticket(0, 0, Priority.P3, NET),
                SimulationFixtures.ticket(1, 60_000, Priority.P4, NET),
                SimulationFixtures.ticket(2, 120_000, Priority.P3, NET),
                SimulationFixtures.ticket(3, 180_000, Priority.P1, NET));

        List<ReplayedTicket> t = result.tickets();
        assertThat(t.get(0).waitMinutes()).isZero();
        assertThat(t.get(3).assignedAt()).as("the P1 goes first").isEqualTo(t.get(0).completedAt());
        assertThat(t.get(2).assignedAt()).as("then the P3").isEqualTo(t.get(3).completedAt());
        assertThat(t.get(1).assignedAt()).as("the P4 last").isEqualTo(t.get(2).completedAt());
        assertThat(result.maxQueueLength()).isEqualTo(3);
    }

    @Test
    @DisplayName("a technician works on several tickets at once up to the capacity, the rest wait; the peak never exceeds it")
    void concurrencyUpToCapacity() {
        SimulationPool pool = SimulationFixtures.singleTechnicianPool(3, 5, NET);
        SyntheticTicket[] tickets = new SyntheticTicket[5];
        for (int i = 0; i < 5; i++) {
            tickets[i] = SimulationFixtures.ticket(i, 0, Priority.P3, NET);
        }

        ReplayResult result = replay(pool, new LoadBasedStrategy(), tickets);

        assertThat(result.peakConcurrent().get("T-1")).isEqualTo(3);
        assertThat(result.maxQueueLength()).isEqualTo(2);
        assertThat(result.ticketsHandled().get("T-1")).isEqualTo(5);
        List<ReplayedTicket> t = result.tickets();
        assertThat(t.subList(0, 3)).allSatisfy(x -> assertThat(x.waitMinutes()).isZero());
        // the 4th starts when the first slot frees: the earliest completion
        Instant firstFree = t.subList(0, 3).stream().map(ReplayedTicket::completedAt).min(Comparator.naturalOrder()).orElseThrow();
        assertThat(t.get(3).assignedAt()).isEqualTo(firstFree);
        // the second and third ticket started on a busier technician, so they took longer than the first
        assertThat(t.get(1).serviceTime()).isGreaterThan(t.get(0).serviceTime());
        assertThat(t.get(2).serviceTime()).isGreaterThan(t.get(1).serviceTime());
    }

    @Test
    @DisplayName("at the same instant a completion comes before an arrival: the freed slot serves the arriving ticket at once")
    void completionBeforeArrivalAtTheSameInstant() {
        SimulationPool pool = SimulationFixtures.singleTechnicianPool(1, 5, NET);
        SyntheticTicket first = SimulationFixtures.ticket(0, 0, Priority.P3, NET);
        Duration service = WorkTimeModel.defaults().serviceTime(first, pool.technicians().get(0), 0);

        ReplayResult result = replay(pool, new LoadBasedStrategy(), first,
                SimulationFixtures.ticket(1, service.toMillis(), Priority.P3, NET));

        assertThat(result.tickets().get(1).waitMinutes()).isZero();
        assertThat(result.maxQueueLength()).isZero();
    }

    @Test
    @DisplayName("a technician without the category skill is flagged and works 1.6 times as long")
    void unskilledAssignmentIsFlagged() {
        SimulationPool pool = SimulationFixtures.singleTechnicianPool(2, 5, IncidentCategory.SECURITY);
        SyntheticTicket ticket = SimulationFixtures.ticket(0, 0, Priority.P3, NET);

        ReplayedTicket outcome = replay(pool, new LoadBasedStrategy(), ticket).tickets().get(0);

        assertThat(outcome.unskilled()).isTrue();
        double skilledMinutes = WorkTimeModel.defaults().serviceTime(ticket,
                SimulationFixtures.singleTechnicianPool(2, 5, NET).technicians().get(0), 0).toMillis() / MS_PER_MIN;
        assertThat(outcome.serviceTime().toMillis() / MS_PER_MIN).isCloseTo(skilledMinutes * 1.6, within(1e-6));
    }

    @Test
    @DisplayName("a ticket nobody can ever serve fails loudly instead of being dropped")
    void unservableTicketFails() {
        SimulationPool pool = SimulationFixtures.singleTechnicianPool(2, 5, NET);

        assertThatThrownBy(() -> replay(pool, new LoadBasedStrategy(),
                SimulationFixtures.ticket(0, 0, Priority.P3, IncidentCategory.DATABASE)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("can never be served");
    }

    @Test
    @DisplayName("a strategy that picks a technician it was not offered is refused")
    void strategyMustPickFromTheCandidates() {
        AssignmentStrategy rogue = new AssignmentStrategy() {
            @Override
            public AssignmentRecommendation recommend(Incident incident, List<Technician> candidates) {
                Technician other = new TechnicianProfile("OTHER", "TEAM-X", 3, 3, java.util.Set.of()).toTechnician();
                return new AssignmentRecommendation(other, 0.5, "ROGUE", "not offered");
            }

            @Override
            public String getStrategyName() {
                return "ROGUE";
            }

            @Override
            public String getDescription() {
                return "picks someone nobody offered";
            }
        };

        assertThatThrownBy(() -> replay(SimulationFixtures.singleTechnicianPool(2, 5, NET), rogue,
                SimulationFixtures.ticket(0, 0, Priority.P3, NET)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("not offered");
    }

    @Test
    @DisplayName("tickets must be numbered in arrival order and there must be at least one")
    void ticketListIsChecked() {
        SimulationPool pool = SimulationFixtures.singleTechnicianPool(2, 5, NET);
        StrategyReplay engine = new StrategyReplay(pool, WorkTimeModel.defaults());

        assertThatThrownBy(() -> engine.run(new LoadBasedStrategy(), List.of(), START))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> engine.run(new LoadBasedStrategy(), SimulationFixtures.prepared(List.of(
                SimulationFixtures.ticket(1, 0, Priority.P3, NET))), START)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the same inputs give an equal result (the replay has no hidden state or randomness)")
    void replayIsDeterministic() {
        SimulationPool pool = SimulationPool.syntheticDefault();
        List<PreparedTicket> tickets = prepared(21, 250, 25, pool);
        StrategyReplay engine = new StrategyReplay(pool, WorkTimeModel.defaults());

        assertThat(engine.run(new WeightedCompositeStrategy(), tickets, START))
                .isEqualTo(engine.run(new WeightedCompositeStrategy(), tickets, START));
    }

    @Test
    @DisplayName("invariants for every strategy on a busy stream: everyone served, no technician over capacity at any time, totals add up")
    void invariantsForEveryStrategy() {
        SimulationPool pool = SimulationPool.syntheticDefault();
        List<PreparedTicket> tickets = prepared(77, 400, 40, pool);
        Map<String, Integer> capacity = new java.util.HashMap<>();
        pool.technicians().forEach(p -> capacity.put(p.id(), p.maxCapacity()));

        for (AssignmentStrategy strategy : SimulationFixtures.registeredStrategies()) {
            ReplayResult result = new StrategyReplay(pool, WorkTimeModel.defaults()).run(strategy.freshInstance(), tickets, START);
            String name = strategy.getStrategyName();

            assertThat(result.tickets()).as(name).hasSize(400).doesNotContainNull();
            assertThat(result.ticketsHandled().values().stream().mapToInt(Integer::intValue).sum()).as(name).isEqualTo(400);
            assertThat(result.maxQueueLength()).as("%s queued under load", name).isPositive();
            double busy = result.busyMinutes().values().stream().mapToDouble(Double::doubleValue).sum();
            double served = result.tickets().stream().mapToDouble(t -> t.serviceTime().toMillis() / MS_PER_MIN).sum();
            assertThat(busy).as(name).isCloseTo(served, within(1e-6));
            for (ReplayedTicket t : result.tickets()) {
                assertThat(t.assignedAt()).as(name).isAfterOrEqualTo(t.arrivedAt());
                assertThat(t.completedAt()).as(name).isEqualTo(t.assignedAt().plus(t.serviceTime()));
            }
            assertThat(result.finishedAt()).isEqualTo(result.tickets().stream().map(ReplayedTicket::completedAt)
                    .max(Comparator.naturalOrder()).orElseThrow());
            assertNeverOverCapacity(result, capacity, name);
        }
    }

    /** Sweeps every technician's intervals: at no virtual time more tickets than the capacity (ends before starts). */
    private static void assertNeverOverCapacity(ReplayResult result, Map<String, Integer> capacity, String name) {
        for (Map.Entry<String, Integer> entry : capacity.entrySet()) {
            List<long[]> points = new ArrayList<>();
            for (ReplayedTicket t : result.tickets()) {
                if (t.technicianId().equals(entry.getKey())) {
                    points.add(new long[] {t.assignedAt().toEpochMilli(), 1});
                    points.add(new long[] {t.completedAt().toEpochMilli(), -1});
                }
            }
            points.sort(Comparator.<long[]>comparingLong(p -> p[0]).thenComparingLong(p -> p[1]));
            int concurrent = 0;
            for (long[] point : points) {
                concurrent += (int) point[1];
                assertThat(concurrent).as("%s %s", name, entry.getKey()).isLessThanOrEqualTo(entry.getValue());
            }
            assertThat(result.peakConcurrent().get(entry.getKey())).isLessThanOrEqualTo(entry.getValue());
        }
    }

    private static List<PreparedTicket> prepared(long seed, int count, double rate, SimulationPool pool) {
        TicketStreamGenerator generator = new TicketStreamGenerator(WorkTimeModel.defaults());
        return generator.prepare(generator.generate(new SplittableRandom(seed), count, rate, pool.servableCategories()), START);
    }
}
