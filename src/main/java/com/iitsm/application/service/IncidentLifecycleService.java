package com.iitsm.application.service;

import com.iitsm.domain.event.DomainEvent;
import com.iitsm.domain.event.DomainEventPublisher;
import com.iitsm.domain.exception.ServiceNotFoundException;
import com.iitsm.domain.exception.UnauthorizedOperationException;
import com.iitsm.domain.exception.ValidationException;
import com.iitsm.domain.model.Feedback;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.IncidentAction;
import com.iitsm.domain.model.IncidentStatus;
import com.iitsm.domain.model.IncidentUrgencyOrdering;
import com.iitsm.domain.model.InvestigationNote;
import com.iitsm.domain.model.ItService;
import com.iitsm.domain.model.ReassignmentRequest;
import com.iitsm.domain.model.Resolution;
import com.iitsm.domain.model.SlaStatus;
import com.iitsm.domain.model.Technician;
import com.iitsm.domain.model.TicketDisplayName;
import com.iitsm.domain.model.User;
import com.iitsm.domain.policy.AccessPolicy;
import com.iitsm.domain.repository.IncidentRepository;
import com.iitsm.domain.repository.ItServiceRepository;
import com.iitsm.domain.repository.ReassignmentRequestRepository;
import com.iitsm.domain.repository.TechnicianRepository;
import com.iitsm.domain.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service managing state machine transitions across an incident's active lifecycle.
 */
