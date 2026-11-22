package com.iitsm.domain.simulation.assignment;

import com.iitsm.domain.model.Priority;
import com.iitsm.domain.strategy.assignment.AssignmentStrategy;
import com.iitsm.domain.strategy.assignment.WeightedCompositeStrategy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.SplittableRandom;

/**
 * Pattern: Template of an experiment (ADR-044, Phase L3). Replays the same seeded ticket stream through every strategy,
 * {@code replications} times, and reports per strategy and metric the mean, standard deviation and 95 % interval, the
 * paired difference against the reference strategy and the winner of every metric.
 *
 * <p>Common random numbers: each replication draws its stream once (from {@link SplittableRandom#split()} of the seed,
 * so replication {@code r} is the same whatever the other replications are) and replays it through each strategy's
 * {@link AssignmentStrategy#freshInstance() fresh instance}; the differences between strategies therefore come from the
 * strategies, not from luck, and the same seed gives an equal report. Nothing outside the arguments is read or written:
 * no repository, no clock, no event, no live strategy state.
 */
public final class StrategyComparison {

    private static final double TIE_TOLERANCE = 1e-9;

    private final WorkTimeModel model;
    private final int servicesPerCategory;

    public StrategyComparison(WorkTimeModel model) {
        this(model, 0);
    }

    /**
     * An experiment whose tickets belong to {@code servicesPerCategory} IT services per category (0 = one shared service,
     * the default of the endpoint). Services matter only with a model that has service familiarity (ADR-044, Phase L4).
     */
    public StrategyComparison(WorkTimeModel model, int servicesPerCategory) {
        this.model = Objects.requireNonNull(model, "model cannot be null");
        if (servicesPerCategory < 0) {
            throw new IllegalArgumentException("servicesPerCategory must not be negative");
        }
        this.servicesPerCategory = servicesPerCategory;
    }

    /**
     * Runs the experiment.
     *
     * @param strategies the strategies to compare; their upper-case names must be exactly {@code parameters.strategyNames()}
     * @param resolutionTargets the resolution target of every priority, for the SLA breach rate
     */
    public ComparisonReport run(ComparisonParameters parameters, SimulationPool pool,
            List<AssignmentStrategy> strategies, Map<Priority, Duration> resolutionTargets) {
        Objects.requireNonNull(parameters, "parameters cannot be null");
        List<AssignmentStrategy> ordered = orderedAndChecked(parameters, strategies);
        Map<String, Map<ComparisonMetric, List<Double>>> values = new LinkedHashMap<>();
        ordered.forEach(strategy -> values.put(nameOf(strategy), emptyValues()));

        TicketStreamGenerator generator = new TicketStreamGenerator(model);
        StrategyReplay replay = new StrategyReplay(pool, model);
        SplittableRandom root = new SplittableRandom(parameters.seed());
        for (int r = 0; r < parameters.replications(); r++) {
            SplittableRandom replicationRandom = root.split();
            List<SyntheticTicket> stream = generator.generate(replicationRandom, parameters.ticketCount(),
                    parameters.arrivalsPerHour(), pool.servableCategories());
            if (servicesPerCategory > 0) {
                // services come from a generator split off after the base stream was drawn, so the base stream is unchanged
                stream = generator.withServices(stream, replicationRandom.split(), servicesPerCategory);
            }
            List<PreparedTicket> prepared = generator.prepare(stream, parameters.startInstant());
            for (AssignmentStrategy strategy : ordered) {
                ReplayResult result = replay.run(strategy.freshInstance(), prepared, parameters.startInstant());
                ReplayMetrics.of(result, resolutionTargets).forEach(
                        (metric, value) -> values.get(nameOf(strategy)).get(metric).add(value));
            }
        }
        return report(parameters, pool, values);
    }

    private ComparisonReport report(ComparisonParameters parameters, SimulationPool pool,
            Map<String, Map<ComparisonMetric, List<Double>>> values) {
        String reference = values.containsKey(WeightedCompositeStrategy.STRATEGY_NAME)
                ? WeightedCompositeStrategy.STRATEGY_NAME : values.keySet().iterator().next();
        List<StrategyReport> reports = new ArrayList<>();
        for (Map.Entry<String, Map<ComparisonMetric, List<Double>>> entry : values.entrySet()) {
            reports.add(strategyReport(entry.getKey(), entry.getValue(), reference, values.get(reference)));
        }
        List<String> assumptions = new ArrayList<>(TicketStreamGenerator.assumptions());
        assumptions.addAll(model.assumptions());
        return new ComparisonReport(parameters, reference, pool.technicians().size(), reports, winners(reports), assumptions);
    }

    private static StrategyReport strategyReport(String name, Map<ComparisonMetric, List<Double>> own,
            String reference, Map<ComparisonMetric, List<Double>> referenceValues) {
        Map<ComparisonMetric, ReplicationStatistics.Summary> summaries = new EnumMap<>(ComparisonMetric.class);
        Map<ComparisonMetric, ReplicationStatistics.PairedDifference> differences = new EnumMap<>(ComparisonMetric.class);
        for (ComparisonMetric metric : ComparisonMetric.values()) {
            summaries.put(metric, ReplicationStatistics.summarize(own.get(metric)));
            if (!name.equals(reference)) {
                differences.put(metric, ReplicationStatistics.pairedDifference(own.get(metric), referenceValues.get(metric)));
            }
        }
        return new StrategyReport(name, own, summaries, differences);
    }

    private static Map<ComparisonMetric, List<String>> winners(List<StrategyReport> reports) {
        Map<ComparisonMetric, List<String>> winners = new EnumMap<>(ComparisonMetric.class);
        for (ComparisonMetric metric : ComparisonMetric.values()) {
            double best = reports.stream().mapToDouble(r -> r.summaries().get(metric).mean()).min().orElseThrow();
            winners.put(metric, reports.stream()
                    .filter(r -> r.summaries().get(metric).mean() <= best + TIE_TOLERANCE)
                    .map(StrategyReport::strategyName).toList());
        }
        return winners;
    }

    private static List<AssignmentStrategy> orderedAndChecked(ComparisonParameters parameters, List<AssignmentStrategy> strategies) {
        List<AssignmentStrategy> ordered = new ArrayList<>(Objects.requireNonNull(strategies, "strategies cannot be null"));
        ordered.sort(Comparator.comparing(StrategyComparison::nameOf));
        List<String> names = ordered.stream().map(StrategyComparison::nameOf).toList();
        if (!names.equals(parameters.strategyNames())) {
            throw new IllegalArgumentException("the strategies " + names + " do not match the parameters "
                    + parameters.strategyNames());
        }
        return ordered;
    }

    private static Map<ComparisonMetric, List<Double>> emptyValues() {
        Map<ComparisonMetric, List<Double>> map = new EnumMap<>(ComparisonMetric.class);
        for (ComparisonMetric metric : ComparisonMetric.values()) {
            map.put(metric, new ArrayList<>());
        }
        return map;
    }

    private static String nameOf(AssignmentStrategy strategy) {
        return strategy.getStrategyName().toUpperCase(Locale.ROOT);
    }
}
