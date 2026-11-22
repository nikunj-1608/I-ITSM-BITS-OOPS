package com.iitsm.domain.simulation.assignment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.Technician;
import com.iitsm.domain.strategy.assignment.AssignmentRecommendation;
import com.iitsm.domain.strategy.assignment.AssignmentStrategy;
import com.iitsm.domain.strategy.assignment.LoadBasedStrategy;
import com.iitsm.domain.strategy.assignment.RoundRobinStrategy;
import com.iitsm.domain.strategy.assignment.SkillBasedStrategy;
import com.iitsm.domain.strategy.assignment.WeightedCompositeStrategy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The offline experiment (ADR-044, Phase L3). No test asserts that a particular strategy wins: that depends on the work
 * time model. They assert what must hold whatever the model says: ranges, consistency, determinism, common random numbers.
 */
class StrategyComparisonTest {

    private final SimulationPool pool = SimulationPool.syntheticDefault();
    private final StrategyComparison comparison = new StrategyComparison(WorkTimeModel.defaults());

    private ComparisonReport run(long seed, int tickets, int replications, double rate, List<AssignmentStrategy> strategies) {
        return comparison.run(SimulationFixtures.parameters(seed, tickets, replications, rate, strategies), pool,
                strategies, SimulationFixtures.targets());
    }

    @Test
    @DisplayName("all five strategies: one report each (alphabetical), every metric once per replication, values in range")
    void reportShapeAndRanges() {
        List<AssignmentStrategy> strategies = SimulationFixtures.registeredStrategies();
        ComparisonReport report = run(42, 200, 4, 25, strategies);

        assertThat(report.strategies()).extracting(StrategyReport::strategyName)
                .containsExactly("EXPERIENCE_BASED", "LOAD_BASED", "ROUND_ROBIN", "SKILL_BASED", "WEIGHTED_COMPOSITE");
        assertThat(report.technicianCount()).isEqualTo(12);
        assertThat(report.assumptions()).hasSizeGreaterThanOrEqualTo(6);
        for (StrategyReport strategy : report.strategies()) {
            for (ComparisonMetric metric : ComparisonMetric.values()) {
                assertThat(strategy.perReplication().get(metric)).as("%s %s", strategy.strategyName(), metric)
                        .hasSize(4).allSatisfy(v -> assertThat(v).isFinite().isGreaterThanOrEqualTo(0.0));
                assertThat(strategy.summaries().get(metric).mean()).isFinite();
            }
            assertRange(strategy, ComparisonMetric.SLA_BREACH_RATE);
            assertRange(strategy, ComparisonMetric.UNSKILLED_SHARE);
            for (ComparisonMetric gini : List.of(ComparisonMetric.TICKETS_GINI, ComparisonMetric.BUSY_TIME_GINI)) {
                assertThat(strategy.perReplication().get(gini)).allSatisfy(v -> assertThat(v).isBetween(0.0, 1.0).isLessThan(1.0));
            }
            assertThat(strategy.perReplication().get(ComparisonMetric.MAX_QUEUE_LENGTH))
                    .allSatisfy(v -> assertThat(v).isLessThanOrEqualTo(200.0));
        }
    }

    private static void assertRange(StrategyReport strategy, ComparisonMetric metric) {
        assertThat(strategy.perReplication().get(metric)).allSatisfy(v -> assertThat(v).isBetween(0.0, 1.0));
    }

    @Test
    @DisplayName("the reference is WEIGHTED_COMPOSITE; only the other strategies carry a paired difference")
    void referenceStrategy() {
        ComparisonReport report = run(1, 100, 3, 20, SimulationFixtures.registeredStrategies());

        assertThat(report.referenceStrategy()).isEqualTo("WEIGHTED_COMPOSITE");
        for (StrategyReport strategy : report.strategies()) {
            boolean reference = strategy.strategyName().equals("WEIGHTED_COMPOSITE");
            assertThat(strategy.isReference()).isEqualTo(reference);
            assertThat(strategy.versusReference().size()).isEqualTo(reference ? 0 : ComparisonMetric.values().length);
        }
    }

