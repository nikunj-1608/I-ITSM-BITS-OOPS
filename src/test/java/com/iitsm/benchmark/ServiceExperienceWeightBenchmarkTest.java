package com.iitsm.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitsm.domain.model.Priority;
import com.iitsm.domain.model.SlaPolicy;
import com.iitsm.domain.simulation.assignment.ComparisonMetric;
import com.iitsm.domain.simulation.assignment.ComparisonParameters;
import com.iitsm.domain.simulation.assignment.ComparisonReport;
import com.iitsm.domain.simulation.assignment.ReplicationStatistics;
import com.iitsm.domain.simulation.assignment.SimulationPool;
import com.iitsm.domain.simulation.assignment.StrategyComparison;
import com.iitsm.domain.simulation.assignment.StrategyReport;
import com.iitsm.domain.simulation.assignment.WorkTimeModel;
import com.iitsm.domain.strategy.assignment.AssignmentStrategy;
import com.iitsm.domain.strategy.assignment.ServiceExperienceFactor;
import com.iitsm.domain.strategy.assignment.ServiceExperienceSource;
import com.iitsm.domain.strategy.assignment.WeightedCompositeStrategy;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Chooses the weight of the service-experience factor with the offline simulation (ADR-044, Phase L4). The weighted
 * composite is replayed with the factor at several weights, on the same seeded streams (common random numbers), under three
 * assumptions about how much a technician's familiarity with an IT service speeds them up (none, 15 %, 30 %) and three
 * arrival rates; the plain composite (weight 0) is the reference of the paired differences.
 *
 * <p>Two pools: the invented pool (each technician has one skill, so "has resolved this service before" also tells who
 * has the right skill, which the first sweep showed: the factor helped even with no familiarity speed-up) and a
 * <b>control pool</b> where every technician has all the skills of their team, like the seeded roster, so experience
 * cannot stand in for skill there.
 *
 * <p>The selection rule (final form, see ADR-044; it was changed twice after seeing numbers, see the ADR): the LARGEST weight
 * that, on the control pool, (a) shortens the mean time to resolve distinguishably when familiarity really speeds
 * technicians up (30 %), (b) changes it by less than 1 % when familiarity brings no speed-up (the factor must do no harm
 * where it cannot help), and (c) at 30 % familiarity raises the Gini coefficient of tickets handled by at most 0.05 and
 * the Gini coefficient of busy time by at most 0.10 (the fairness it may cost); the factor stays off (weight 0) when no
 * weight passes. Report-only: the assertions only check that the report is complete.
 */
@Tag("benchmark")
class ServiceExperienceWeightBenchmarkTest {

    private static final String REPORT_FILE = "service-experience-weights.txt";
    private static final double[] WEIGHTS = {0.0, 0.05, 0.10, 0.15, 0.20, 0.30, 0.40, 0.50};
    private static final double[] SPEEDUPS = {0.0, 0.15, 0.30};
    private static final double[] RATES = {8.0, ComparisonParameters.DEFAULT_ARRIVALS_PER_HOUR, 25.0};
    private static final int TICKETS = 1000;
    private static final int REPLICATIONS = 10;
    private static final int SERVICES_PER_CATEGORY = 2;
    private static final int FULL_TICKETS = ServiceExperienceFactor.DEFAULT_FULL_SCORE_TICKETS;
    private static final long SEED = 42L;

    private static String nameOf(double weight) {
        return weight == 0.0 ? WeightedCompositeStrategy.STRATEGY_NAME
                : String.format(Locale.ROOT, "%s_W%02d", WeightedCompositeStrategy.STRATEGY_NAME, Math.round(weight * 100));
    }

    private static List<AssignmentStrategy> variants() {
        List<AssignmentStrategy> variants = new ArrayList<>();
        for (double weight : WEIGHTS) {
            variants.add(WeightedCompositeStrategy.withServiceExperience(ServiceExperienceSource.none(), weight, FULL_TICKETS)
                    .named(nameOf(weight)));
        }
        return variants;
    }

    /** The invented pool with every technician given all the skills of their team (like the seeded roster). */
    private static SimulationPool controlPool() {
        SimulationPool base = SimulationPool.syntheticDefault();
        Map<String, java.util.Set<com.iitsm.domain.model.Skill>> byTeam = new java.util.HashMap<>();
        base.technicians().forEach(t -> byTeam.computeIfAbsent(t.supportTeamId(), k -> new java.util.HashSet<>()).addAll(t.skills()));
        List<com.iitsm.domain.simulation.assignment.TechnicianProfile> profiles = base.technicians().stream()
                .map(t -> new com.iitsm.domain.simulation.assignment.TechnicianProfile(t.id(), t.supportTeamId(), t.experienceYears(),
                        t.maxCapacity(), byTeam.get(t.supportTeamId()))).toList();
        return new SimulationPool(profiles, base.owningTeamByCategory());
    }

