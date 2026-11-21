package com.iitsm.domain.strategy.suggestion;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * OOP: Immutable value object. The candidates of one request (ADR-042): the resolved-ticket window and the article window.
 * A ticket that was promoted appears once, as its article, so its fix is not suggested twice and its article (the
 * curated version) is the one voted on.
 */
public final class CandidatePool {

    private final List<SuggestionCandidate> candidates;

    private CandidatePool(List<SuggestionCandidate> candidates) {
        this.candidates = List.copyOf(candidates);
    }

    public static CandidatePool of(Collection<SuggestionCandidate> tickets, Collection<SuggestionCandidate> articles) {
        Set<String> promoted = articles.stream().map(SuggestionCandidate::originIncidentId).collect(Collectors.toSet());
        List<SuggestionCandidate> merged = new ArrayList<>(articles);
        tickets.stream().filter(ticket -> !promoted.contains(ticket.sourceId())).forEach(merged::add);
        return new CandidatePool(merged);
    }

    /** Articles first, then the tickets not promoted; unmodifiable. */
    public List<SuggestionCandidate> candidates() {
        return candidates;
    }
}
