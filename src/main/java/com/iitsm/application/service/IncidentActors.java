package com.iitsm.application.service;

import com.iitsm.domain.model.AuditRecord;
import com.iitsm.domain.model.Incident;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The stored actor ids a staff view shows a name for: who overrode the priority, who resolved the ticket and who acted
 * in the audit trail (ADR-037). The reporter and the assignee are looked up with the ticket already; note authors carry
 * their name from the day the note was written.
 */
public final class IncidentActors {

    private IncidentActors() {}

    public static Set<String> of(Incident incident, List<AuditRecord> auditRecords) {
        Objects.requireNonNull(incident, "incident cannot be null");
        Set<String> ids = new LinkedHashSet<>();
        if (incident.getPriorityDecision() != null && incident.getPriorityDecision().overriddenBy() != null) {
            ids.add(incident.getPriorityDecision().overriddenBy());
        }
        if (incident.getResolution() != null && incident.getResolution().resolvedBy() != null) {
            ids.add(incident.getResolution().resolvedBy());
        }
        if (auditRecords != null) {
            auditRecords.stream().map(AuditRecord::getActorId).filter(Objects::nonNull).forEach(ids::add);
        }
        return ids;
    }

    /** The ids of several tickets and their trails together, for one lookup per list. */
    public static Set<String> ofAll(List<Incident> incidents, java.util.function.Function<Incident, List<AuditRecord>> trails) {
        Set<String> ids = new LinkedHashSet<>();
        for (Incident incident : incidents) {
            ids.addAll(of(incident, trails.apply(incident)));
        }
        return ids;
    }
}
