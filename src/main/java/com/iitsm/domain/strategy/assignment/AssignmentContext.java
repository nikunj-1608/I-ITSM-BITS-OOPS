package com.iitsm.domain.strategy.assignment;

import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.Priority;
import com.iitsm.domain.strategy.nlp.TextNormalizer;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * OOP: Immutable value object. What the assignment factors need to know about one ticket, read once per ranking
 * (ADR-044): the ticket, its lower-cased title and description, their normalised tokens and, since Phase L4, how much
 * experience each candidate has with the ticket's IT service.
 */
public record AssignmentContext(Incident incident, String fullText, List<String> tokens, ServiceExperience serviceExperience) {

    public AssignmentContext {
        Objects.requireNonNull(incident, "incident cannot be null");
        fullText = Objects.requireNonNullElse(fullText, "");
        tokens = List.copyOf(Objects.requireNonNull(tokens, "tokens cannot be null"));
        serviceExperience = Objects.requireNonNullElse(serviceExperience, ServiceExperience.none());
    }

    /** A context without service experience. */
    public AssignmentContext(Incident incident, String fullText, List<String> tokens) {
        this(incident, fullText, tokens, ServiceExperience.none());
    }

    /** The same ticket with the candidates' service experience. */
    public AssignmentContext withServiceExperience(ServiceExperience experience) {
        return new AssignmentContext(incident, fullText, tokens, experience);
    }

    /** Reads the ticket's text once: title and description, lower case, and their tokens. */
    public static AssignmentContext of(Incident incident) {
        Objects.requireNonNull(incident, "incident cannot be null");
        String title = incident.getTitle() != null ? incident.getTitle() : "";
        String description = incident.getDescription() != null ? incident.getDescription() : "";
        String fullText = (title + " " + description).toLowerCase(Locale.ROOT);
        return new AssignmentContext(incident, fullText, TextNormalizer.tokenize(fullText));
    }

    /** The ticket's category; null for an unclassified ticket. */
    public IncidentCategory category() {
        return incident.getCategory();
    }

    /** The ticket's priority, or {@code fallback} when it has none yet. */
    public Priority priorityOr(Priority fallback) {
        return incident.getPriorityDecision() != null ? incident.getPriorityDecision().priority() : fallback;
    }
}
