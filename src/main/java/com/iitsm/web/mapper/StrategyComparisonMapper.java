package com.iitsm.web.mapper;

import com.iitsm.application.service.StrategyComparisonService.ComparisonRun;
import com.iitsm.domain.simulation.assignment.ComparisonMetric;
import com.iitsm.domain.simulation.assignment.ComparisonParameters;
import com.iitsm.domain.simulation.assignment.ComparisonReport;
import com.iitsm.domain.simulation.assignment.ReplicationStatistics;
import com.iitsm.domain.simulation.assignment.StrategyReport;
import com.iitsm.web.dto.StrategyComparisonResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// OOP (Encapsulation of projection): StrategyComparisonMapper builds persona-specific views, so hiding fields is enforced by the type, not by the UI.
/**
 * Pattern: Mapper. Turns the report of an offline strategy comparison into its administrator DTO (ADR-044, Phase L3):
 * metric enums become stable string keys, in a fixed order.
 */
public final class StrategyComparisonMapper {

    /** Said with every result. */
    public static final String NOTICE = "Simulated results from a synthetic work-time model, not production data. "
            + "Only the comparison between strategies is meaningful; the absolute times are assumptions.";

    private StrategyComparisonMapper() {
    }

    public static StrategyComparisonResponse toResponse(ComparisonRun run) {
        ComparisonReport report = run.report();
        ComparisonParameters p = report.parameters();
        List<StrategyComparisonResponse.MetricInfo> metrics = java.util.Arrays.stream(ComparisonMetric.values())
                .map(m -> new StrategyComparisonResponse.MetricInfo(m.key(), m.label(), m.unit())).toList();
        Map<String, List<String>> winners = new LinkedHashMap<>();
        for (ComparisonMetric metric : ComparisonMetric.values()) {
            winners.put(metric.key(), report.winners().get(metric));
        }
        return new StrategyComparisonResponse(
                new StrategyComparisonResponse.Parameters(p.seed(), p.ticketCount(), p.replications(),
                        p.arrivalsPerHour(), p.strategyNames(), p.startInstant()),
                report.referenceStrategy(), run.poolSource(), report.technicianCount(), metrics,
                report.strategies().stream().map(StrategyComparisonMapper::toView).toList(), winners,
                report.assumptions(), run.runMillis(), NOTICE);
    }

    private static StrategyComparisonResponse.StrategyView toView(StrategyReport strategy) {
        Map<String, StrategyComparisonResponse.MetricView> byMetric = new LinkedHashMap<>();
        for (ComparisonMetric metric : ComparisonMetric.values()) {
            ReplicationStatistics.Summary summary = strategy.summaries().get(metric);
            ReplicationStatistics.PairedDifference difference = strategy.versusReference().get(metric);
            byMetric.put(metric.key(), new StrategyComparisonResponse.MetricView(
                    summary.mean(), summary.stdDev(), summary.ci95HalfWidth(), strategy.perReplication().get(metric),
                    difference == null ? null : difference.meanDifference(),
                    difference == null ? null : difference.ci95HalfWidth(),
                    difference == null ? null : difference.distinguishable()));
        }
        return new StrategyComparisonResponse.StrategyView(strategy.strategyName(), strategy.isReference(), byMetric);
    }
}
