package com.iitsm.web.controller;

import com.iitsm.application.service.ResolutionSuggestionService;
import com.iitsm.domain.strategy.suggestion.SuggestionSourceKey;
import com.iitsm.web.dto.SuggestedResolutionView;
import com.iitsm.web.dto.SuggestionVoteRequest;
import com.iitsm.web.dto.SuggestionVoteView;
import com.iitsm.web.mapper.SuggestionDtoMapper;
import com.iitsm.web.security.CurrentUser;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// OOP (Single responsibility): ResolutionSuggestionController validates input, calls one application service and maps the result to a DTO; no business rule lives here.
/**
 * Suggested resolutions for a ticket and votes on them (spec feature G, ADR-040, ADR-041). Staff only: technicians, team
 * leads and administrators; the service applies the ticket's access rules on top. The voter is the signed-in user's id.
 */
@RestController
@RequestMapping("/api/technician")
@PreAuthorize("hasAnyRole('TECHNICIAN', 'TEAM_LEAD', 'ADMIN')")
public class ResolutionSuggestionController {

    private final ResolutionSuggestionService suggestionService;
    private final CurrentUser currentUser;

    // OOP (Dependency Injection): services arrive through the constructor, so the controller depends on classes it is given, not ones it creates.
    public ResolutionSuggestionController(ResolutionSuggestionService suggestionService, CurrentUser currentUser) {
        this.suggestionService = Objects.requireNonNull(suggestionService, "suggestionService cannot be null");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser cannot be null");
    }

    @GetMapping("/incidents/{id}/suggested-resolutions")
    public ResponseEntity<List<SuggestedResolutionView>> suggestedResolutions(
            @PathVariable("id") String id,
            Authentication authentication) {
        String actorId = currentUser.idOf(authentication);
        return ResponseEntity.ok(SuggestionDtoMapper.toViews(suggestionService.suggestFor(actorId, id)));
    }

    @PostMapping("/incidents/{id}/suggested-resolutions/votes")
    public ResponseEntity<SuggestionVoteView> vote(
            @PathVariable("id") String id,
            @Valid @RequestBody SuggestionVoteRequest request,
            Authentication authentication) {
        String voterId = currentUser.idOf(authentication);
        SuggestionSourceKey source = new SuggestionSourceKey(request.sourceType(), request.sourceId());
        return ResponseEntity.ok(SuggestionDtoMapper.toView(suggestionService.vote(voterId, id, source, request.worked())));
    }
}
