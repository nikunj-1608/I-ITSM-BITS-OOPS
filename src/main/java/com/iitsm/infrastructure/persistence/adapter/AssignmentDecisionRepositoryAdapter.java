package com.iitsm.infrastructure.persistence.adapter;

import com.iitsm.domain.exception.PersistenceFailureException;
import com.iitsm.domain.repository.AssignmentDecisionRepository;
import com.iitsm.domain.strategy.assignment.AssignmentDecision;
import com.iitsm.infrastructure.persistence.entity.AssignmentDecisionJpaEntity;
import com.iitsm.infrastructure.persistence.mapper.CandidateScoresJson;
import com.iitsm.infrastructure.persistence.repository.SpringDataAssignmentDecisionRepository;
import java.util.List;
import java.util.Objects;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

// OOP (Adapter + Dependency Inversion): AssignmentDecisionRepositoryAdapter implements a domain port with Spring Data/JPA; the domain never sees the database.
/**
 * Pattern: Adapter / Repository. Stores assignment decisions (ADR-044); the ranking travels as one JSON column
 * ({@link CandidateScoresJson}). Decisions are inserted only; the newest ones of a ticket are read for the admin dialog.
 */
@Component
public class AssignmentDecisionRepositoryAdapter implements AssignmentDecisionRepository {

    private final SpringDataAssignmentDecisionRepository springDataRepository;
    private final CandidateScoresJson candidateScoresJson;

    // OOP (Dependency Injection): collaborators are passed through the constructor (no field injection), stored in final fields.
    public AssignmentDecisionRepositoryAdapter(
            SpringDataAssignmentDecisionRepository springDataRepository, CandidateScoresJson candidateScoresJson) {
        this.springDataRepository = Objects.requireNonNull(springDataRepository, "springDataRepository cannot be null");
        this.candidateScoresJson = Objects.requireNonNull(candidateScoresJson, "candidateScoresJson cannot be null");
    }

    @Override
    public AssignmentDecision save(AssignmentDecision decision) {
        Objects.requireNonNull(decision, "decision cannot be null");
        try {
            return toDomain(springDataRepository.save(toEntity(decision)));
        } catch (DataAccessException ex) {
            throw new PersistenceFailureException("Failed to save assignment decision " + decision.id(), ex);
        }
    }

    @Override
    public List<AssignmentDecision> findNewestByIncidentId(String incidentId, int limit) {
        if (incidentId == null || limit < 1) {
            return List.of();
        }
        try {
            return springDataRepository.findByIncidentIdOrderByDecidedAtDescIdDesc(incidentId, PageRequest.of(0, limit))
                    .stream().map(this::toDomain).toList();
        } catch (DataAccessException ex) {
            throw new PersistenceFailureException("Failed to read assignment decisions of " + incidentId, ex);
        }
    }

    private AssignmentDecisionJpaEntity toEntity(AssignmentDecision d) {
        AssignmentDecisionJpaEntity e = new AssignmentDecisionJpaEntity();
        e.setId(d.id());
        e.setIncidentId(d.incidentId());
        e.setPoolKind(d.poolKind());
        e.setStrategyName(d.strategyName());
        e.setChosenTechnicianId(d.chosenTechnicianId());
        e.setActorId(d.actorId());
        e.setRelatedIncidentId(d.relatedIncidentId());
        e.setCandidateCount(d.candidateCount());
        e.setCandidatesJson(candidateScoresJson.write(d.candidates()));
        e.setDecidedAt(d.decidedAt());
        return e;
    }

    private AssignmentDecision toDomain(AssignmentDecisionJpaEntity e) {
        return new AssignmentDecision(e.getId(), e.getIncidentId(), e.getPoolKind(), e.getStrategyName(),
                e.getChosenTechnicianId(), e.getActorId(), e.getRelatedIncidentId(), e.getDecidedAt(),
                e.getCandidateCount(), candidateScoresJson.read(e.getCandidatesJson()));
    }
}
