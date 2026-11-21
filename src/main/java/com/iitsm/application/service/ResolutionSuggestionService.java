package com.iitsm.application.service;

import com.iitsm.domain.exception.ResourceNotFoundException;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.repository.KnowledgeArticleRepository;
import com.iitsm.domain.repository.ResolvedCaseRepository;
import com.iitsm.domain.repository.SuggestionVoteRepository;
import com.iitsm.domain.strategy.suggestion.CandidatePool;
import com.iitsm.domain.strategy.suggestion.ResolutionSuggester;
import com.iitsm.domain.strategy.suggestion.ResolutionSuggestion;
import com.iitsm.domain.strategy.suggestion.SuggestionCandidate;
import com.iitsm.domain.strategy.suggestion.SuggestionPolicy;
import com.iitsm.domain.strategy.suggestion.SuggestionQuery;
import com.iitsm.domain.strategy.suggestion.SuggestionSourceKey;
import com.iitsm.domain.strategy.suggestion.SuggestionVote;
import com.iitsm.domain.strategy.suggestion.VoteTallies;
import com.iitsm.domain.strategy.suggestion.VoteTally;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Suggested resolutions for a ticket and the technicians' votes on them (spec feature G, ADR-040, ADR-041).
 * Orchestration only: the ticket is loaded through {@link IncidentLifecycleService#getIncident}, so whoever may view it
 * may see its suggestions and vote on them (an unknown ticket is 404, a ticket outside the actor's access 403); the
 * resolved-ticket window, the knowledge-base window (ADR-042) and the vote counts are read in one query each; the ranking is the wired {@link ResolutionSuggester}.
 */
@Service
public class ResolutionSuggestionService {

    /** The stored vote and the source's counts after it. */
    public record VoteResult(SuggestionVote vote, VoteTally tally) {}

    private final IncidentLifecycleService lifecycleService;
    private final ResolvedCaseRepository resolvedCaseRepository;
    private final SuggestionVoteRepository voteRepository;
    private final KnowledgeArticleRepository articleRepository;
    private final ResolutionSuggester suggester;
    private final SuggestionPolicy policy;
    private final Clock clock;

    public ResolutionSuggestionService(
            IncidentLifecycleService lifecycleService,
            ResolvedCaseRepository resolvedCaseRepository,
            SuggestionVoteRepository voteRepository,
            KnowledgeArticleRepository articleRepository,
            ResolutionSuggester suggester,
            SuggestionPolicy policy,
            Clock clock) {
        this.lifecycleService = Objects.requireNonNull(lifecycleService, "lifecycleService cannot be null");
        this.resolvedCaseRepository = Objects.requireNonNull(resolvedCaseRepository, "resolvedCaseRepository cannot be null");
        this.voteRepository = Objects.requireNonNull(voteRepository, "voteRepository cannot be null");
        this.articleRepository = Objects.requireNonNull(articleRepository, "articleRepository cannot be null");
        this.suggester = Objects.requireNonNull(suggester, "suggester cannot be null");
        this.policy = Objects.requireNonNull(policy, "policy cannot be null");
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
    }

    /** The best past fixes for the ticket, best first; an empty list when nothing is similar enough. */
    @Transactional(readOnly = true)
    public List<ResolutionSuggestion> suggestFor(String actorId, String incidentId) {
        Incident incident = lifecycleService.getIncident(actorId, incidentId);
        SuggestionQuery query = SuggestionQuery.forIncident(incident);
        if (!policy.canSuggestFor(query)) {
            return List.of();
        }
        IncidentCategory category = policy.windowCategory(query);
        List<SuggestionCandidate> candidates = CandidatePool.of(
                resolvedCaseRepository.findNewestResolvedCases(category, policy.candidateWindow()),
                articleRepository.findNewestArticles(category, policy.articleWindow())).candidates();
        VoteTallies votes = voteRepository.tallies(candidates.stream().map(SuggestionCandidate::key).toList());
        return suggester.suggest(query, candidates, votes);
    }

    /**
     * Records the actor's verdict on a suggested fix for the ticket; voting again on the same source and ticket changes
     * the verdict. The source must exist (else 404 {@code ERR_NOT_FOUND}); a ticket cannot be voted as its own fix (400).
     */
    @Transactional
    public VoteResult vote(String actorId, String incidentId, SuggestionSourceKey source, boolean worked) {
        Incident target = lifecycleService.getIncident(actorId, incidentId);
        if (!sourceExists(source)) {
            throw new ResourceNotFoundException("Suggestion source not found: " + source.type() + " " + source.id());
        }
        SuggestionVote vote = voteRepository.findVote(actorId, source, target.getId())
                .map(previous -> previous.recast(worked, clock.instant()))
                .orElseGet(() -> SuggestionVote.cast(actorId, source, target.getId(), worked, clock.instant()));
        SuggestionVote saved = voteRepository.save(vote);
        return new VoteResult(saved, voteRepository.tallies(List.of(source)).of(source));
    }

    /** A resolved ticket (confirmed resolution) or a knowledge-base article (ADR-042). */
    private boolean sourceExists(SuggestionSourceKey source) {
        return switch (source.type()) {
            case RESOLVED_TICKET -> resolvedCaseRepository.isResolvedCase(source.id());
            case KB_ARTICLE -> articleRepository.existsById(source.id());
        };
    }
}
