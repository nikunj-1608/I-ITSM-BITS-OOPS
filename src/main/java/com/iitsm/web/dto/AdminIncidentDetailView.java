package com.iitsm.web.dto;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.time.Instant;
import java.util.List;

// OOP (Immutability): AdminIncidentDetailView is a record, so it is a read-only data carrier with no setters.
/**
 * Persona DTO for the administrator ticket inspection dialog. Extends {@link AdminIncidentView}
 * (unwrapped, so existing top-level JSON fields stay unchanged) with related-entity details that would
 * be too costly to resolve for every row of the incident list.
 *
 * <p>{@code assignmentDecisions} (ADR-044) explain why the ticket got its technicians: for a strategy's choice every
 * ranked candidate with each factor's raw score, weight and contribution. Administrators only: candidates' scores
 * reveal colleagues' load and experience, so no technician or employee view carries them.
 */
public record AdminIncidentDetailView(
        @JsonUnwrapped AdminIncidentView incident,
        ReporterView reporter,
        ServiceView service,
        SupportTeamView supportTeam,
        String publicStatus,
        boolean needsTriage,
        Instant lastModifiedAt,
        List<AssignmentDecisionView> assignmentDecisions) {

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

    /** One assignment decision, newest first in the list (ADR-044). */
    public record AssignmentDecisionView(
            String poolKind,
            String poolLabel,
            String strategyName,
            String chosenTechnicianId,
            String chosenTechnicianName,
            String actorId,
            String actorName,
            String relatedIncidentId,
            Instant decidedAt,
            String summary,
            int candidateCount,
            List<CandidateView> candidates) {}

    /** One ranked candidate of a strategy's decision. */
    public record CandidateView(
            String technicianId,
            String technicianName,
            int rank,
            boolean chosen,
            double total,
            List<FactorView> factors) {}

    /** One factor of a candidate's score: contribution = raw score x weight; a negative weight is a penalty. */
    public record FactorView(
            String key,
            double rawScore,
            double weight,
            double contribution,
            String detail) {}
}