    @Test
    @DisplayName("without WEIGHTED_COMPOSITE the first strategy (alphabetical) is the reference; one strategy has no differences")
    void referenceWithoutTheDefault() {
        List<AssignmentStrategy> two = List.of(new RoundRobinStrategy(), new LoadBasedStrategy());
        ComparisonReport report = run(1, 80, 2, 20, two);
        assertThat(report.referenceStrategy()).isEqualTo("LOAD_BASED");

        ComparisonReport single = run(1, 80, 2, 20, List.of(new SkillBasedStrategy()));
        assertThat(single.referenceStrategy()).isEqualTo("SKILL_BASED");
        assertThat(single.strategies()).singleElement().satisfies(s -> assertThat(s.versusReference()).isEmpty());
        assertThat(single.winners().get(ComparisonMetric.MEAN_WAIT)).containsExactly("SKILL_BASED");
    }

    @Test
    @DisplayName("the same seed gives an equal report (byte-identical text); another seed changes it")
    void determinism() {
        List<AssignmentStrategy> strategies = SimulationFixtures.registeredStrategies();
        ComparisonReport first = run(42, 150, 3, 20, strategies);
        ComparisonReport again = run(42, 150, 3, 20, SimulationFixtures.registeredStrategies());
        ComparisonReport other = run(43, 150, 3, 20, strategies);

        assertThat(again).isEqualTo(first);
        assertThat(again.toString()).isEqualTo(first.toString());
        assertThat(other).isNotEqualTo(first);
        assertThat(other.toString()).isNotEqualTo(first.toString());
    }

    @Test
    @DisplayName("replication r is the same whatever the number of replications (split generators)")
    void replicationsAreIndependentOfTheirNumber() {
        List<AssignmentStrategy> strategies = List.of(new WeightedCompositeStrategy());
        ComparisonReport three = run(5, 100, 3, 20, strategies);
        ComparisonReport five = run(5, 100, 5, 20, strategies);

        assertThat(five.strategies().get(0).perReplication().get(ComparisonMetric.MEAN_TIME_TO_RESOLVE).subList(0, 3))
                .isEqualTo(three.strategies().get(0).perReplication().get(ComparisonMetric.MEAN_TIME_TO_RESOLVE));
    }

    /** A strategy that records every ticket it is asked about and otherwise behaves like {@code delegate}. */
    private static final class Recording implements AssignmentStrategy {
        private final String name;
        private final AssignmentStrategy delegate;
        private final List<String> seen = new ArrayList<>();

        Recording(String name, AssignmentStrategy delegate) {
            this.name = name;
            this.delegate = delegate;
        }

        @Override
        public AssignmentRecommendation recommend(Incident incident, List<Technician> candidates) {
            seen.add(incident.getId() + "|" + incident.getTitle() + "|" + incident.getCategory() + "|"
                    + incident.getPriorityDecision().priority() + "|" + incident.getCreatedAt());
            return delegate.recommend(incident, candidates);
        }

        @Override
        public String getStrategyName() {
            return name;
        }

        @Override
        public String getDescription() {
            return "records what it is asked";
        }
    }

    @Test
    @DisplayName("common random numbers: every strategy is asked about exactly the same tickets in every replication")
    void everyStrategySeesTheSameStream() {
        Recording a = new Recording("REC_A", new WeightedCompositeStrategy());
        Recording b = new Recording("REC_B", new LoadBasedStrategy());
        Recording c = new Recording("REC_C", new RoundRobinStrategy());

        run(42, 120, 3, 25, List.of(a, b, c));

        assertThat(a.seen).hasSize(360);
        List<String> sortedA = new ArrayList<>(a.seen);
        List<String> sortedB = new ArrayList<>(b.seen);
        List<String> sortedC = new ArrayList<>(c.seen);
        Collections.sort(sortedA);
        Collections.sort(sortedB);
        Collections.sort(sortedC);
        assertThat(sortedB).isEqualTo(sortedA);
        assertThat(sortedC).isEqualTo(sortedA);
        assertThat(sortedA.stream().distinct().count()).as("three different streams, not one").isGreaterThan(120);
    }

