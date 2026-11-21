package com.iitsm.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitsm.application.service.StrategyComparisonService;
import com.iitsm.application.service.StrategyComparisonService.ComparisonRun;
import com.iitsm.domain.model.Priority;
import com.iitsm.domain.model.SlaPolicy;
import com.iitsm.domain.repository.UserRepository;
import com.iitsm.domain.simulation.assignment.ComparisonMetric;
import com.iitsm.domain.simulation.assignment.ComparisonParameters;
import com.iitsm.domain.simulation.assignment.ComparisonReport;
import com.iitsm.domain.simulation.assignment.ReplicationStatistics;
import com.iitsm.domain.simulation.assignment.SimulationPool;
import com.iitsm.domain.simulation.assignment.StrategyComparison;
import com.iitsm.domain.simulation.assignment.StrategyReport;
import com.iitsm.domain.simulation.assignment.WorkTimeModel;
import com.iitsm.domain.strategy.assignment.AssignmentStrategy;
import com.iitsm.domain.strategy.assignment.AssignmentStrategyRegistry;
import com.iitsm.infrastructure.persistence.CompanySeeder;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * The offline strategy comparison on the seeded roster (ADR-044, Phase L3): the representative result table for the
 * report, how the result moves with the model's two softest assumptions (concurrency penalty, arrival rate), and the
 * time a request takes at the defaults and at the largest allowed request. Report-only: it asserts the invariants that
 * hold whatever the model says (every strategy and metric present, values in range) and a generous run-time ceiling,
 * never that a particular strategy wins. Runs on its own in-memory database, so the seed never touches the shared one.
 */
@SpringBootTest(properties = "spring.datasource.url="
        + "jdbc:h2:mem:strategycomparisonbenchmark;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL")
@ActiveProfiles("test")
@Tag("benchmark")
class StrategyComparisonBenchmarkTest {

    private static final String REPORT_FILE = "strategy-comparison.txt";
    private static final long GENEROUS_CEILING_MILLIS = 60_000L;
    private static final int WARM_RUNS = 3;

    @Autowired private StrategyComparisonService service;
    @Autowired private UserRepository userRepository;
    @Autowired private CompanySeeder companySeeder;

    @Test
    @DisplayName("Benchmark: the comparison table at the defaults, its sensitivity to the model, and the run time")
    void benchmark() throws IOException {
        if (userRepository.count() == 0) {
            companySeeder.seedCompanyData();
        }
        List<String> report = new ArrayList<>();
        report.add("Strategy comparison benchmark (ADR-044, Phase L3). Simulated results of a synthetic work-time model, not production data.");

        ComparisonRun cold = service.compare(null, null, null, null, null);
        List<Long> warm = new ArrayList<>();
        for (int i = 0; i < WARM_RUNS; i++) {
            warm.add(service.compare(null, null, null, null, null).runMillis());
        }
        mainTable(report, cold, warm);
        sensitivity(report);
        timeAtTheLimits(report);

        BenchmarkReport.write(REPORT_FILE, report);

        ComparisonReport defaults = cold.report();
        assertThat(defaults.strategies()).hasSize(5);
        assertThat(defaults.parameters().seed()).isEqualTo(42L);
        assertThat(cold.runMillis()).as("defaults, first run").isLessThan(GENEROUS_CEILING_MILLIS);
        assertThat(warm).allSatisfy(ms -> assertThat(ms).isLessThan(GENEROUS_CEILING_MILLIS));
        for (StrategyReport strategy : defaults.strategies()) {
            assertThat(strategy.summaries().get(ComparisonMetric.TICKETS_GINI).mean()).isBetween(0.0, 1.0);
            assertThat(strategy.summaries().get(ComparisonMetric.SLA_BREACH_RATE).mean()).isBetween(0.0, 1.0);
        }
    }

