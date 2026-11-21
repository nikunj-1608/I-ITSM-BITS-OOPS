package com.iitsm.web.controller;

import com.iitsm.application.service.KnowledgeArticleService;
import com.iitsm.domain.model.KnowledgeArticle;
import com.iitsm.web.dto.KnowledgeArticleView;
import com.iitsm.web.dto.PromoteArticleRequest;
import com.iitsm.web.security.CurrentUser;
import jakarta.validation.Valid;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// OOP (Single responsibility): KnowledgeArticleController validates input, calls one application service and maps the result to a DTO; no business rule lives here.
/**
 * Promote a resolved ticket's fix into the knowledge base (spec feature G, ADR-042). Staff only; the service and the
 * domain decide who may promote which ticket. There is deliberately no list, edit or delete endpoint.
 */
@RestController
@RequestMapping("/api/technician")
@PreAuthorize("hasAnyRole('TECHNICIAN', 'TEAM_LEAD', 'ADMIN')")
public class KnowledgeArticleController {

    private final KnowledgeArticleService articleService;
    private final CurrentUser currentUser;

    // OOP (Dependency Injection): services arrive through the constructor, so the controller depends on classes it is given, not ones it creates.
    public KnowledgeArticleController(KnowledgeArticleService articleService, CurrentUser currentUser) {
        this.articleService = Objects.requireNonNull(articleService, "articleService cannot be null");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser cannot be null");
    }

    @PostMapping("/knowledge-articles")
    public ResponseEntity<KnowledgeArticleView> promote(
            @Valid @RequestBody PromoteArticleRequest request,
            Authentication authentication) {
        String actorId = currentUser.idOf(authentication);
        KnowledgeArticle article = articleService.promote(actorId, request.sourceIncidentId(), request.title());
        return ResponseEntity.status(HttpStatus.CREATED).body(toView(article));
    }

    private static KnowledgeArticleView toView(KnowledgeArticle a) {
        return new KnowledgeArticleView(a.getId(), a.getTitle(), a.getCategory().name(), a.getProblem(), a.getRootCause(),
                a.getStepsTaken(), a.getPreventiveAction(), a.getSourceIncidentId(), a.getCreatedBy(), a.getCreatedAt());
    }
}
