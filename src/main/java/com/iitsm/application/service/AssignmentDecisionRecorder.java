package com.iitsm.application.service;

import com.iitsm.domain.repository.AssignmentDecisionRepository;
import com.iitsm.domain.strategy.assignment.AssignmentDecision;
import com.iitsm.domain.strategy.assignment.AssignmentRecommendation;
import com.iitsm.domain.strategy.assignment.PoolKind;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records why a ticket got its technician, on every path that sets an assignee (ADR-044, Phase L2): a strategy's choice
 * (intake, dispatcher, accept-suggestion, reclassify reroute), a manual assignment, a duplicate riding its parent and
 * the return to the same technician after a rejected wrong-domain request.
 *
 * <p>Propagation MANDATORY, like the capacity slot (ADR-026): a decision is saved in the transaction of the assignment
 * it explains, so a rollback leaves neither the assignment nor its decision. A strategy's ranking is stored as the
 * strategy computed it when it chose (never recomputed), cut to {@code iitsm.assignment.explanation-max-candidates}.
 */
@Service
public class AssignmentDecisionRecorder {

    private static final String ID_PREFIX = "ASG-";

    private final AssignmentDecisionRepository repository;
    private final int maxCandidates;

    public AssignmentDecisionRecorder(
            AssignmentDecisionRepository repository,
            @Value("${iitsm.assignment.explanation-max-candidates:10}") int maxCandidates) {
        this.repository = Objects.requireNonNull(repository, "repository cannot be null");
        if (maxCandidates < 1) {
            throw new IllegalArgumentException("iitsm.assignment.explanation-max-candidates must be at least 1");
        }
        this.maxCandidates = maxCandidates;
    }

    /** A strategy chose among routed candidates of {@code pool}. */
    @Transactional(propagation = Propagation.MANDATORY)
    public AssignmentDecision recordStrategyChoice(String incidentId, PoolKind pool,
            AssignmentRecommendation recommendation, String actorId, Instant now) {
        return repository.save(AssignmentDecision.byStrategy(newId(), incidentId, pool, recommendation, actorId, now,
                maxCandidates));
    }

    /** An administrator assigned the technician by hand. */
    @Transactional(propagation = Propagation.MANDATORY)
    public AssignmentDecision recordManual(String incidentId, String technicianId, String actorId, Instant now) {
        return repository.save(AssignmentDecision.manual(newId(), incidentId, technicianId, actorId, now));
    }

    /** A confident duplicate rides its parent's technician (F4). */
    @Transactional(propagation = Propagation.MANDATORY)
    public AssignmentDecision recordInheritedFromParent(String incidentId, String technicianId, String parentIncidentId,
            String actorId, Instant now) {
        return repository.save(AssignmentDecision.inheritedFromParent(newId(), incidentId, technicianId, parentIncidentId,
                actorId, now));
    }

    /** The administrator rejected a wrong-domain request; the ticket is back with the technician who flagged it. */
    @Transactional(propagation = Propagation.MANDATORY)
    public AssignmentDecision recordRequeued(String incidentId, String technicianId, String actorId, Instant now) {
        return repository.save(AssignmentDecision.requeuedToSameTechnician(newId(), incidentId, technicianId, actorId, now));
    }

    private static String newId() {
        return ID_PREFIX + UUID.randomUUID();
    }
}
