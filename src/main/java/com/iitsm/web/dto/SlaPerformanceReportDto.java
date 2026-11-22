package com.iitsm.web.dto;

import java.util.Map;

// OOP (Immutability): SlaPerformanceReportDto is a record, so it is a read-only data carrier with no setters.
public record SlaPerformanceReportDto(
        long totalEvaluated,
        long metCount,
        long breachedCount,
        long warningCount,
        long atRiskCount,
        double overallCompliancePercentage,
        Map<String, PrioritySlaMetric> priorityCompliance) {

    public record PrioritySlaMetric(
            long metCount,
            long breachedCount,
            double compliancePercentage) {}
}

