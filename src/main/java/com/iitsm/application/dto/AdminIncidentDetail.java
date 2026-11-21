package com.iitsm.application.dto;

import com.iitsm.domain.model.AuditRecord;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.ItService;
import com.iitsm.domain.model.SupportTeam;
import com.iitsm.domain.model.User;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Everything the admin ticket inspection view needs about one incident, gathered in a single read.
 * Related entities may be null when the incident references something that no longer exists
 * (for example an unassigned incident has no assignee).
 */
public record AdminIncidentDetail(
        Incident incident,
        User reporter,
        User assignee,
        ItService service,
        SupportTeam supportTeam,
        List<AuditRecord> auditRecords) {

    public AdminIncidentDetail {
        Objects.requireNonNull(incident, "incident cannot be null");
        auditRecords = (auditRecords != null) ? List.copyOf(auditRecords) : List.of();
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
