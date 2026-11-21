package com.iitsm.application.service;

import com.iitsm.domain.event.DomainEvent;
import com.iitsm.domain.event.DomainEventPublisher;
import com.iitsm.domain.exception.InvalidStateTransitionException;
import com.iitsm.domain.exception.ServiceNotFoundException;
import com.iitsm.domain.exception.TechnicianUnavailableException;
import com.iitsm.domain.exception.UnauthorizedOperationException;
import com.iitsm.domain.exception.ValidationException;
import com.iitsm.domain.model.AvailabilityStatus;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.IncidentAction;
import com.iitsm.domain.model.IncidentStatus;
import com.iitsm.domain.model.InvestigationNote;
import com.iitsm.domain.model.ReassignmentRequest;
import com.iitsm.domain.model.Technician;
import com.iitsm.domain.model.TicketDisplayName;
import com.iitsm.domain.model.User;
import com.iitsm.domain.policy.AccessPolicy;
import com.iitsm.domain.repository.IncidentRepository;
import com.iitsm.domain.repository.ReassignmentRequestRepository;
import com.iitsm.domain.repository.TechnicianRepository;
import com.iitsm.domain.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pattern: Application Service (Facade over one use case). A team lead gives a ticket of their team to another
 * technician of the same team (ADR-045, Phase M3).
 *
 * <p>The rules, all checked here and not only by the web role annotation: only a team lead may do it
 * ({@link User#canReassignTeamTickets()}); the ticket must be one the lead may act on ({@link AccessPolicy},
 * {@link IncidentAction#REASSIGN}: assigned to the lead or in the lead's team); a ticket in triage stays with the
 * administrator; the status must allow it ({@link IncidentStatus#canBeReassignedByTeamLead()}); the new technician
 * must belong to the lead's own team, be active, not be offline and have a free slot; a reason is required.
 *
 * <p>Everything happens in one transaction, like the administrator's assignment: the previous technician's slot is
 * released, the new one's is taken, the ticket changes hands through the state machine, one
 * {@code MANUAL} assignment decision is recorded with the lead as actor, the reason is kept as an internal note and
 * {@code IncidentAssigned} is published. A failure anywhere (a full technician, a lost race) undoes all of it.
 */
@Service
@Transactional
public class TeamLeadReassignmentService {

    private static final Set<String> NO_ACTIONS = Set.of();
    private static final String REASSIGN_ACTION = "reassign";
    private static final String FLAG_REASON = "Team lead reassignment";

    private final IncidentRepository incidentRepository;
    private final UserRepository userRepository;
    private final TechnicianRepository technicianRepository;
    private final ReassignmentRequestRepository reassignmentRequestRepository;
    private final DomainEventPublisher eventPublisher;
    private final AccessPolicy accessPolicy;
    private final TicketRouter ticketRouter;
    private final AssignmentDecisionRecorder decisionRecorder;
    private final Clock clock;

    public TeamLeadReassignmentService(
            IncidentRepository incidentRepository,
            UserRepository userRepository,
            TechnicianRepository technicianRepository,
            ReassignmentRequestRepository reassignmentRequestRepository,
            DomainEventPublisher eventPublisher,
            AccessPolicy accessPolicy,
            TicketRouter ticketRouter,
            AssignmentDecisionRecorder decisionRecorder,
            Clock clock) {
        this.incidentRepository = Objects.requireNonNull(incidentRepository, "incidentRepository cannot be null");
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository cannot be null");
        this.technicianRepository = Objects.requireNonNull(technicianRepository, "technicianRepository cannot be null");
        this.reassignmentRequestRepository =
                Objects.requireNonNull(reassignmentRequestRepository, "reassignmentRequestRepository cannot be null");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher cannot be null");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy cannot be null");
        this.ticketRouter = Objects.requireNonNull(ticketRouter, "ticketRouter cannot be null");
        this.decisionRecorder = Objects.requireNonNull(decisionRecorder, "decisionRecorder cannot be null");
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
    }

    /** Gives the ticket to {@code technicianId}, a member of the lead's own team; returns the saved ticket. */
    public Incident reassign(String incidentId, String leadId, String technicianId, String reason) {
        requireText("reason", reason, "a reason for the reassignment is required");
        requireText("technicianId", technicianId, "the technician to assign is required");
        User lead = loadUser(leadId);
        if (!lead.canReassignTeamTickets()) {
            throw new UnauthorizedOperationException("Only a team lead can reassign tickets of the team");
        }
        Incident incident = loadIncident(incidentId);
        accessPolicy.enforceCanExecuteAction(lead, incident, IncidentAction.REASSIGN, owningTeamId(incident));
        enforceReassignable(incident);
        Technician target = loadTargetOfLeadsTeam((Technician) lead, technicianId.trim());
        if (target.getId().equals(incident.getAssigneeId())) {
            throw new ValidationException("technicianId", "the ticket is already assigned to " + target.getName());
        }

        Instant now = clock.instant();
        releaseCurrentHolder(incident, lead, now);
        incident.setNeedsTriage(false);
        incident.clearExcludedTechnicians();
        incident.assign(target.getId(), now);
        target.assignIncident(incident.getId()); // a full technician throws here and the whole call rolls back
        technicianRepository.save(target);
        incident.addNote(new InvestigationNote("NOTE-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                lead.getId(), lead.getName(),
                "Reassigned to " + target.getName() + " by the team lead: " + reason.trim(), now, true));
        Incident saved = incidentRepository.save(incident);
        decisionRecorder.recordManual(saved.getId(), target.getId(), lead.getId(), now);
        eventPublisher.publish(new DomainEvent.IncidentAssigned(
                UUID.randomUUID().toString(), now, saved.getId(), lead.getId(), target.getId()));
        return saved;
    }

    private void enforceReassignable(Incident incident) {
        if (incident.isNeedsTriage() || hasPendingRequest(incident)) {
            throw new UnauthorizedOperationException(
                    "Ticket " + incident.getId() + " is in triage; an administrator decides who takes it");
        }
        IncidentStatus status = incident.getStatus();
        if (!status.canBeReassignedByTeamLead()) {
            throw new InvalidStateTransitionException(status, REASSIGN_ACTION, NO_ACTIONS);
        }
    }

    private boolean hasPendingRequest(Incident incident) {
        return reassignmentRequestRepository.findByIncidentId(incident.getId()).stream()
                .anyMatch(ReassignmentRequest::isPending);
    }

    /** The new technician: a member of the lead's own team who can take work now. */
    private Technician loadTargetOfLeadsTeam(Technician lead, String technicianId) {
        Technician target = userRepository.findById(technicianId)
                .filter(u -> u instanceof Technician)
                .map(u -> (Technician) u)
                .orElseThrow(() -> new ServiceNotFoundException("Technician not found: " + technicianId));
        String teamId = lead.getSupportTeamId();
        if (teamId == null || !teamId.equals(target.getSupportTeamId())) {
            throw new UnauthorizedOperationException(
                    "A team lead can only assign to a technician of their own team; " + target.getName() + " is in another team");
        }
        if (!target.isActive() || target.getAvailabilityStatus() == AvailabilityStatus.OFFLINE) {
            throw new TechnicianUnavailableException(target.getName() + " is not available for assignment");
        }
        return target;
    }

    /**
     * Frees the current holder the way the administrator's assignment does: a ticket a technician works on goes back
     * through the state machine to CLASSIFIED and the holder's slot is released; a waiting ticket has no holder.
     */
    private void releaseCurrentHolder(Incident incident, User lead, Instant now) {
        String holderId = incident.getAssigneeId();
        if (incident.getStatus() == IncidentStatus.CLASSIFIED) {
            return;
        }
        incident.flagWrongDomain(lead.getId(), FLAG_REASON, now);
        if (holderId != null) {
            technicianRepository.findById(holderId).ifPresent(holder -> {
                holder.releaseIncident(incident.getId());
                technicianRepository.save(holder);
            });
        }
    }

    private String owningTeamId(Incident incident) {
        return ticketRouter.owningTeamId(incident).orElse(null);
    }

    private static void requireText(String field, String value, String message) {
        if (value == null || value.isBlank()) {
            throw new ValidationException(field, message);
        }
    }

    private User loadUser(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new ValidationException("userId", "User identifier cannot be blank");
        }
        return userRepository.findById(userId.trim())
                .orElseThrow(() -> new ValidationException("userId", "User not found with ID: " + userId));
    }

    /** Accepts the plain ticket id or the staff display name (priority prefix), e.g. P2-FIN-2026-000142. */
    private Incident loadIncident(String incidentId) {
        return incidentRepository.findById(TicketDisplayName.canonicalId(incidentId))
                .orElseThrow(() -> new ServiceNotFoundException("Incident not found: " + incidentId));
    }
}
