package com.iitsm.domain.strategy.suggestion;

import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.Priority;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * OOP: Immutable value object. One past fix that may be suggested for a new ticket (ADR-040): the technical content of a
 * resolved ticket (or, from Phase K3, a knowledge-base article).
 *
 * <p>{@code problemText} (the ticket's description) is used for matching only and is never shown in a suggestion, so a
 * suggestion carries no words the reporter wrote beyond the ticket title. Reporter, assignee and notes are not part of
 * a candidate at all.
 *
 * @param priority         the source ticket's current priority, used for its staff display name; null for an article
 * @param originIncidentId the ticket the fix comes from: the ticket itself, or the ticket an article was promoted from
 *                         (ADR-042); a query excludes candidates by this id
 */
public record SuggestionCandidate(
        SuggestionSourceType sourceType,
        String sourceId,
        IncidentCategory category,
        Priority priority,
        String title,
        String problemText,
        String rootCause,
        String stepsTaken,
        String preventiveAction,
        String originIncidentId) {

    public SuggestionCandidate {
        Objects.requireNonNull(sourceType, "sourceType cannot be null");
        if (sourceId == null || sourceId.isBlank()) {
            throw new IllegalArgumentException("sourceId cannot be blank");
        }
        originIncidentId = originIncidentId == null || originIncidentId.isBlank() ? sourceId : originIncidentId.trim();
        title = textOrEmpty(title);
        problemText = textOrEmpty(problemText);
        rootCause = textOrEmpty(rootCause);
        stepsTaken = textOrEmpty(stepsTaken);
        preventiveAction = textOrEmpty(preventiveAction);
    }

    /** A candidate that is its own origin: a resolved ticket. */
    public SuggestionCandidate(SuggestionSourceType sourceType, String sourceId, IncidentCategory category, Priority priority,
            String title, String problemText, String rootCause, String stepsTaken, String preventiveAction) {
        this(sourceType, sourceId, category, priority, title, problemText, rootCause, stepsTaken, preventiveAction, sourceId);
    }

    /** The source's identity, the key of its votes. */
    public SuggestionSourceKey key() {
        return new SuggestionSourceKey(sourceType, sourceId);
    }

    /** The text compared with the new ticket: title, problem, root cause and steps (the preventive action is not). */
    public String matchingText() {
        return Stream.of(title, problemText, rootCause, stepsTaken)
                .filter(part -> !part.isEmpty())
                .collect(Collectors.joining(" "));
    }

    private static String textOrEmpty(String text) {
        return text == null ? "" : text.trim();
    }
}
