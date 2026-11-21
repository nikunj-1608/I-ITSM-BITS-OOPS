package com.iitsm.domain.model;

import com.iitsm.domain.exception.NotResolvedException;
import com.iitsm.domain.exception.UnauthorizedOperationException;
import java.time.Instant;
import java.util.Objects;

/**
 * OOP: Immutable value-style entity (ADR-042). A fix promoted from one resolved ticket into the small knowledge base, so
 * it stays suggestable after the ticket has left the suggestion window. It holds technical content only: title, category,
 * problem, root cause, steps and preventive action, all with the reporter's name, e-mail and department removed. No
 * setters; {@link #promote} enforces who may promote and from which ticket.
 */
public final class KnowledgeArticle {

    /** An article's id is this prefix and the source ticket id ({@code KB-NET-2026-000012}). */
    public static final String ID_PREFIX = "KB-";
    public static final int MAX_TITLE_LENGTH = 255;

    private final String id;
    private final String title;
    private final IncidentCategory category;
    private final String problem;
    private final String rootCause;
    private final String stepsTaken;
    private final String preventiveAction;
    private final String sourceIncidentId;
    private final String createdBy;
    private final Instant createdAt;

    @SuppressWarnings("java:S107") // one argument per stored column; a builder would add a mutable step
    public KnowledgeArticle(String id, String title, IncidentCategory category, String problem, String rootCause,
            String stepsTaken, String preventiveAction, String sourceIncidentId, String createdBy, Instant createdAt) {
        this.id = required(id, "id");
        this.title = required(title, "title");
        if (this.title.length() > MAX_TITLE_LENGTH) {
            throw new IllegalArgumentException("title longer than " + MAX_TITLE_LENGTH + " characters");
        }
        this.category = Objects.requireNonNull(category, "category cannot be null");
        this.problem = required(problem, "problem");
        this.rootCause = required(rootCause, "rootCause");
        this.stepsTaken = required(stepsTaken, "stepsTaken");
        this.preventiveAction = required(preventiveAction, "preventiveAction");
        this.sourceIncidentId = required(sourceIncidentId, "sourceIncidentId");
        this.createdBy = required(createdBy, "createdBy");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt cannot be null");
    }

    /**
     * Promotes the ticket's resolution. The ticket must have a confirmed resolution (RESOLVED, VERIFIED or CLOSED, a
     * resolution report and a category), else {@link NotResolvedException} (409); the actor must be allowed
     * ({@link User#canPromoteResolution}), else {@link UnauthorizedOperationException} (403). The title is the requested
     * one or, when blank, the ticket's title. Every copied text goes through the redactor.
     */
    public static KnowledgeArticle promote(Incident source, User actor, String requestedTitle,
            PersonalDataRedactor redactor, Instant now) {
        Objects.requireNonNull(source, "source cannot be null");
        Objects.requireNonNull(actor, "actor cannot be null");
        Objects.requireNonNull(redactor, "redactor cannot be null");
        Resolution resolution = source.getResolution();
        if (!source.getStatus().hasConfirmedResolution() || resolution == null) {
            throw new NotResolvedException(source.getId(), "it has no confirmed resolution (status " + source.getStatus() + ")");
        }
        if (source.getCategory() == null) {
            throw new NotResolvedException(source.getId(), "it has no category");
        }
        if (!actor.canPromoteResolution(resolution)) {
            throw new UnauthorizedOperationException(
                    "Only the technician who resolved the ticket, a team lead or an administrator may promote it");
        }
        String title = redactor.redact(requestedTitle == null || requestedTitle.isBlank() ? source.getTitle() : requestedTitle.trim());
        return new KnowledgeArticle(ID_PREFIX + source.getId(), titleOrFallback(title, source.getId()), source.getCategory(),
                textOrFallback(redactor.redact(source.getDescription()), resolution.rootCause()),
                redactor.redact(resolution.rootCause()), redactor.redact(resolution.stepsTaken()),
                redactor.redact(resolution.preventiveAction()), source.getId(), actor.getId(), now);
    }

    /** A title that was nothing but personal data becomes a neutral one. */
    private static String titleOrFallback(String title, String sourceId) {
        String cleaned = title == null ? "" : title.replace(PersonalDataRedactor.REPLACEMENT, "").strip();
        String result = cleaned.isEmpty() ? "Fix from ticket " + sourceId : title.strip();
        return result.length() > MAX_TITLE_LENGTH ? result.substring(0, MAX_TITLE_LENGTH) : result;
    }

    private static String textOrFallback(String text, String fallback) {
        return text == null || text.isBlank() ? fallback : text;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " cannot be blank");
        }
        return value.strip();
    }

    public String getId() { return id; }

    public String getTitle() { return title; }

    public IncidentCategory getCategory() { return category; }

    public String getProblem() { return problem; }

    public String getRootCause() { return rootCause; }

    public String getStepsTaken() { return stepsTaken; }

    public String getPreventiveAction() { return preventiveAction; }

    public String getSourceIncidentId() { return sourceIncidentId; }

    public String getCreatedBy() { return createdBy; }

    public Instant getCreatedAt() { return createdAt; }
}
