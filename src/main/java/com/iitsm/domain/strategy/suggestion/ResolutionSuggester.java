package com.iitsm.domain.strategy.suggestion;

import java.util.Collection;
import java.util.List;

/**
 * Pattern: Strategy. Ranks past fixes for a ticket (ADR-040, ADR-041). A different ranking (another similarity, a learned
 * model) is a new implementation registered as the bean, with no change to the service or the web layer.
 */
public interface ResolutionSuggester {

    /**
     * The best candidates for the query, best first, already cut to the policy's maximum; an empty list when none is
     * good enough. Never returns the ticket itself or its linked duplicates. {@code votes} may adjust the order; it must
     * never make a candidate qualify that is not similar enough on its own.
     */
    List<ResolutionSuggestion> suggest(SuggestionQuery query, Collection<SuggestionCandidate> candidates, VoteTallies votes);

    /** The ranking without votes. */
    default List<ResolutionSuggestion> suggest(SuggestionQuery query, Collection<SuggestionCandidate> candidates) {
        return suggest(query, candidates, VoteTallies.none());
    }
}
