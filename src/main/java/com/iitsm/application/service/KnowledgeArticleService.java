package com.iitsm.application.service;

import com.iitsm.domain.exception.AlreadyPromotedException;
import com.iitsm.domain.exception.ValidationException;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.KnowledgeArticle;
import com.iitsm.domain.model.PersonalDataRedactor;
import com.iitsm.domain.model.User;
import com.iitsm.domain.repository.KnowledgeArticleRepository;
import com.iitsm.domain.repository.UserRepository;
import java.time.Clock;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Promotes a resolved ticket's fix into the knowledge base (spec feature G, ADR-042). Orchestration only: the ticket is
 * loaded through {@link IncidentLifecycleService#getIncident} (404 unknown, 403 not viewable); a second promotion is 409
 * {@code ERR_ALREADY_PROMOTED}; the rules about status and actor live in {@link KnowledgeArticle#promote} and
 * {@link User#canPromoteResolution}; the reporter's personal data is removed by {@link PersonalDataRedactor}.
 */
@Service
public class KnowledgeArticleService {

    private final IncidentLifecycleService lifecycleService;
    private final UserRepository userRepository;
    private final KnowledgeArticleRepository articleRepository;
    private final Clock clock;

    public KnowledgeArticleService(
            IncidentLifecycleService lifecycleService,
            UserRepository userRepository,
            KnowledgeArticleRepository articleRepository,
            Clock clock) {
        this.lifecycleService = Objects.requireNonNull(lifecycleService, "lifecycleService cannot be null");
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository cannot be null");
        this.articleRepository = Objects.requireNonNull(articleRepository, "articleRepository cannot be null");
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
    }

    /** The new article; {@code title} may be blank (the ticket's title is used). */
    @Transactional
    public KnowledgeArticle promote(String actorId, String sourceIncidentId, String title) {
        Incident source = lifecycleService.getIncident(actorId, sourceIncidentId);
        User actor = userRepository.findById(actorId)
                .orElseThrow(() -> new ValidationException("userId", "User not found with ID: " + actorId));
        if (articleRepository.existsBySourceIncidentId(source.getId())) {
            throw new AlreadyPromotedException(source.getId());
        }
        User reporter = userRepository.findById(source.getReporterId()).orElse(null);
        KnowledgeArticle article = KnowledgeArticle.promote(
                source, actor, title, PersonalDataRedactor.forReporter(reporter), clock.instant());
        return articleRepository.save(article);
    }
}