    private static ComparisonReport run(double speedup, double rate) {
        return run(speedup, rate, SimulationPool.syntheticDefault());
    }

    private static ComparisonReport run(double speedup, double rate, SimulationPool pool) {
        List<AssignmentStrategy> variants = variants();
        Map<Priority, Duration> targets = new EnumMap<>(Priority.class);
        for (Priority priority : Priority.values()) {
            targets.put(priority, SlaPolicy.defaultFor(priority).getResolutionTarget());
        }
        ComparisonParameters parameters = ComparisonParameters.of(SEED, TICKETS, REPLICATIONS, rate, null,
                variants.stream().map(AssignmentStrategy::getStrategyName).toList());
        WorkTimeModel model = speedup == 0.0 ? WorkTimeModel.defaults() : WorkTimeModel.withServiceFamiliarity(speedup, FULL_TICKETS);
        // Without familiarity the replay exposes no experience, so the factor would see nothing; the services are still
        // drawn so the streams are the same. The no-benefit case needs the experience visible: it uses a model with
        // the smallest possible speed-up (0.001 %), which changes no time noticeably.
        if (speedup == 0.0) {
            model = WorkTimeModel.withServiceFamiliarity(0.00001, FULL_TICKETS);
        }
        return new StrategyComparison(model, SERVICES_PER_CATEGORY).run(parameters, pool, variants, targets);
    }

