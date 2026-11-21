package com.iitsm.application.service;

import com.iitsm.domain.event.DomainEvent;
import com.iitsm.domain.event.DomainEventPublisher;
import com.iitsm.domain.exception.ServiceNotFoundException;
import com.iitsm.domain.model.EscalationRecord;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.IncidentStatus;
import com.iitsm.domain.model.SlaStatus;
import com.iitsm.domain.model.SlaTracker;
import com.iitsm.domain.repository.IncidentRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Application service managing SLA evaluations, violation detection, and triggering escalations.
 *
 * <p>The scan ({@link #evaluateAllActiveIncidents()}) is not one transaction (ADR-031): every ticket is evaluated, saved
 * and escalated in its own transaction, so one ticket that fails or loses a write race neither undoes nor blocks the
 * others, and no transaction stays open for the whole scan.
 */
@Service
@Transactional
public class SlaService {

    private static final Logger log = LoggerFactory.getLogger(SlaService.class);

    private final IncidentRepository incidentRepository;
    private final DomainEventPublisher eventPublisher;
    private final EscalationService escalationService;
    private final Clock clock;
    private final TransactionOperations transactionOperations;

    public SlaService(
            IncidentRepository incidentRepository,
            DomainEventPublisher eventPublisher,
            EscalationService escalationService,
            Clock clock,
            TransactionOperations transactionOperations) {
        this.incidentRepository = Objects.requireNonNull(incidentRepository, "incidentRepository cannot be null");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher cannot be null");
        this.escalationService = Objects.requireNonNull(escalationService, "escalationService cannot be null");
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
        this.transactionOperations =
                Objects.requireNonNull(transactionOperations, "transactionOperations cannot be null");
    }

    /**
     * Evaluates the SLA state for a single incident.
     */
    public SlaEvaluationResult evaluateIncident(String incidentId) {
        Objects.requireNonNull(incidentId, "incidentId cannot be null");
        Incident incident = incidentRepository.findById(incidentId)
                .orElseThrow(() -> new ServiceNotFoundException("Incident not found: " + incidentId));

        return evaluateIncidentInternal(incident);
    }

    /**
     * Evaluates all active (unresolved/unclosed) incidents in the system, one transaction per ticket. A ticket whose
     * evaluation fails is logged and left for the next scan; it does not stop the others (ADR-031). The result list
     * holds the tickets that were evaluated.
     */
    @Transactional(propagation = Propagation.SUPPORTS)
    public List<SlaEvaluationResult> evaluateAllActiveIncidents() {
        List<SlaEvaluationResult> results = new ArrayList<>();
        for (String incidentId : incidentRepository.findActiveIncidentIds()) {
            evaluateInOwnTransaction(incidentId).ifPresent(results::add);
        }
        return results;
    }

    private Optional<SlaEvaluationResult> evaluateInOwnTransaction(String incidentId) {
        try {
            return Optional.ofNullable(transactionOperations.execute(status ->
                    incidentRepository.findById(incidentId).map(this::evaluateIncidentInternal).orElse(null)));
        } catch (RuntimeException failure) {
            logScanFailure(incidentId, failure);
            return Optional.empty();
        }
    }

    private void logScanFailure(String incidentId, RuntimeException failure) {
        if (ConcurrencyConflicts.isConflict(failure)) {
            log.warn("SLA scan: incident {} was changed by someone else during its evaluation, retrying next scan",
                    incidentId);
        } else {
            log.error("SLA scan: evaluating incident {} failed, continuing with the others", incidentId, failure);
        }
    }

    /**
     * Publishes the warning or violation event for a status change that already happened on the
     * incident's tracker. Used by the scan, and by callers that move the tracker themselves (a priority
     * override), so the scan does not see "no change" and stay silent.
     */
    public void announceTransition(Incident incident, SlaStatus previous, Instant now) {
        SlaStatus current = incident.getSlaTracker().getStatus();
        if (previous == current) {
            return;
        }
        log.info("Incident {} SLA status transitioned from {} to {}", incident.getId(), previous, current);
        if (current == SlaStatus.AT_RISK || current == SlaStatus.WARNING) {
            eventPublisher.publish(new DomainEvent.SlaWarning(
                    UUID.randomUUID().toString(),
                    now,
                    incident.getId(),
                    "SYSTEM_SLA_MONITOR",
                    "Incident " + incident.getId() + " reached SLA status " + current.getDescription()));
        } else if (current == SlaStatus.BREACHED) {
            eventPublisher.publish(new DomainEvent.SlaViolated(
                    UUID.randomUUID().toString(),
                    now,
                    incident.getId(),
                    "SYSTEM_SLA_MONITOR",
                    breachedTargetLabel(incident, now),
                    now));
        }
    }

    private static String breachedTargetLabel(Incident incident, Instant now) {
        return incident.getSlaTracker().breachedTarget(now)
                .orElse(SlaTracker.BreachedTarget.RESOLUTION)
                .label();
    }

    private SlaEvaluationResult evaluateIncidentInternal(Incident incident) {
        Instant now = clock.instant();
        SlaTracker tracker = incident.getSlaTracker();

        if (tracker == null) {
            return new SlaEvaluationResult(
                    incident.getId(),
                    SlaStatus.ON_TRACK,
                    SlaStatus.ON_TRACK,
                    0.0,
                    false,
                    Optional.empty()
            );
        }

        SlaStatus prevStatus = tracker.getStatus();
        incident.updateSlaStatus(now);
        SlaStatus currentStatus = tracker.getStatus();
        double riskScore = tracker.getRiskScore(now);
        boolean changed = (prevStatus != currentStatus);

        if (changed) {
            announceTransition(incident, prevStatus, now);
            incidentRepository.save(incident);
        }

        // Trigger escalation check
        Optional<EscalationRecord> escalation = escalationService.evaluateAndEscalate(incident.getId());

        return new SlaEvaluationResult(
                incident.getId(),
                prevStatus,
                currentStatus,
                riskScore,
                changed,
                escalation
        );
    }
}

