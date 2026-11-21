package com.iitsm.infrastructure.persistence.entity;

import com.iitsm.domain.strategy.assignment.PoolKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import org.springframework.data.domain.Persistable;

/**
 * Table {@code assignment_decisions} (Flyway V11, ADR-044). The id is assigned by the application and a decision is
 * never updated, so the entity reports itself as new ({@link Persistable}): Spring Data then inserts directly instead
 * of looking the id up first.
 */
@Entity
@Table(name = "assignment_decisions")
public class AssignmentDecisionJpaEntity implements Persistable<String> {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "incident_id", nullable = false, length = 64)
    private String incidentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "pool_kind", nullable = false, length = 32)
    private PoolKind poolKind;

    @Column(name = "strategy_name", length = 64)
    private String strategyName;

    @Column(name = "chosen_technician_id", nullable = false, length = 64)
    private String chosenTechnicianId;

    @Column(name = "actor_id", nullable = false)
    private String actorId;

    @Column(name = "related_incident_id", length = 64)
    private String relatedIncidentId;

    @Column(name = "candidate_count", nullable = false)
    private int candidateCount;

    @Column(name = "candidates_json", nullable = false, columnDefinition = "TEXT")
    private String candidatesJson;

    @Column(name = "decided_at", nullable = false)
    private Instant decidedAt;

    @Transient
    private boolean fresh = true;

    @Override
    public String getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return fresh;
    }

    /** Loaded rows are not new. */
    @jakarta.persistence.PostLoad
    @jakarta.persistence.PostPersist
    void markNotNew() {
        this.fresh = false;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getIncidentId() {
        return incidentId;
    }

    public void setIncidentId(String incidentId) {
        this.incidentId = incidentId;
    }

    public PoolKind getPoolKind() {
        return poolKind;
    }

    public void setPoolKind(PoolKind poolKind) {
        this.poolKind = poolKind;
    }

    public String getStrategyName() {
        return strategyName;
    }

    public void setStrategyName(String strategyName) {
        this.strategyName = strategyName;
    }

    public String getChosenTechnicianId() {
        return chosenTechnicianId;
    }

    public void setChosenTechnicianId(String chosenTechnicianId) {
        this.chosenTechnicianId = chosenTechnicianId;
    }

    public String getActorId() {
        return actorId;
    }

    public void setActorId(String actorId) {
        this.actorId = actorId;
    }

    public String getRelatedIncidentId() {
        return relatedIncidentId;
    }

    public void setRelatedIncidentId(String relatedIncidentId) {
        this.relatedIncidentId = relatedIncidentId;
    }

    public int getCandidateCount() {
        return candidateCount;
    }

    public void setCandidateCount(int candidateCount) {
        this.candidateCount = candidateCount;
    }

    public String getCandidatesJson() {
        return candidatesJson;
    }

    public void setCandidatesJson(String candidatesJson) {
        this.candidatesJson = candidatesJson;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public void setDecidedAt(Instant decidedAt) {
        this.decidedAt = decidedAt;
    }
}
