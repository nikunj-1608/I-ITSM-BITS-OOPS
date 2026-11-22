package com.iitsm.domain.strategy.suggestion;

import com.iitsm.domain.exception.ValidationException;
import java.time.Instant;
import java.util.Objects;

/**
 * OOP: Immutable value object. One technician's verdict on a suggested fix for one ticket (ADR-041). The key is (voter,
 * source, target ticket): a second vote by the same voter on the same pair replaces the first ({@link #recast}). The
 * voter is stored by user id (ADR-037).
 */
public record SuggestionVote(String voterId, SuggestionSourceKey source, String targetIncidentId, boolean worked, Instant votedAt) {

    public SuggestionVote {
        if (voterId == null || voterId.isBlank()) {
            throw new IllegalArgumentException("voterId cannot be blank");
        }
        Objects.requireNonNull(source, "source cannot be null");
        if (targetIncidentId == null || targetIncidentId.isBlank()) {
            throw new IllegalArgumentException("targetIncidentId cannot be blank");
        }
        Objects.requireNonNull(votedAt, "votedAt cannot be null");
    }

    /**
     * A first vote. A ticket is never suggested for itself, so voting it as its own fix is a client mistake (HTTP 400).
     */
    public static SuggestionVote cast(String voterId, SuggestionSourceKey source, String targetIncidentId, boolean worked, Instant now) {
        if (source.type() == SuggestionSourceType.RESOLVED_TICKET && source.id().equals(targetIncidentId)) {
            throw new ValidationException("sourceId", "A ticket cannot be voted as a fix for itself");
        }
        return new SuggestionVote(voterId, source, targetIncidentId, worked, now);
    }

    /** The same voter changes their verdict: same key, new verdict and time. */
    public SuggestionVote recast(boolean newVerdict, Instant now) {
        return new SuggestionVote(voterId, source, targetIncidentId, newVerdict, now);
    }
}
