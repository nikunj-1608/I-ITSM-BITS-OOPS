package com.iitsm.application.service;

import com.iitsm.domain.exception.TechnicianOverloadedException;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.Technician;
import com.iitsm.domain.repository.TechnicianRepository;
import com.iitsm.domain.strategy.assignment.AssignmentRecommendation;
import com.iitsm.domain.strategy.assignment.AssignmentStrategy;
import com.iitsm.domain.strategy.assignment.RoutedCandidates;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Takes a capacity slot for a ticket on the technician the active assignment strategy recommends (ADR-026).
 *
 * <p>The slot is saved in the <em>caller's</em> transaction (propagation MANDATORY), together with the ticket's own
 * change: either both commit or both roll back. Before ADR-026 this service committed the slot in a transaction of its
 * own, so any later failure of the caller (a conflict at commit, a constraint violation, a failing listener) left a
 * phantom slot behind (stress-audit items H8, M6 and the unexplained P2 mismatch).
 *
 * <p>Since ADR-044 the same transaction also records the assignment decision: the strategy's ranking, the pool the
 * candidates came from and who triggered it. The ranking is the one the strategy chose from, never recomputed.
 *
 * <p>Only the expected capacity race ({@link TechnicianOverloadedException}) is absorbed here. A lost optimistic-lock
 * race on the technician surfaces when the caller's transaction commits, so callers that must not fail because of it
 * (intake, the dispatcher) catch it at their own transaction boundary; any other failure propagates.
 */
@Service
public class TechnicianAssignmentService {

    private static final Logger log = LoggerFactory.getLogger(TechnicianAssignmentService.class);

    private final TechnicianRepository technicianRepository;
    private final AssignmentDecisionRecorder decisionRecorder;

    public TechnicianAssignmentService(TechnicianRepository technicianRepository, AssignmentDecisionRecorder decisionRecorder) {
        this.technicianRepository = Objects.requireNonNull(technicianRepository, "technicianRepository cannot be null");
        this.decisionRecorder = Objects.requireNonNull(decisionRecorder, "decisionRecorder cannot be null");
    }

    /**
     * Takes a slot on the technician the strategy recommends among the routed candidates and records the decision.
     *
     * @param actorId who triggered the assignment ({@code SYSTEM} at intake, {@code DISPATCHER}, or the administrator)
     * @return the technician's id, or empty when nobody could take the ticket (no candidate, a capacity race)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<String> tryAssignTechnician(
            Incident incident, RoutedCandidates routed, AssignmentStrategy strategy, String actorId, Instant now) {
        if (routed == null || routed.isEmpty() || strategy == null) {
            return Optional.empty();
        }
        try {
            AssignmentRecommendation recommendation = strategy.recommend(incident, routed.technicians());
            Optional<Technician> taken = takeSlot(recommendation.technician(), incident);
            taken.ifPresent(tech -> decisionRecorder.recordStrategyChoice(
                    incident.getId(), routed.pool(), recommendation, actorId, now));
            return taken.map(Technician::getId);
        } catch (TechnicianOverloadedException ex) {
            log.warn("Technician capacity race for incident {}: {}", incident.getId(), ex.getMessage());
            return Optional.empty();
        }
    }

    /** Reloads the technician to check real-time capacity and the current version, then takes the slot. */
    private Optional<Technician> takeSlot(Technician recommended, Incident incident) {
        if (recommended == null) {
            return Optional.empty();
        }
        Optional<Technician> optTech = technicianRepository.findById(recommended.getId());
        if (optTech.isEmpty()) {
            return Optional.empty();
        }
        Technician freshTech = optTech.get();
        if (!freshTech.hasCapacity()) {
            log.info("Technician {} has reached maximum capacity", freshTech.getId());
            return Optional.empty();
        }
        freshTech.assignIncident(incident.getId());
        technicianRepository.save(freshTech);
        return Optional.of(freshTech);
    }
}
