package com.iitsm.domain.simulation.assignment;

import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.Priority;
import com.iitsm.domain.model.Skill;
import com.iitsm.domain.model.SlaPolicy;
import com.iitsm.domain.strategy.assignment.AssignmentStrategy;
import com.iitsm.domain.strategy.assignment.AssignmentStrategyRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pools, tickets and targets shared by the offline comparison tests (ADR-044, Phase L3). */
final class SimulationFixtures {

    static final Instant START = ComparisonParameters.DEFAULT_START;
    static final String TEAM = "TEAM-T";

    private SimulationFixtures() {
    }

    /** The default resolution targets: P1 30 min, P2 2 h, P3 8 h, P4 24 h. */
    static Map<Priority, Duration> targets() {
        Map<Priority, Duration> targets = new EnumMap<>(Priority.class);
        for (Priority priority : Priority.values()) {
            targets.put(priority, SlaPolicy.defaultFor(priority).getResolutionTarget());
        }
        return targets;
    }

    /** One technician of {@link #TEAM} who owns NETWORK, with the given skill category. */
    static SimulationPool singleTechnicianPool(int capacity, int years, IncidentCategory skill) {
        return new SimulationPool(
                List.of(new TechnicianProfile("T-1", TEAM, years, capacity, Set.of(new Skill("Skill " + skill, skill)))),
                Map.of(IncidentCategory.NETWORK, TEAM));
    }

    /** A hand-made ticket with noise 1, arriving {@code arrivalMillis} after the start. */
    static SyntheticTicket ticket(int index, long arrivalMillis, Priority priority, IncidentCategory category) {
        return new SyntheticTicket(index, Duration.ofMillis(arrivalMillis), priority, category,
                "Hand made replay ticket", "A hand made ticket for the offline replay tests " + index + ".", 1.0);
    }

    static List<PreparedTicket> prepared(List<SyntheticTicket> tickets) {
        return new TicketStreamGenerator(WorkTimeModel.defaults()).prepare(tickets, START);
    }

    /** The five registered strategies, in the registry's own (unspecified) order. */
    static List<AssignmentStrategy> registeredStrategies() {
        return new AssignmentStrategyRegistry().getAllStrategies();
    }

    static List<String> names(List<AssignmentStrategy> strategies) {
        return strategies.stream().map(AssignmentStrategy::getStrategyName).toList();
    }

    static ComparisonParameters parameters(long seed, int tickets, int replications, double rate, List<AssignmentStrategy> strategies) {
        return ComparisonParameters.of(seed, tickets, replications, rate, names(strategies), names(strategies));
    }
}
