package com.iitsm.application.service;

import com.iitsm.domain.event.DomainEvent;
import com.iitsm.domain.event.DomainEventPublisher;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.Technician;
import com.iitsm.domain.repository.IncidentRepository;
import com.iitsm.domain.repository.TechnicianRepository;
import com.iitsm.domain.strategy.assignment.AssignmentStrategyRegistry;
import com.iitsm.domain.strategy.assignment.RoutedCandidates;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pattern: Transaction Script (one use case, one transaction).
 * Assigns one waiting ticket to a technician (ADR-026). The technician's capacity slot and the ticket's new assignee
 * are saved in the same transaction, so they commit or roll back together: a failed attempt leaves no slot behind and
 * the ticket simply stays waiting for the next attempt.
 *
 * <p>Who may take the ticket is decided by {@link TicketRouter} (ADR-036): the team that handles the ticket's category,
 * then skilled technicians of other teams, otherwise the ticket waits ({@code NOT_ASSIGNED}).
 *
 * <p>Used by the ticket intake (right after the ticket committed) and by {@link UnassignedIncidentDispatcher} (one
 * call, so one transaction, per ticket). A lost optimistic-lock race on the technician or the ticket surfaces when
 * this transaction commits, i.e. at the proxy boundary; the callers catch it there.
 */
@Service
public class TicketDispatchService {

    private static final Logger log = LoggerFactory.getLogger(TicketDispatchService.class);

    /** What happened to the ticket. */
    public enum Outcome {
        /** The ticket got a technician and a slot. */
        ASSIGNED,
        /** The ticket does not exist or is no longer waiting (assigned meanwhile, in triage, past CLASSIFIED). */
        NOT_WAITING,
        /** No technician has free capacity anywhere; every other waiting ticket would see the same pool. */
        NO_CAPACITY,
        /** Technicians have capacity, but none can take this ticket now (exclusions, a capacity race). */
        NOT_ASSIGNED
    }

    /**
     * @param outcome  what happened
     * @param incident the ticket as it is now (the saved one when assigned); null when the ticket does not exist
     */
    public record DispatchResult(Outcome outcome, Incident incident) {}

    private final IncidentRepository incidentRepository;
    private final TechnicianRepository technicianRepository;
    private final TicketRouter ticketRouter;
    private final AssignmentStrategyRegistry strategyRegistry;
    private final TechnicianAssignmentService technicianAssignmentService;
    private final DomainEventPublisher eventPublisher;
    private final Clock clock;

    public TicketDispatchService(
            IncidentRepository incidentRepository,
            TechnicianRepository technicianRepository,
            TicketRouter ticketRouter,
            AssignmentStrategyRegistry strategyRegistry,
            TechnicianAssignmentService technicianAssignmentService,
            DomainEventPublisher eventPublisher,
            Clock clock) {
        this.incidentRepository = Objects.requireNonNull(incidentRepository, "incidentRepository cannot be null");
        this.technicianRepository = Objects.requireNonNull(technicianRepository, "technicianRepository cannot be null");
        this.ticketRouter = Objects.requireNonNull(ticketRouter, "ticketRouter cannot be null");
        this.strategyRegistry = Objects.requireNonNull(strategyRegistry, "strategyRegistry cannot be null");
        this.technicianAssignmentService = Objects.requireNonNull(technicianAssignmentService, "technicianAssignmentService cannot be null");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher cannot be null");
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
    }

    /**
     * Tries to assign the ticket now.
     *
     * @param actorId who is recorded as the assigner in the audit trail and the notification ("SYSTEM" at intake,
     *                "DISPATCHER" for the background run)
     */
    @Transactional
    public DispatchResult dispatchOne(String incidentId, String actorId) {
        Objects.requireNonNull(incidentId, "incidentId cannot be null");
        Objects.requireNonNull(actorId, "actorId cannot be null");
        Incident incident = incidentRepository.findById(incidentId).orElse(null);
        if (incident == null || !incident.isAwaitingAutomaticAssignment()) {
            return new DispatchResult(Outcome.NOT_WAITING, incident);
        }
        List<Technician> available = availableWithCapacity();
        if (available.isEmpty()) {
            return new DispatchResult(Outcome.NO_CAPACITY, incident);
        }
        Instant now = clock.instant();
        Optional<String> technicianId = takeSlot(incident, ticketRouter.route(incident, available), actorId, now);
        if (technicianId.isEmpty()) {
            return new DispatchResult(Outcome.NOT_ASSIGNED, incident);
        }
        incident.assign(technicianId.get(), now);
        Incident saved = incidentRepository.save(incident);
        eventPublisher.publish(new DomainEvent.IncidentAssigned(
                UUID.randomUUID().toString(), now, incidentId, actorId, technicianId.get()));
        log.info("{} assigned waiting incident {} to technician {}", actorId, incidentId, technicianId.get());
        return new DispatchResult(Outcome.ASSIGNED, saved);
    }

    private List<Technician> availableWithCapacity() {
        return technicianRepository.findAvailableTechnicians().stream()
                .filter(Technician::hasCapacity)
                .collect(Collectors.toList());
    }

    private Optional<String> takeSlot(Incident incident, RoutedCandidates routed, String actorId, Instant now) {
        if (routed.isEmpty()) {
            return Optional.empty();
        }
        return technicianAssignmentService.tryAssignTechnician(
                incident, routed, strategyRegistry.getActiveStrategy(), actorId, now);
    }
}
