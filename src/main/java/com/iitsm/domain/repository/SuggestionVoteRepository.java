package com.iitsm.domain.repository;

import com.iitsm.domain.strategy.suggestion.SuggestionSourceKey;
import com.iitsm.domain.strategy.suggestion.SuggestionVote;
import com.iitsm.domain.strategy.suggestion.VoteTallies;
import java.util.Collection;
import java.util.Optional;

/** Pattern: Repository (port). Technicians' verdicts on suggested fixes (ADR-041); one row per voter, source and ticket. */
public interface SuggestionVoteRepository {

    /** The voter's current verdict on this source for this ticket, if any. */
    Optional<SuggestionVote> findVote(String voterId, SuggestionSourceKey source, String targetIncidentId);

    /**
     * Stores the vote, replacing the voter's earlier verdict on the same source and ticket (one row per key). Two first
     * votes of one voter racing on the same key end as a {@code ConcurrentUpdateException} (HTTP 409) for the loser.
     */
    SuggestionVote save(SuggestionVote vote);

    /** The vote counts of the given sources over every ticket they were voted on, in one query. */
    VoteTallies tallies(Collection<SuggestionSourceKey> sources);
}
