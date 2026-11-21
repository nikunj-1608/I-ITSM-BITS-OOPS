package com.iitsm.domain.repository;

import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.KnowledgeArticle;
import com.iitsm.domain.strategy.suggestion.SuggestionCandidate;
import java.util.List;
import java.util.Optional;

/** Pattern: Repository (port). The small knowledge base of promoted fixes (ADR-042). Articles are only added. */
public interface KnowledgeArticleRepository {

    /**
     * Stores a new article. A second article for the same source ticket (two promotions racing past the service's check)
     * is refused by the unique key and ends as a {@code ConcurrentUpdateException} (HTTP 409).
     */
    KnowledgeArticle save(KnowledgeArticle article);

    Optional<KnowledgeArticle> findById(String id);

    boolean existsById(String id);

    boolean existsBySourceIncidentId(String sourceIncidentId);

    /**
     * The newest articles as suggestion candidates (type {@code KB_ARTICLE}, origin = the source ticket).
     *
     * @param category the category to keep, or null for every category
     * @param limit    the most rows read (the article window)
     */
    List<SuggestionCandidate> findNewestArticles(IncidentCategory category, int limit);
}
