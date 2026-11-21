package com.iitsm.infrastructure.persistence.entity;

import com.iitsm.domain.model.IncidentCategory;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

// OOP (Separation of concerns): KnowledgeArticleJpaEntity is the persistence twin of a domain class; mappers convert between them so the domain stays free of JPA.
/** Table {@code knowledge_articles} (Flyway V10, ADR-042). */
@Entity
@Table(name = "knowledge_articles")
public class KnowledgeArticleJpaEntity {

    @Id
    @Column(name = "id", length = 80)
    private String id;

    @Column(name = "title", nullable = false)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 32)
    private IncidentCategory category;

    @Column(name = "problem", nullable = false, columnDefinition = "TEXT")
    private String problem;

    @Column(name = "root_cause", nullable = false, columnDefinition = "TEXT")
    private String rootCause;

    @Column(name = "steps_taken", nullable = false, columnDefinition = "TEXT")
    private String stepsTaken;

    @Column(name = "preventive_action", nullable = false, columnDefinition = "TEXT")
    private String preventiveAction;

    @Column(name = "source_incident_id", nullable = false, length = 64, unique = true)
    private String sourceIncidentId;

    @Column(name = "created_by", nullable = false, length = 64)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public KnowledgeArticleJpaEntity() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public IncidentCategory getCategory() { return category; }
    public void setCategory(IncidentCategory category) { this.category = category; }

    public String getProblem() { return problem; }
    public void setProblem(String problem) { this.problem = problem; }

    public String getRootCause() { return rootCause; }
    public void setRootCause(String rootCause) { this.rootCause = rootCause; }

    public String getStepsTaken() { return stepsTaken; }
    public void setStepsTaken(String stepsTaken) { this.stepsTaken = stepsTaken; }

    public String getPreventiveAction() { return preventiveAction; }
    public void setPreventiveAction(String preventiveAction) { this.preventiveAction = preventiveAction; }

    public String getSourceIncidentId() { return sourceIncidentId; }
    public void setSourceIncidentId(String sourceIncidentId) { this.sourceIncidentId = sourceIncidentId; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
