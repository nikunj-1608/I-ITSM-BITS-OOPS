package com.iitsm.application.dto;

import com.iitsm.domain.model.AuditRecord;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.ItService;
import com.iitsm.domain.model.SupportTeam;
import com.iitsm.domain.model.User;
import com.iitsm.domain.strategy.assignment.AssignmentDecision;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Everything the admin ticket inspection view needs about one incident, gathered in a single read.
 * Related entities may be null when the incident references something that no longer exists
 * (for example an unassigned incident has no assignee).
 *
 * <p>{@code assignmentDecisions} (ADR-044) are the newest decisions explaining why the ticket got its technicians,
 * newest first; {@code actorNames} also holds the names of the technicians and actors those decisions mention.
 */
public record AdminIncidentDetail(
        Incident incident,
        User reporter,
        User assignee,
        ItService service,
        SupportTeam supportTeam,
        List<AuditRecord> auditRecords,
        Map<String, String> actorNames,
        List<AssignmentDecision> assignmentDecisions) {

    /** A detail without a name lookup: the views then show the stored actor text only. */
    public AdminIncidentDetail(
            Incident incident,
            User reporter,
            User assignee,
            ItService service,
            SupportTeam supportTeam,
            List<AuditRecord> auditRecords) {
        this(incident, reporter, assignee, service, supportTeam, auditRecords, Map.of(), List.of());
    }

    /** A detail without assignment decisions. */
    public AdminIncidentDetail(
            Incident incident,
            User reporter,
            User assignee,
            ItService service,
            SupportTeam supportTeam,
            List<AuditRecord> auditRecords,
            Map<String, String> actorNames) {
        this(incident, reporter, assignee, service, supportTeam, auditRecords, actorNames, List.of());
    }

    public AdminIncidentDetail {
        Objects.requireNonNull(incident, "incident cannot be null");
        auditRecords = (auditRecords != null) ? List.copyOf(auditRecords) : List.of();
        actorNames = (actorNames != null) ? Map.copyOf(actorNames) : Map.of();
        assignmentDecisions = (assignmentDecisions != null) ? List.copyOf(assignmentDecisions) : List.of();
    }

    /**
     * The incident has no modification timestamp of its own, so the latest audit entry is the best
     * available "last modified" signal; falls back to creation time when nothing was audited.
     */
    public Instant lastModifiedAt() {
        return auditRecords.stream()
                .map(AuditRecord::getTimestamp)
                .filter(Objects::nonNull)
                .max(Instant::compareTo)
                .orElse(incident.getCreatedAt());
    }
}
