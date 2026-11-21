package com.iitsm.infrastructure.persistence.repository;

import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.infrastructure.persistence.entity.KnowledgeArticleJpaEntity;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data access to {@code knowledge_articles} (ADR-042). */
public interface SpringDataKnowledgeArticleRepository extends JpaRepository<KnowledgeArticleJpaEntity, String> {

    boolean existsBySourceIncidentId(String sourceIncidentId);

    List<KnowledgeArticleJpaEntity> findByCategoryOrderByCreatedAtDescIdDesc(IncidentCategory category, Pageable page);

    List<KnowledgeArticleJpaEntity> findAllByOrderByCreatedAtDescIdDesc(Pageable page);
}
