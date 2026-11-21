package com.iitsm.domain.strategy.suggestion;

import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.IncidentCategory;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * OOP: Immutable value object. What suggestions are looked up for (ADR-040): the open ticket's text and category, and
 * the tickets that must never be suggested for it (the ticket itself and every ticket it is linked to as a duplicate,
 * in either direction).
 */
public record SuggestionQuery(String incidentId, IncidentCategory category, String text, Set<String> excludedTicketIds) {

    public SuggestionQuery {
        Objects.requireNonNull(incidentId, "incidentId cannot be null");
        text = text == null ? "" : text.trim();
        excludedTicketIds = excludedTicketIds == null ? Set.of() : Set.copyOf(excludedTicketIds);
    }

    /** The query for a ticket: its title and description, its category, itself and its duplicate links excluded. */
    public static SuggestionQuery forIncident(Incident incident) {
        Objects.requireNonNull(incident, "incident cannot be null");
        Set<String> excluded = new HashSet<>(incident.getLinkedIncidentIds());
        excluded.add(incident.getId());
        String text = Objects.toString(incident.getTitle(), "") + " " + Objects.toString(incident.getDescription(), "");
        return new SuggestionQuery(incident.getId(), incident.getCategory(), text, excluded);
    }

    /**
     * True when the candidate comes from the ticket itself or one of its linked duplicates: the ticket, or an article
     * promoted from it (ADR-042), so a reopened ticket is never offered its own fix.
     */
    public boolean excludes(SuggestionCandidate candidate) {
        return excludedTicketIds.contains(candidate.originIncidentId());
    }
}
