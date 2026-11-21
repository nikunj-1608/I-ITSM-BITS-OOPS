package com.iitsm.infrastructure.persistence.entity;

import com.iitsm.domain.strategy.suggestion.SuggestionSourceType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Table {@code suggestion_votes} (Flyway V9, ADR-041). */
@Entity
@Table(name = "suggestion_votes")
public class SuggestionVoteJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "voter_id", nullable = false, length = 64)
    private String voterId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 16)
    private SuggestionSourceType sourceType;

    @Column(name = "source_id", nullable = false, length = 64)
    private String sourceId;

    @Column(name = "target_incident_id", nullable = false, length = 64)
    private String targetIncidentId;

    @Column(name = "worked", nullable = false)
    private boolean worked;

    @Column(name = "voted_at", nullable = false)
    private Instant votedAt;

    public SuggestionVoteJpaEntity() {}

    public Long getId() { return id; }

    public String getVoterId() { return voterId; }
    public void setVoterId(String voterId) { this.voterId = voterId; }

    public SuggestionSourceType getSourceType() { return sourceType; }
    public void setSourceType(SuggestionSourceType sourceType) { this.sourceType = sourceType; }

    public String getSourceId() { return sourceId; }
    public void setSourceId(String sourceId) { this.sourceId = sourceId; }

    public String getTargetIncidentId() { return targetIncidentId; }
    public void setTargetIncidentId(String targetIncidentId) { this.targetIncidentId = targetIncidentId; }

    public boolean isWorked() { return worked; }
    public void setWorked(boolean worked) { this.worked = worked; }

    public Instant getVotedAt() { return votedAt; }
    public void setVotedAt(Instant votedAt) { this.votedAt = votedAt; }
}
