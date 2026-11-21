package com.iitsm.infrastructure.persistence.adapter;

import com.iitsm.domain.exception.AlreadyPromotedException;
import com.iitsm.domain.exception.ConcurrentUpdateException;
import com.iitsm.domain.exception.PersistenceFailureException;
import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.KnowledgeArticle;
import com.iitsm.domain.repository.KnowledgeArticleRepository;
import com.iitsm.domain.strategy.suggestion.SuggestionCandidate;
import com.iitsm.domain.strategy.suggestion.SuggestionSourceType;
import com.iitsm.infrastructure.persistence.entity.KnowledgeArticleJpaEntity;
import com.iitsm.infrastructure.persistence.repository.SpringDataKnowledgeArticleRepository;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

// OOP (Adapter + Dependency Inversion): KnowledgeArticleRepositoryAdapter implements a domain port with Spring Data/JPA; the domain never sees the database.
/**
 * Pattern: Adapter / Repository. Stores knowledge-base articles and reads the newest ones as suggestion candidates
 * (ADR-042). The unique key on the source ticket turns a lost race of two promotions into a 409.
 */
@Component
public class KnowledgeArticleRepositoryAdapter implements KnowledgeArticleRepository {

    private final SpringDataKnowledgeArticleRepository springDataRepository;

    // OOP (Dependency Injection): collaborators are passed through the constructor (no field injection), stored in final fields.
    public KnowledgeArticleRepositoryAdapter(SpringDataKnowledgeArticleRepository springDataRepository) {
        this.springDataRepository = Objects.requireNonNull(springDataRepository, "springDataRepository cannot be null");
    }

    @Override
    public KnowledgeArticle save(KnowledgeArticle article) {
        Objects.requireNonNull(article, "article cannot be null");
        try {
            // The id is assigned (KB- + ticket id), so Spring Data would merge: an existing article must never be overwritten.
            if (springDataRepository.existsById(article.getId())) {
                throw new AlreadyPromotedException(article.getSourceIncidentId());
            }
            return toDomain(springDataRepository.saveAndFlush(toEntity(article)));
        } catch (DataIntegrityViolationException ex) {
            throw new ConcurrentUpdateException(ex);
        } catch (DataAccessException ex) {
            throw new PersistenceFailureException("Failed to save knowledge article " + article.getId(), ex);
        }
    }

    @Override
    public Optional<KnowledgeArticle> findById(String id) {
        try {
            return id == null ? Optional.empty() : springDataRepository.findById(id).map(KnowledgeArticleRepositoryAdapter::toDomain);
        } catch (DataAccessException ex) {
            throw new PersistenceFailureException("Failed to read knowledge article " + id, ex);
        }
    }

    @Override
    public boolean existsById(String id) {
        try {
            return id != null && !id.isBlank() && springDataRepository.existsById(id.trim());
        } catch (DataAccessException ex) {
            throw new PersistenceFailureException("Failed to check knowledge article " + id, ex);
        }
    }

    @Override
    public boolean existsBySourceIncidentId(String sourceIncidentId) {
        try {
            return sourceIncidentId != null && springDataRepository.existsBySourceIncidentId(sourceIncidentId);
        } catch (DataAccessException ex) {
            throw new PersistenceFailureException("Failed to check the knowledge base for " + sourceIncidentId, ex);
        }
    }

    @Override
    public List<SuggestionCandidate> findNewestArticles(IncidentCategory category, int limit) {
        if (limit < 1) {
            return List.of();
        }
        PageRequest page = PageRequest.of(0, limit);
        try {
            List<KnowledgeArticleJpaEntity> rows = category == null
                    ? springDataRepository.findAllByOrderByCreatedAtDescIdDesc(page)
                    : springDataRepository.findByCategoryOrderByCreatedAtDescIdDesc(category, page);
            return rows.stream().map(KnowledgeArticleRepositoryAdapter::toCandidate).toList();
        } catch (DataAccessException ex) {
            throw new PersistenceFailureException("Failed to read knowledge articles for suggestions", ex);
        }
    }

    private static SuggestionCandidate toCandidate(KnowledgeArticleJpaEntity e) {
        return new SuggestionCandidate(SuggestionSourceType.KB_ARTICLE, e.getId(), e.getCategory(), null, e.getTitle(),
                e.getProblem(), e.getRootCause(), e.getStepsTaken(), e.getPreventiveAction(), e.getSourceIncidentId());
    }

    private static KnowledgeArticleJpaEntity toEntity(KnowledgeArticle a) {
        KnowledgeArticleJpaEntity e = new KnowledgeArticleJpaEntity();
        e.setId(a.getId());
        e.setTitle(a.getTitle());
        e.setCategory(a.getCategory());
        e.setProblem(a.getProblem());
        e.setRootCause(a.getRootCause());
        e.setStepsTaken(a.getStepsTaken());
        e.setPreventiveAction(a.getPreventiveAction());
        e.setSourceIncidentId(a.getSourceIncidentId());
        e.setCreatedBy(a.getCreatedBy());
        e.setCreatedAt(a.getCreatedAt());
        return e;
    }

    private static KnowledgeArticle toDomain(KnowledgeArticleJpaEntity e) {
        return new KnowledgeArticle(e.getId(), e.getTitle(), e.getCategory(), e.getProblem(), e.getRootCause(),
                e.getStepsTaken(), e.getPreventiveAction(), e.getSourceIncidentId(), e.getCreatedBy(), e.getCreatedAt());
    }
}