    private static void mainTable(List<String> out, ComparisonRun run, List<Long> warm) {
        ComparisonReport r = run.report();
        ComparisonParameters p = r.parameters();
        out.add("");
        out.add(String.format(Locale.ROOT, "== Defaults on the seeded roster: pool %s, %d technicians, seed %d, %d tickets x %d replications, %.1f tickets/hour",
                run.poolSource(), r.technicianCount(), p.seed(), p.ticketCount(), p.replications(), p.arrivalsPerHour()));
        out.add("Run time: first request " + run.runMillis() + " ms, then " + warm + " ms (strategies " + p.strategyNames() + ")");
        out.add("Mean over the replications, with the 95 % half width; * = the paired difference to " + r.referenceStrategy()
                + " is distinguishable from luck; the best mean of a metric is marked with <");
        for (ComparisonMetric metric : ComparisonMetric.values()) {
            out.add("");
            out.add(metric.label() + " [" + metric.unit() + "]");
            for (StrategyReport s : r.strategies()) {
                ReplicationStatistics.Summary summary = s.summaries().get(metric);
                ReplicationStatistics.PairedDifference diff = s.versusReference().get(metric);
                String vs = diff == null ? "reference" : String.format(Locale.ROOT, "%+.4f %s", diff.meanDifference(),
                        diff.distinguishable() ? "*" : "");
                out.add(String.format(Locale.ROOT, "  %-18s %10.4f +- %8.4f   vs ref %-16s %s", s.strategyName(),
                        summary.mean(), summary.ci95HalfWidth(), vs,
                        r.winners().get(metric).contains(s.strategyName()) ? "<" : ""));
            }
        }
        out.add("");
        out.add("Assumptions:");
        r.assumptions().forEach(a -> out.add("  - " + a));
    }

    /** The synthetic pool, so the model's assumptions can be varied; the default parameters otherwise. */
    private static void sensitivity(List<String> out) {
        out.add("");
        out.add("== Sensitivity on the synthetic pool (12 technicians, 500 tickets x 10 replications, seed 42)");
        out.add("Mean time to resolve in minutes / SLA breach rate, per strategy; the ranking is the point, not the numbers");
        Map<Priority, Duration> targets = new EnumMap<>(Priority.class);
        for (Priority priority : Priority.values()) {
            targets.put(priority, SlaPolicy.defaultFor(priority).getResolutionTarget());
        }
        for (double penalty : new double[] {0.0, WorkTimeModel.DEFAULT_CONCURRENCY_PENALTY, 0.6}) {
            for (double rate : new double[] {8.0, ComparisonParameters.DEFAULT_ARRIVALS_PER_HOUR, 25.0}) {
                List<AssignmentStrategy> strategies = new AssignmentStrategyRegistry().getAllStrategies();
                ComparisonParameters parameters = ComparisonParameters.of(42L, 500, 10, rate,
                        null, strategies.stream().map(AssignmentStrategy::getStrategyName).toList());
                ComparisonReport result = new StrategyComparison(WorkTimeModel.withConcurrencyPenalty(penalty))
                        .run(parameters, SimulationPool.syntheticDefault(), strategies, targets);
                StringBuilder line = new StringBuilder(String.format(Locale.ROOT, "  concurrency penalty %.2f, %4.1f tickets/h:", penalty, rate));
                for (StrategyReport s : result.strategies()) {
                    line.append(String.format(Locale.ROOT, "  %s %.0f/%.3f", s.strategyName(),
                            s.summaries().get(ComparisonMetric.MEAN_TIME_TO_RESOLVE).mean(),
                            s.summaries().get(ComparisonMetric.SLA_BREACH_RATE).mean()));
                }
                out.add(line.toString());
            }
        }
    }

    private void timeAtTheLimits(List<String> out) {
        out.add("");
        out.add("== Run time at the largest allowed requests (seeded roster, all five strategies)");
        for (int[] request : new int[][] {{500, 10}, {5000, 5}, {500, 50}, {5000, 1}}) {
            ComparisonRun run = service.compare(42L, request[0], request[1], null, null);
            out.add(String.format(Locale.ROOT, "  %4d tickets x %2d replications = %6d ticket replays x 5 strategies: %6d ms",
                    request[0], request[1], request[0] * request[1], run.runMillis()));
        }
    }
}