    private static StrategyReport report(ComparisonReport r, double weight) {
        return r.strategies().stream().filter(s -> s.strategyName().equals(nameOf(weight))).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("Benchmark: the weight of the service-experience factor under three familiarity assumptions and three loads")
    void sweep() throws IOException {
        List<String> out = new ArrayList<>();
        out.add("Service-experience weight sweep (ADR-044, Phase L4). Simulated results of a synthetic model, not production data.");
        out.add(String.format(Locale.ROOT, "Synthetic pool of 12 technicians, %d tickets x %d replications, seed %d, %d IT services per category,"
                + " factor full score at %d resolved tickets; reference = weight 0 (the historic composite).", TICKETS, REPLICATIONS, SEED,
                SERVICES_PER_CATEGORY, FULL_TICKETS));
        out.add("Columns: mean time to resolve (min) with the paired difference to weight 0 (* = larger than chance), SLA breach rate, Gini of tickets handled,"
                + " Gini of busy time, share of tickets to a technician without the skill.");
        Map<String, ComparisonReport> byCase = new java.util.LinkedHashMap<>();
        for (double speedup : SPEEDUPS) {
            for (double rate : RATES) {
                ComparisonReport result = run(speedup, rate);
                byCase.put(speedup + "/" + rate, result);
                out.add("");
                out.add(String.format(Locale.ROOT, "== familiarity speed-up %.0f %%, %.0f tickets per hour", speedup * 100, rate));
                for (double weight : WEIGHTS) {
                    StrategyReport s = report(result, weight);
                    ReplicationStatistics.Summary ttr = s.summaries().get(ComparisonMetric.MEAN_TIME_TO_RESOLVE);
                    ReplicationStatistics.PairedDifference diff = s.versusReference().get(ComparisonMetric.MEAN_TIME_TO_RESOLVE);
                    out.add(String.format(Locale.ROOT, "  weight %.2f  mean %7.2f +-%5.2f  vs w0 %-14s  breach %.4f  giniTickets %.3f  giniBusy %.3f  unskilled %.3f",
                            weight, ttr.mean(), ttr.ci95HalfWidth(),
                            diff == null ? "reference" : String.format(Locale.ROOT, "%+.2f %s", diff.meanDifference(), diff.distinguishable() ? "*" : ""),
                            s.summaries().get(ComparisonMetric.SLA_BREACH_RATE).mean(),
                            s.summaries().get(ComparisonMetric.TICKETS_GINI).mean(),
                            s.summaries().get(ComparisonMetric.BUSY_TIME_GINI).mean(),
                            s.summaries().get(ComparisonMetric.UNSKILLED_SHARE).mean()));
                }
            }
        }
        SimulationPool control = controlPool();
        Map<String, ComparisonReport> controlCases = new java.util.LinkedHashMap<>();
        for (double speedup : SPEEDUPS) {
            ComparisonReport result = run(speedup, ComparisonParameters.DEFAULT_ARRIVALS_PER_HOUR, control);
            controlCases.put(String.valueOf(speedup), result);
            out.add("");
            out.add(String.format(Locale.ROOT, "== CONTROL POOL (every technician has all the skills of the team), familiarity speed-up %.0f %%, %.0f tickets per hour",
                    speedup * 100, ComparisonParameters.DEFAULT_ARRIVALS_PER_HOUR));
            for (double weight : WEIGHTS) {
                StrategyReport s = report(result, weight);
                ReplicationStatistics.Summary ttr = s.summaries().get(ComparisonMetric.MEAN_TIME_TO_RESOLVE);
                ReplicationStatistics.PairedDifference diff = s.versusReference().get(ComparisonMetric.MEAN_TIME_TO_RESOLVE);
                out.add(String.format(Locale.ROOT, "  weight %.2f  mean %7.2f +-%5.2f  vs w0 %-14s  breach %.4f  giniTickets %.3f  giniBusy %.3f  unskilled %.3f",
                        weight, ttr.mean(), ttr.ci95HalfWidth(),
                        diff == null ? "reference" : String.format(Locale.ROOT, "%+.2f %s", diff.meanDifference(), diff.distinguishable() ? "*" : ""),
                        s.summaries().get(ComparisonMetric.SLA_BREACH_RATE).mean(),
                        s.summaries().get(ComparisonMetric.TICKETS_GINI).mean(),
                        s.summaries().get(ComparisonMetric.BUSY_TIME_GINI).mean(),
                        s.summaries().get(ComparisonMetric.UNSKILLED_SHARE).mean()));
            }
        }
        out.add("");
        out.add(selection(byCase, controlCases));
        BenchmarkReport.write(REPORT_FILE, out);
        assertThat(byCase).hasSize(SPEEDUPS.length * RATES.length);
    }

    /** Applies the final selection rule of the class description and says which weight it picks, and why. */
    private static String selection(Map<String, ComparisonReport> byCase, Map<String, ComparisonReport> controlCases) {
        ComparisonReport real = controlCases.get(String.valueOf(0.30));
        ComparisonReport none = controlCases.get(String.valueOf(0.0));
        double baseReal = report(real, 0.0).summaries().get(ComparisonMetric.MEAN_TIME_TO_RESOLVE).mean();
        double baseNone = report(none, 0.0).summaries().get(ComparisonMetric.MEAN_TIME_TO_RESOLVE).mean();
        double ticketsGini = report(real, 0.0).summaries().get(ComparisonMetric.TICKETS_GINI).mean();
        double busyGini = report(real, 0.0).summaries().get(ComparisonMetric.BUSY_TIME_GINI).mean();
        StringBuilder text = new StringBuilder("Selection on the control pool (largest weight passing a, b and c):");
        double chosen = 0.0;
        for (double weight : WEIGHTS) {
            if (weight == 0.0) {
                continue;
            }
            StrategyReport withReal = report(real, weight);
            ReplicationStatistics.PairedDifference diff = withReal.versusReference().get(ComparisonMetric.MEAN_TIME_TO_RESOLVE);
            double gain = baseReal - withReal.summaries().get(ComparisonMetric.MEAN_TIME_TO_RESOLVE).mean();
            boolean a = diff.distinguishable() && diff.meanDifference() < 0;
            double none1 = report(none, weight).summaries().get(ComparisonMetric.MEAN_TIME_TO_RESOLVE).mean() / baseNone - 1.0;
            boolean b = Math.abs(none1) < 0.01;
            double riseTickets = withReal.summaries().get(ComparisonMetric.TICKETS_GINI).mean() - ticketsGini;
            double riseBusy = withReal.summaries().get(ComparisonMetric.BUSY_TIME_GINI).mean() - busyGini;
            boolean c = riseTickets <= 0.05 && riseBusy <= 0.10;
            boolean passes = a && b && c;
            if (passes) {
                chosen = weight;
            }
            text.append(String.format(Locale.ROOT, "%n  weight %.2f: (a) gain at 30 %% %.2f min %s; (b) change without benefit %+.2f %% %s; (c) gini rise tickets %+.3f busy %+.3f %s -> %s",
                    weight, gain, a ? "ok" : "FAILS", 100 * none1, b ? "ok" : "FAILS", riseTickets, riseBusy, c ? "ok" : "FAILS",
                    passes ? "PASSES" : "fails"));
        }
        text.append(String.format(Locale.ROOT, "%nCHOSEN WEIGHT: %.2f", chosen));
        return text.toString();
    }
}