@Service
@Transactional
public class IncidentLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(IncidentLifecycleService.class);

    private final IncidentRepository incidentRepository;
    private final UserRepository userRepository;
    private final TechnicianRepository technicianRepository;
    private final ItServiceRepository itServiceRepository;
    private final ReassignmentRequestRepository reassignmentRequestRepository;
    private final DomainEventPublisher eventPublisher;
    private final AccessPolicy accessPolicy;
    private final SlaService slaService;
    private final TicketRouter ticketRouter;
    private final Clock clock;

    /**
     * Feature flag – when {@code true} the technician queue includes RESOLVED and CLOSED
     * tickets so that solved tickets remain visible after resolution.
     * Controlled via {@code iitsm.show-solved-tickets} in application.yml.
     */
    private final boolean showSolvedTickets;

    public IncidentLifecycleService(
            IncidentRepository incidentRepository,
            UserRepository userRepository,
            TechnicianRepository technicianRepository,
            ItServiceRepository itServiceRepository,
            ReassignmentRequestRepository reassignmentRequestRepository,
            DomainEventPublisher eventPublisher,
            AccessPolicy accessPolicy,
            SlaService slaService,
            TicketRouter ticketRouter,
            Clock clock) {
        this(incidentRepository, userRepository, technicianRepository, itServiceRepository,
                reassignmentRequestRepository, eventPublisher, accessPolicy, slaService, ticketRouter, clock, true);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public IncidentLifecycleService(
            IncidentRepository incidentRepository,
            UserRepository userRepository,
            TechnicianRepository technicianRepository,
            ItServiceRepository itServiceRepository,
            ReassignmentRequestRepository reassignmentRequestRepository,
            DomainEventPublisher eventPublisher,
            AccessPolicy accessPolicy,
            SlaService slaService,
            TicketRouter ticketRouter,
            Clock clock,
            @Value("${iitsm.show-solved-tickets:true}") boolean showSolvedTickets) {
        this.incidentRepository = Objects.requireNonNull(incidentRepository, "incidentRepository cannot be null");
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository cannot be null");
        this.technicianRepository = Objects.requireNonNull(technicianRepository, "technicianRepository cannot be null");
        this.itServiceRepository = Objects.requireNonNull(itServiceRepository, "itServiceRepository cannot be null");
        this.reassignmentRequestRepository = Objects.requireNonNull(reassignmentRequestRepository, "reassignmentRequestRepository cannot be null");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher cannot be null");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy cannot be null");
        this.slaService = Objects.requireNonNull(slaService, "slaService cannot be null");
        this.ticketRouter = Objects.requireNonNull(ticketRouter, "ticketRouter cannot be null");
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
        this.showSolvedTickets = showSolvedTickets;
    }

    public Incident getIncident(String actorIdOrEmail, String incidentId) {
        User actor = loadUser(actorIdOrEmail);
        Incident incident = loadIncident(incidentId);
        String owningTeamId = resolveOwningTeamId(incident);
        accessPolicy.enforceCanViewIncident(actor, incident, owningTeamId);
        return incident;
    }

    @Transactional(readOnly = true)
    public List<Incident> getMyTickets(String reporterIdOrEmail) {
        User reporter = loadUser(reporterIdOrEmail);
        return incidentRepository.findByReporterId(reporter.getId()).stream()
                .sorted(Comparator.comparing(Incident::getCreatedAt).reversed())
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<Incident> getTechnicianQueue(String technicianIdOrEmail) {
        User technician = loadUser(technicianIdOrEmail);
        // When showSolvedTickets is true (the default) all incidents assigned to the
        // technician are returned – including RESOLVED and CLOSED – so that solved
        // tickets remain visible in the UI instead of disappearing after resolution.
        // When the flag is false the historical behaviour (active tickets only) is kept.
        return incidentRepository.findByAssigneeId(technician.getId()).stream()
                .filter(i -> showSolvedTickets || i.getStatus() != IncidentStatus.CLOSED)
                .sorted(IncidentUrgencyOrdering.INSTANCE)
                .collect(Collectors.toList());
    }

    /** The team that owns the ticket: the one that handles its category (ADR-036). */
    public String resolveOwningTeamId(Incident incident) {
        if (incident == null) {
            return null;
        }
        return ticketRouter.owningTeamId(incident).orElse(null);
    }

    public Incident acknowledge(String incidentId, String technicianId) {
        User actor = loadUser(technicianId);
        Incident incident = loadIncident(incidentId);
        accessPolicy.enforceCanExecuteAction(actor, incident, IncidentAction.ACKNOWLEDGE, resolveOwningTeamId(incident));

        Instant now = clock.instant();
        SlaStatus previousSla = incident.getSlaTracker() != null ? incident.getSlaTracker().getStatus() : null;
        incident.acknowledge(actor.getId(), now);
        Incident saved = incidentRepository.save(incident);
        // A late acknowledgement is a response breach: announce it here, the scan would see "no change" (ADR-030)
        if (previousSla != null) {
            slaService.announceTransition(saved, previousSla, now);
        }

        eventPublisher.publish(new DomainEvent.IncidentAcknowledged(
                UUID.randomUUID().toString(), now, incidentId, actor.getId()));
        return saved;
    }

    public Incident startInvestigation(String incidentId, String technicianId) {
        User actor = loadUser(technicianId);
        Incident incident = loadIncident(incidentId);
        accessPolicy.enforceCanExecuteAction(actor, incident, IncidentAction.START_INVESTIGATION, resolveOwningTeamId(incident));

        Instant now = clock.instant();
        incident.startInvestigation(actor.getId(), now);
        Incident saved = incidentRepository.save(incident);

        eventPublisher.publish(new DomainEvent.InvestigationStarted(
                UUID.randomUUID().toString(), now, incidentId, actor.getId()));
        return saved;
    }

    /** The note is signed with the author's own id and name, whatever the caller names them (ADR-037). */
    public Incident addNote(String incidentId, String authorIdOrEmail, String noteText, boolean internalOnly) {
        User actor = loadUser(authorIdOrEmail);
        Incident incident = loadIncident(incidentId);
        accessPolicy.enforceCanViewIncident(actor, incident, resolveOwningTeamId(incident));

        Instant now = clock.instant();
        String noteId = "NOTE-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        incident.addNote(new InvestigationNote(noteId, actor.getId(), actor.getName(), noteText, now, internalOnly));
        return incidentRepository.save(incident);
    }

    public Incident requestInfo(String incidentId, String technicianId, String message) {
        User actor = loadUser(technicianId);
        Incident incident = loadIncident(incidentId);
        accessPolicy.enforceCanExecuteAction(actor, incident, IncidentAction.REQUEST_INFO, resolveOwningTeamId(incident));

        Instant now = clock.instant();
        incident.requestInfo(actor.getId(), message, now);
        Incident saved = incidentRepository.save(incident);

        eventPublisher.publish(new DomainEvent.MoreInfoRequested(
                UUID.randomUUID().toString(), now, incidentId, actor.getId(), message));
        return saved;
    }

    public Incident provideInfo(String incidentId, String employeeId, String responseText) {
        User actor = loadUser(employeeId);
        Incident incident = loadIncident(incidentId);
        accessPolicy.enforceCanExecuteAction(actor, incident, IncidentAction.PROVIDE_INFO, resolveOwningTeamId(incident));

        Instant now = clock.instant();
        incident.provideInfo(actor.getId(), responseText, now);
        return incidentRepository.save(incident);
    }

    public Incident resolve(String incidentId, String technicianId, Resolution resolution) {
        User actor = loadUser(technicianId);
        Incident incident = loadIncident(incidentId);
        accessPolicy.enforceCanExecuteAction(actor, incident, IncidentAction.RESOLVE, resolveOwningTeamId(incident));

        Instant now = clock.instant();
        SlaStatus previousSla = incident.getSlaTracker() != null ? incident.getSlaTracker().getStatus() : null;
        // The resolver is the signed-in actor, whatever id or e-mail the caller put in the resolution (ADR-037)
        Resolution signed = new Resolution(resolution.rootCause(), resolution.stepsTaken(), resolution.preventiveAction(),
                resolution.confirmedCategory(), actor.getId(), resolution.resolvedAt());
        incident.resolve(signed, now);
        Incident saved = incidentRepository.save(incident);
        // The verdict is set here: a late resolution, or a late response now confirmed, must be announced once (ADR-030)
        if (previousSla != null) {
            slaService.announceTransition(saved, previousSla, now);
        }

        // Free up technician capacity
        if (incident.getAssigneeId() != null) {
            technicianRepository.findById(incident.getAssigneeId()).ifPresent(tech -> {
                tech.releaseIncident(incidentId);
                technicianRepository.save(tech);
            });
        }

        eventPublisher.publish(new DomainEvent.IncidentResolved(
                UUID.randomUUID().toString(), now, incidentId, actor.getId(), signed));
        return saved;
    }

    public Incident verify(String incidentId, String employeeId) {
        User actor = loadUser(employeeId);
        Incident incident = loadIncident(incidentId);
        accessPolicy.enforceCanExecuteAction(actor, incident, IncidentAction.VERIFY, resolveOwningTeamId(incident));

        Instant now = clock.instant();
        incident.verify(actor.getId(), now);
        Incident saved = incidentRepository.save(incident);

        eventPublisher.publish(new DomainEvent.ResolutionVerified(
                UUID.randomUUID().toString(), now, incidentId, actor.getId()));
        return saved;
    }

    public Incident reject(String incidentId, String employeeId, String rejectionReason) {
        User actor = loadUser(employeeId);
        Incident incident = loadIncident(incidentId);
        accessPolicy.enforceCanExecuteAction(actor, incident, IncidentAction.REJECT, resolveOwningTeamId(incident));

        Instant now = clock.instant();
        incident.reject(actor.getId(), rejectionReason, now);
        Incident saved = incidentRepository.save(incident);

        // Re-consume technician capacity if returning to active status
        if (incident.getAssigneeId() != null) {
            technicianRepository.findById(incident.getAssigneeId()).ifPresent(tech -> {
                tech.assignIncident(incidentId);
                technicianRepository.save(tech);
            });
        }

        eventPublisher.publish(new DomainEvent.IncidentReopened(
                UUID.randomUUID().toString(), now, incidentId, actor.getId(), rejectionReason, incident.getReopenCount()));
        return saved;
    }

    public Incident close(String incidentId, String actorId, Feedback feedback) {
        User actor = loadUser(actorId);
        Incident incident = loadIncident(incidentId);
        accessPolicy.enforceCanExecuteAction(actor, incident, IncidentAction.CLOSE, resolveOwningTeamId(incident));

        Instant now = clock.instant();
        if (incident.getAssigneeId() != null) {
            technicianRepository.findById(incident.getAssigneeId()).ifPresent(tech -> {
                tech.releaseIncident(incidentId);
                technicianRepository.save(tech);
            });
        }
        incident.close(actor.getId(), feedback, now);
        Incident saved = incidentRepository.save(incident);

        eventPublisher.publish(new DomainEvent.IncidentClosed(
                UUID.randomUUID().toString(), now, incidentId, actor.getId(), feedback));
        return saved;
    }

    public ReassignmentRequest flagWrongDomain(String incidentId, String technicianId, String reason) {
        User actor = loadUser(technicianId);
        Incident incident = loadIncident(incidentId);
        accessPolicy.enforceCanExecuteAction(actor, incident, IncidentAction.FLAG_WRONG_DOMAIN, resolveOwningTeamId(incident));

        Instant now = clock.instant();
        // The slot belongs to the technician who holds the ticket; a team lead can flag it on his behalf (ADR-029).
        String holderId = incident.getAssigneeId();
        incident.flagWrongDomain(actor.getId(), reason, now);
        incident.addExcludedTechnician(actor.getId());
        incident.setNeedsTriage(true);
        incidentRepository.save(incident);

        technicianRepository.findById(holderId != null ? holderId : actor.getId()).ifPresent(tech -> {
            tech.releaseIncident(incidentId);
            technicianRepository.save(tech);
        });

        ReassignmentRequest req = new ReassignmentRequest(
                "REASSIGN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                incidentId,
                actor.getId(),
                reason,
                now
        );
        ReassignmentRequest savedReq = reassignmentRequestRepository.save(req);

        eventPublisher.publish(new DomainEvent.ReassignmentRequested(
                UUID.randomUUID().toString(), now, incidentId, actor.getId(), actor.getId(), reason));
        return savedReq;
    }

    /**
     * The tickets a staff member may see in the team view (ADR-032): an administrator sees everything; a team lead the
     * tickets of the categories the team handles (ADR-036: the category decides the team); a technician the tickets
     * assigned to them and the unassigned tickets of the team. Every ticket passes the access policy, so a colleague's
     * ticket and its notes stay out of the list.
     */
    @Transactional(readOnly = true)
    public List<Incident> getTeamQueue(String actorIdOrEmail) {
        User actor = loadUser(actorIdOrEmail);
        if (actor instanceof com.iitsm.domain.model.Administrator) {
            return incidentRepository.findAll();
        }
        if (!(actor instanceof Technician member)) {
            throw new UnauthorizedOperationException("Only technicians, team leads and administrators can view team queues");
        }
        String teamId = member.getSupportTeamId();
        java.util.Set<com.iitsm.domain.model.IncidentCategory> teamCategories = teamId == null
                ? java.util.Set.of()
                : ticketRouter.categoriesHandledBy(teamId);

        java.util.Map<String, Incident> candidates = new java.util.LinkedHashMap<>();
        incidentRepository.findByCategoryIn(teamCategories).forEach(i -> candidates.put(i.getId(), i));
        incidentRepository.findByAssigneeId(member.getId()).forEach(i -> candidates.putIfAbsent(i.getId(), i));

        return candidates.values().stream()
                .filter(i -> accessPolicy.canViewIncident(actor, i,
                        teamCategories.contains(i.getCategory()) ? teamId : resolveOwningTeamId(i)))
                .sorted(IncidentUrgencyOrdering.INSTANCE)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public IncidentService.TechnicianDashboardData getTechnicianDashboard(String technicianIdOrEmail) {
        User user = loadUser(technicianIdOrEmail);
        if (!(user instanceof Technician technician)) {
            throw new ServiceNotFoundException("Technician not found: " + technicianIdOrEmail);
        }

        List<Incident> assignedTickets = incidentRepository.findByAssigneeId(technician.getId());

        int assigned = 0, acknowledged = 0, inProgress = 0, pendingInfo = 0, resolved = 0, closed = 0, atRisk = 0;
        int activeLoad = 0;

        for (Incident inc : assignedTickets) {
            switch (inc.getStatus()) {
                case ASSIGNED -> { assigned++; activeLoad++; }
                case ACKNOWLEDGED -> { acknowledged++; activeLoad++; }
                case IN_PROGRESS -> { inProgress++; activeLoad++; }
                case PENDING_INFO -> { pendingInfo++; activeLoad++; }
                case RESOLVED -> resolved++;
                case CLOSED -> closed++;
                default -> {}
            }
            if (inc.getSlaTracker() != null) {
                SlaStatus sla = inc.getSlaTracker().getStatus();
                if (sla == SlaStatus.AT_RISK || sla == SlaStatus.WARNING) {
                    atRisk++;
                }
            }
        }

        double loadRatio = (double) activeLoad / (double) technician.getMaxCapacity();

        return new IncidentService.TechnicianDashboardData(
                technician.getId(),
                technician.getName(),
                activeLoad,
                technician.getMaxCapacity(),
                loadRatio,
                assigned,
                acknowledged,
                inProgress,
                pendingInfo,
                resolved,
                closed,
                atRisk);
    }

    private User loadUser(String userIdOrEmail) {
        if (userIdOrEmail == null || userIdOrEmail.isBlank()) {
            throw new ValidationException("userId", "User identifier cannot be blank");
        }
        if (userIdOrEmail.contains("@")) {
            return userRepository.findByEmail(new com.iitsm.domain.model.Email(userIdOrEmail.trim()))
                    .orElseThrow(() -> new ValidationException("userId", "User not found with email: " + userIdOrEmail));
        }
        return userRepository.findById(userIdOrEmail.trim())
                .orElseThrow(() -> new ValidationException("userId", "User not found with ID: " + userIdOrEmail));
    }

    /** Accepts the plain ticket id or the staff display name (priority prefix), e.g. P2-FIN-2026-000142. */
    private Incident loadIncident(String incidentId) {
        return incidentRepository.findById(TicketDisplayName.canonicalId(incidentId))
                .orElseThrow(() -> new ServiceNotFoundException("Incident not found: " + incidentId));
    }
}

