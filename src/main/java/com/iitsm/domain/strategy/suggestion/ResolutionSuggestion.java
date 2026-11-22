package com.iitsm.domain.strategy.suggestion;

import java.util.List;
import java.util.Objects;

/**
 * OOP: Immutable value object. One suggested fix with the evidence for it (ADR-040, ADR-041): the similarity score, the
 * ranking score (similarity times the bounded vote factor), the vote counts, and the domain terms the ticket and the past
 * fix share, like the explainable priority breakdown.
 */
public record ResolutionSuggestion(
        SuggestionCandidate source, double similarity, double score, VoteTally votes, List<String> matchedTerms) {

    public ResolutionSuggestion {
        Objects.requireNonNull(source, "source cannot be null");
        if (similarity < 0.0 || similarity > 1.0) {
            throw new IllegalArgumentException("similarity must be in [0, 1]: " + similarity);
        }
        if (score < 0.0 || Double.isNaN(score)) {
            throw new IllegalArgumentException("score cannot be negative: " + score);
        }
        votes = votes == null ? VoteTally.none() : votes;
        matchedTerms = matchedTerms == null ? List.of() : List.copyOf(matchedTerms);
    }

    /** A suggestion without votes: its score is its similarity. */
    public ResolutionSuggestion(SuggestionCandidate source, double similarity, List<String> matchedTerms) {
        this(source, similarity, similarity, VoteTally.none(), matchedTerms);
    }
}
