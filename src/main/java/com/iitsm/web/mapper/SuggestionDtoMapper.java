package com.iitsm.web.mapper;

import com.iitsm.application.service.ResolutionSuggestionService.VoteResult;
import com.iitsm.domain.model.TicketDisplayName;
import com.iitsm.domain.strategy.suggestion.ResolutionSuggestion;
import com.iitsm.domain.strategy.suggestion.SuggestionCandidate;
import com.iitsm.domain.strategy.suggestion.SuggestionSourceType;
import com.iitsm.domain.strategy.suggestion.SuggestionVote;
import com.iitsm.web.dto.SuggestedResolutionView;
import com.iitsm.web.dto.SuggestionVoteView;
import java.util.List;

// OOP (Encapsulation of projection): SuggestionDtoMapper builds persona-specific views, so hiding fields is enforced by the type, not by the UI.
/** Maps suggested resolutions and votes to their staff views (ADR-040, ADR-041); a source's problem text is left out. */
public final class SuggestionDtoMapper {

    private SuggestionDtoMapper() { }

    public static List<SuggestedResolutionView> toViews(List<ResolutionSuggestion> suggestions) {
        return suggestions.stream().map(SuggestionDtoMapper::toView).toList();
    }

    public static SuggestedResolutionView toView(ResolutionSuggestion suggestion) {
        SuggestionCandidate source = suggestion.source();
        return new SuggestedResolutionView(
                source.sourceType().name(),
                source.sourceId(),
                displayId(source),
                source.title(),
                source.rootCause(),
                source.stepsTaken(),
                source.preventiveAction(),
                suggestion.similarity(),
                suggestion.score(),
                suggestion.votes().worked(),
                suggestion.votes().didNotWork(),
                suggestion.matchedTerms());
    }

    public static SuggestionVoteView toView(VoteResult result) {
        SuggestionVote vote = result.vote();
        return new SuggestionVoteView(vote.source().type().name(), vote.source().id(), vote.targetIncidentId(),
                vote.worked(), result.tally().worked(), result.tally().didNotWork());
    }

    /** The staff ticket name for a resolved ticket; an article is shown by its id. */
    private static String displayId(SuggestionCandidate source) {
        return source.sourceType() == SuggestionSourceType.RESOLVED_TICKET
                ? TicketDisplayName.of(source.priority(), source.sourceId())
                : source.sourceId();
    }
}
