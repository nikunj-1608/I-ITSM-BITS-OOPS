package com.iitsm.domain.strategy.suggestion;

import com.iitsm.domain.model.IncidentCategory;

/**
 * OOP: Immutable value object holding the tunable rules of resolution suggestions (ADR-040), all set in
 * {@code application.yml} under {@code iitsm.suggestions.*}. A wrong value stops the application at start-up.
 *
 * @param maxResults          most suggestions returned (default 3)
 * @param minSimilarity       similarity in (0, 1] a candidate must reach; below it nothing is suggested
 * @param minSharedTerms      domain terms the ticket and the candidate must share (0 switches the content guard off)
 * @param sameCategoryOnly    a candidate must have the ticket's category (the default hard filter)
 * @param candidateWindow     newest resolved tickets compared per request (bounds the cost, default 200)
 * @param maxExplanationTerms most shared terms listed as the explanation of a score
 * @param articleWindow       newest knowledge-base articles compared per request (ADR-042, default 200)
 */
public record SuggestionPolicy(
        int maxResults,
        double minSimilarity,
        int minSharedTerms,
        boolean sameCategoryOnly,
        int candidateWindow,
        int maxExplanationTerms,
        int articleWindow) {

    public SuggestionPolicy {
        if (maxResults < 1) {
            throw new IllegalArgumentException("Suggestion max-results must be at least 1: " + maxResults);
        }
        if (minSimilarity <= 0.0 || minSimilarity > 1.0) {
            throw new IllegalArgumentException("Suggestion min-similarity must be in (0, 1]: " + minSimilarity);
        }
        if (minSharedTerms < 0) {
            throw new IllegalArgumentException("Suggestion min-shared-terms cannot be negative: " + minSharedTerms);
        }
        if (candidateWindow < 1) {
            throw new IllegalArgumentException("Suggestion candidate-window must be at least 1: " + candidateWindow);
        }
        if (maxExplanationTerms < 0) {
            throw new IllegalArgumentException("Suggestion explanation-terms cannot be negative: " + maxExplanationTerms);
        }
        if (articleWindow < 1) {
            throw new IllegalArgumentException("Suggestion article-window must be at least 1: " + articleWindow);
        }
    }

    /** A policy whose article window is the same size as the ticket window. */
    public SuggestionPolicy(int maxResults, double minSimilarity, int minSharedTerms, boolean sameCategoryOnly,
            int candidateWindow, int maxExplanationTerms) {
        this(maxResults, minSimilarity, minSharedTerms, sameCategoryOnly, candidateWindow, maxExplanationTerms, candidateWindow);
    }

    /** False when the category filter is on and the ticket has no category yet: there is nothing to compare with. */
    public boolean canSuggestFor(SuggestionQuery query) {
        return !sameCategoryOnly || query.category() != null;
    }

    /** The category the candidate window is limited to, or null for every category. */
    public IncidentCategory windowCategory(SuggestionQuery query) {
        return sameCategoryOnly ? query.category() : null;
    }

    /** The hard category filter: with it on, only a candidate of the ticket's own category is admitted. */
    public boolean admitsCategory(SuggestionQuery query, SuggestionCandidate candidate) {
        return !sameCategoryOnly || (query.category() != null && query.category() == candidate.category());
    }

    /** A candidate is suggested only when it is similar enough and shares enough domain terms. */
    public boolean accepts(double similarity, int sharedTerms) {
        return similarity >= minSimilarity && sharedTerms >= minSharedTerms;
    }
}
