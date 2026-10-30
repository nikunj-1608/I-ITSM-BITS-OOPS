package com.iitsm.web.dto;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.time.Instant;

/**
 * Persona DTO for the administrator ticket inspection dialog. Extends {@link AdminIncidentView}
 * (unwrapped, so existing top-level JSON fields stay unchanged) with related-entity details that would
 * be too costly to resolve for every row of the incident list.
 */
public record AdminIncidentDetailView(
        @JsonUnwrapped AdminIncidentView incident,
        ReporterView reporter,
        ServiceView service,
        SupportTeamView supportTeam,
        String publicStatus,
        boolean needsTriage,
        Instant lastModifiedAt) {

    public record ReporterView(
            String id,
            String name,
            String email,
            String department,
            String designation) {}

    public record ServiceView(
            String id,
            String name,
            String criticalityTier,
            String criticalityLabel) {}

    public record SupportTeamView(
            String id,
            String name) {}
}
