package com.iitsm.web.dto;

import java.util.List;

// OOP (Immutability): WorkloadReportDto is a record, so it is a read-only data carrier with no setters.
public record WorkloadReportDto(
        List<TechnicianLoadItem> technicianLoads,
        List<TeamLoadItem> teamLoads,
        double averageLoadRatio) {

    public record TechnicianLoadItem(
            String technicianId,
            String name,
            String teamId,
            int activeIncidents,
            int maxCapacity,
            double loadRatio,
            int resolvedIncidents) {}

    public record TeamLoadItem(
            String teamId,
            String teamName,
            int memberCount,
            int totalActiveIncidents,
            int totalCapacity,
            double teamLoadRatio) {}
}

