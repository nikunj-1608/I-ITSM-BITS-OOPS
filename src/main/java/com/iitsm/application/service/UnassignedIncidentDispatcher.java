package com.iitsm.application.service;

import com.iitsm.application.service.TicketDispatchService.Outcome;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.IncidentStatus;
import com.iitsm.domain.repository.IncidentRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Scheduled background dispatcher that periodically scans for unassigned CLASSIFIED incidents
 * and assigns them as technician capacity becomes available.
 *
 * <p>Each ticket is assigned in a transaction of its own ({@link TicketDispatchService#dispatchOne}), so one ticket that
 * fails or loses a race never undoes the others, and a failed attempt never leaves a capacity slot behind (ADR-026).
 * This class is a scheduler boundary: a failure on one ticket is logged (a lost race as a warning, anything else as an
 * error with its stack trace) and the cycle goes on; the ticket is tried again in the next cycle.
 */
@Service
public class UnassignedIncidentDispatcher {

    private static final Logger log = LoggerFactory.getLogger(UnassignedIncidentDispatcher.class);

    /** Recorded as the assigner of tickets this job assigns. */
    static final String ACTOR = "DISPATCHER";

    private final IncidentRepository incidentRepository;
    private final TicketDispatchService dispatchService;

    public UnassignedIncidentDispatcher(IncidentRepository incidentRepository, TicketDispatchService dispatchService) {
        this.incidentRepository = Objects.requireNonNull(incidentRepository, "incidentRepository cannot be null");
        this.dispatchService = Objects.requireNonNull(dispatchService, "dispatchService cannot be null");
    }

    /** Not transactional itself: every ticket gets its own transaction. Returns how many tickets were assigned. */
    @Scheduled(fixedDelayString = "${iitsm.dispatcher.interval-ms:30000}",
            initialDelayString = "${iitsm.dispatcher.initial-delay-ms:10000}")
    public int dispatchWaitingIncidents() {
        List<String> waiting = waitingIncidentIds();
        int assigned = 0;
        for (String incidentId : waiting) {
            Outcome outcome = dispatch(incidentId);
            if (outcome == Outcome.ASSIGNED) {
                assigned++;
            } else if (outcome == Outcome.NO_CAPACITY) {
                log.warn("Dispatcher found no technician with free capacity; {} incident(s) remain waiting",
                        waiting.size() - assigned);
                break; // Still no capacity anywhere; every remaining incident would see the same pool
            }
        }
        return assigned;
    }

    /** Waiting tickets, most urgent first; tickets held back for human triage are never touched (ADR-009). */
    private List<String> waitingIncidentIds() {
        return incidentRepository.findByStatus(IncidentStatus.CLASSIFIED).stream()
                .filter(Incident::isAwaitingAutomaticAssignment)
                .sorted(Comparator.comparing(Incident::getPriority, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(Incident::getId)
                .collect(Collectors.toList());
    }

    private Outcome dispatch(String incidentId) {
        try {
            return dispatchService.dispatchOne(incidentId, ACTOR).outcome();
        } catch (RuntimeException failure) {
            if (ConcurrencyConflicts.isConflict(failure)) {
                log.warn("Dispatcher lost a race for incident {}; retrying next cycle: {}", incidentId, failure.getMessage());
            } else {
                log.error("Dispatcher could not assign incident {}; retrying next cycle", incidentId, failure);
            }
            return Outcome.NOT_ASSIGNED;
        }
    }
}