    @Test
    @DisplayName("a strategy with state starts every replay from a fresh instance (the supplied instance never advances)")
    void statefulStrategyIsNotAdvanced() {
        RoundRobinStrategy live = new RoundRobinStrategy();
        var candidates = SimulationPool.syntheticDefault().technicians().stream().limit(3).map(TechnicianProfile::toTechnician).toList();
        var ticket = AssignmentGridAccessBridge.ticket();
        String before = live.rank(ticket, candidates).get(0).technicianId();

        run(7, 100, 2, 20, List.of(live));

        assertThat(live.rank(ticket, candidates).get(0).technicianId()).isEqualTo(before);
    }

    @Test
    @DisplayName("the winner of a metric is the lowest mean; ties name every tied strategy")
    void winnersAreConsistent() {
        ComparisonReport report = run(9, 200, 3, 25, SimulationFixtures.registeredStrategies());

        for (ComparisonMetric metric : ComparisonMetric.values()) {
            double best = report.strategies().stream().mapToDouble(s -> s.summaries().get(metric).mean()).min().orElseThrow();
            List<String> winners = report.winners().get(metric);
            assertThat(winners).as("%s", metric).isNotEmpty();
            for (StrategyReport strategy : report.strategies()) {
                double mean = strategy.summaries().get(metric).mean();
                assertThat(winners.contains(strategy.strategyName())).as("%s %s", metric, strategy.strategyName())
                        .isEqualTo(mean <= best + 1e-9);
            }
        }
    }

    @Test
    @DisplayName("one replication has no confidence interval and no distinguishable difference")
    void singleReplication() {
        ComparisonReport report = run(3, 100, 1, 20, SimulationFixtures.registeredStrategies());

        for (StrategyReport strategy : report.strategies()) {
            strategy.summaries().values().forEach(s -> {
                assertThat(s.stdDev()).isZero();
                assertThat(s.ci95HalfWidth()).isZero();
            });
            strategy.versusReference().values().forEach(d -> assertThat(d.distinguishable()).isFalse());
        }
    }

    @Test
    @DisplayName("the strategies handed in must be the ones the parameters name")
    void strategiesMustMatchTheParameters() {
        ComparisonParameters parameters = SimulationFixtures.parameters(1, 50, 1, 20, List.of(new LoadBasedStrategy()));

        assertThatThrownBy(() -> comparison.run(parameters, pool, List.of(new SkillBasedStrategy()), SimulationFixtures.targets()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Open/Closed: a new strategy needs no change in the experiment and shows up in the report")
    void customStrategyIsComparedToo() {
        AssignmentStrategy lastCandidate = new AssignmentStrategy() {
            @Override
            public AssignmentRecommendation recommend(Incident incident, List<Technician> candidates) {
                return new AssignmentRecommendation(candidates.get(candidates.size() - 1), 0.5, "CUSTOM_LAST", "last");
            }

            @Override
            public String getStrategyName() {
                return "CUSTOM_LAST";
            }

            @Override
            public String getDescription() {
                return "always the last candidate";
            }
        };
        List<AssignmentStrategy> strategies = new ArrayList<>(SimulationFixtures.registeredStrategies());
        strategies.add(lastCandidate);

        ComparisonReport report = run(11, 100, 2, 20, strategies);

        assertThat(report.strategies()).extracting(StrategyReport::strategyName).contains("CUSTOM_LAST").hasSize(6);
        assertThat(report.strategies().stream().filter(s -> s.strategyName().equals("CUSTOM_LAST")).findFirst().orElseThrow()
                .versusReference()).hasSize(ComparisonMetric.values().length);
    }
}
