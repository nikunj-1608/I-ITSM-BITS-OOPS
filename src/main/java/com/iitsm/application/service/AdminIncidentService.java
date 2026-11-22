package com.iitsm.application.service;

import com.iitsm.application.dto.TriageQueueItemDto;
import com.iitsm.domain.event.DomainEvent;
import com.iitsm.domain.event.DomainEventPublisher;
import com.iitsm.domain.exception.NotInTriageException;
import com.iitsm.domain.exception.ServiceNotFoundException;
import com.iitsm.domain.exception.ValidationException;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.IncidentStatus;
import com.iitsm.domain.model.InvestigationNote;
import com.iitsm.domain.model.ItService;
import com.iitsm.domain.model.Priority;
import com.iitsm.domain.model.PriorityDecision;
import com.iitsm.domain.model.ReassignmentRequest;
import com.iitsm.domain.model.SlaPolicy;
import com.iitsm.domain.model.SlaStatus;
import com.iitsm.domain.model.SupportTeam;
import com.iitsm.domain.model.Technician;
import com.iitsm.domain.model.TicketDisplayName;
import com.iitsm.domain.model.User;
import com.iitsm.domain.policy.AccessPolicy;
import com.iitsm.domain.repository.IncidentRepository;
import com.iitsm.domain.repository.ItServiceRepository;
import com.iitsm.domain.repository.ReassignmentRequestRepository;
import com.iitsm.domain.repository.SlaPolicyRepository;
import com.iitsm.domain.repository.SupportTeamRepository;
import com.iitsm.domain.repository.TechnicianRepository;
import com.iitsm.domain.repository.UserRepository;
import com.iitsm.domain.strategy.assignment.AssignmentStrategy;
import com.iitsm.domain.strategy.assignment.AssignmentStrategyRegistry;
import com.iitsm.domain.strategy.assignment.RoutedCandidates;
import com.iitsm.domain.strategy.classification.ClassificationResult;
import com.iitsm.domain.strategy.classification.IncidentClassifier;
import com.iitsm.domain.strategy.priority.CompositePriorityCalculator;
import com.iitsm.domain.strategy.priority.IncidentEvaluationContext;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// OOP (Tell, Don't Ask): AdminIncidentService orchestrates the use case and tells domain objects to make the business decisions.
/**
 * Service managing administrative incident commands: assignment, priority overrides,
 * reclassification, and unified triage queue handling.
 */
@Service
@Transactional
public class AdminIncidentService {

    private static final Logger log = LoggerFactory.getLogger(AdminIncidentService.class);

    private static final String TRIAGE_LOW_CONFIDENCE = "LOW_CONFIDENCE";
    private static final String TRIAGE_WRONG_DOMAIN = "WRONG_DOMAIN";
    private static final String LOW_CONFIDENCE_REASON = "Automated intake flagged low classification confidence (< 0.60)";
    /** Shown as the confidence of a ticket the classifier could not be asked about. */
    private static final double UNKNOWN_CONFIDENCE = 0.50;

    private final IncidentRepository incidentRepository;
    private final UserRepository userRepository;
    private final TechnicianRepository technicianRepository;
    private final SlaPolicyRepository slaPolicyRepository;
    private final ItServiceRepository itServiceRepository;
    private final SupportTeamRepository supportTeamRepository;
    private final ReassignmentRequestRepository reassignmentRequestRepository;
    private final DomainEventPublisher eventPublisher;
    private final AccessPolicy accessPolicy;
    private final IncidentClassifier incidentClassifier;
    private final CompositePriorityCalculator priorityCalculator;
    private final AssignmentStrategyRegistry assignmentStrategyRegistry;
    private final TicketRouter ticketRouter;
    private final TechnicianAssignmentService technicianAssignmentService;
    private final SlaService slaService;
    private final AssignmentDecisionRecorder decisionRecorder;
    private final Clock clock;

    public AdminIncidentService(
            IncidentRepository incidentRepository,
            UserRepository userRepository,
            TechnicianRepository technicianRepository,
            SlaPolicyRepository slaPolicyRepository,
            ItServiceRepository itServiceRepository,
            SupportTeamRepository supportTeamRepository,
            ReassignmentRequestRepository reassignmentRequestRepository,
            DomainEventPublisher eventPublisher,
            AccessPolicy accessPolicy,
            IncidentClassifier incidentClassifier,
            CompositePriorityCalculator priorityCalculator,
            AssignmentStrategyRegistry assignmentStrategyRegistry,
            TicketRouter ticketRouter,
            TechnicianAssignmentService technicianAssignmentService,
            SlaService slaService,
            AssignmentDecisionRecorder decisionRecorder,
            Clock clock) {
        this.slaService = Objects.requireNonNull(slaService, "slaService cannot be null");
        this.decisionRecorder = Objects.requireNonNull(decisionRecorder, "decisionRecorder cannot be null");
        this.incidentRepository = Objects.requireNonNull(incidentRepository, "incidentRepository cannot be null");
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository cannot be null");
        this.technicianRepository = Objects.requireNonNull(technicianRepository, "technicianRepository cannot be null");
        this.slaPolicyRepository = Objects.requireNonNull(slaPolicyRepository, "slaPolicyRepository cannot be null");
        this.itServiceRepository = Objects.requireNonNull(itServiceRepository, "itServiceRepository cannot be null");
        this.supportTeamRepository = Objects.requireNonNull(supportTeamRepository, "supportTeamRepository cannot be null");
        this.reassignmentRequestRepository = Objects.requireNonNull(reassignmentRequestRepository, "reassignmentRequestRepository cannot be null");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher cannot be null");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy cannot be null");
        this.incidentClassifier = Objects.requireNonNull(incidentClassifier, "incidentClassifier cannot be null");
        this.priorityCalculator = Objects.requireNonNull(priorityCalculator, "priorityCalculator cannot be null");
        this.assignmentStrategyRegistry = Objects.requireNonNull(assignmentStrategyRegistry, "assignmentStrategyRegistry cannot be null");
        this.ticketRouter = Objects.requireNonNull(ticketRouter, "ticketRouter cannot be null");
        this.technicianAssignmentService = Objects.requireNonNull(technicianAssignmentService, "technicianAssignmentService cannot be null");
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
    }

    @Transactional(readOnly = true)
    public IncidentService.AdminStats getAdminStats() {
        long total = incidentRepository.count();
        long newCount = incidentRepository.countByStatusIn(List.of(IncidentStatus.NEW, IncidentStatus.CLASSIFIED));
        long assigned = incidentRepository.countByStatusIn(List.of(IncidentStatus.ASSIGNED, IncidentStatus.ACKNOWLEDGED));
        long inProgress = incidentRepository.countByStatusIn(List.of(IncidentStatus.IN_PROGRESS, IncidentStatus.PENDING_INFO));
        long resolved = incidentRepository.countByStatusIn(List.of(IncidentStatus.RESOLVED, IncidentStatus.VERIFIED));
        long closed = incidentRepository.countByStatusIn(List.of(IncidentStatus.CLOSED));
        long critical = incidentRepository.countByPriority(Priority.P1);

        return new IncidentService.AdminStats(total, newCount, assigned, inProgress, resolved, closed, critical);
    }

    public Incident assign(String incidentId, String adminId, String technicianId) {
        User admin = loadUser(adminId);
        accessPolicy.enforceAdmin(admin);

        Incident incident = loadIncident(incidentId);
        Technician technician = (Technician) userRepository.findById(technicianId)
                .filter(u -> u instanceof Technician)
                .orElseThrow(() -> new ServiceNotFoundException("Technician not found: " + technicianId));

        Instant now = clock.instant();
        String previousAssigneeId = incident.getAssigneeId();
        IncidentStatus status = incident.getStatus();
        if (status == IncidentStatus.ASSIGNED || status == IncidentStatus.ACKNOWLEDGED || status == IncidentStatus.IN_PROGRESS) {
            // These states release the ticket through the State Pattern (ADR-029: IN_PROGRESS can be flagged too)
            incident.flagWrongDomain(admin.getId(), "Administrative reassignment", now);
            releaseSlot(previousAssigneeId, incidentId);
        } else if (status == IncidentStatus.PENDING_INFO) {
            // A ticket waiting for the employee is not flagged by a technician (ADR-021): the administrator resumes
            // the paused SLA clock and returns it to CLASSIFIED so that the assign() below succeeds.
            if (incident.getSlaTracker() != null) {
                incident.getSlaTracker().resumeClock(now);
            }
            incident.clearAssignee();
            incident.transitionTo(IncidentStatus.CLASSIFIED);
            releaseSlot(previousAssigneeId, incidentId);
        }
        incident.setNeedsTriage(false);
        incident.clearExcludedTechnicians();
        incident.assign(technician.getId(), now);
        technician.assignIncident(incident.getId());
        technicianRepository.save(technician);
        Incident saved = incidentRepository.save(incident);
        decisionRecorder.recordManual(incidentId, technician.getId(), admin.getId(), now);

        // Resolve any pending reassignment requests for this incident
        approvePendingReassignments(incidentId, admin, now);

        eventPublisher.publish(new DomainEvent.IncidentAssigned(
                UUID.randomUUID().toString(), now, incidentId, admin.getId(), technicianId));
        return saved;
    }

    public Incident overridePriority(String incidentId, String adminId, Priority newPriority, String reason) {
        User admin = loadUser(adminId);
        accessPolicy.enforceAdmin(admin);

        Incident incident = loadIncident(incidentId);
        Priority previousPriority = incident.getPriorityDecision() != null
                ? incident.getPriorityDecision().priority()
                : Priority.P3;

        Instant now = clock.instant();
        SlaPolicy slaPolicy = slaPolicyRepository.findDefaultByPriority(newPriority)
                .orElseGet(() -> SlaPolicy.defaultFor(newPriority));
        SlaStatus previousSla = incident.getSlaTracker() != null ? incident.getSlaTracker().getStatus() : null;
        incident.overridePriority(newPriority, admin.getId(), reason, slaPolicy, now);
        Incident saved = incidentRepository.save(incident);
        if (previousSla != null) {
            slaService.announceTransition(saved, previousSla, now);
        }

        eventPublisher.publish(new DomainEvent.PriorityOverridden(
                UUID.randomUUID().toString(), now, incidentId, admin.getId(), previousPriority, newPriority, reason));
        return saved;
    }

    public Incident reclassify(String incidentId, String adminId, IncidentCategory newCategory, String serviceId, String reason) {
        User admin = loadUser(adminId);
        accessPolicy.enforceAdmin(admin);

        Incident incident = loadIncident(incidentId);
        Instant now = clock.instant();

        ItService service = findServiceOrFail(serviceId);
        PriorityDecision decision = recalculatePriority(incident, admin, service, now);
        SlaPolicy slaPolicy = slaPolicyFor(decision);
        boolean priorityApplies = !incident.isPrioritySettled();

        String owningTeamId = ticketRouter.owningTeamId(newCategory, service).orElse(null);
        List<Technician> available = availableTechnicians();

        incident.setNeedsTriage(false);
        incident.clearExcludedTechnicians();
        incident.reclassify(newCategory, (service != null) ? service.getId() : serviceId, decision, slaPolicy, now);
        Optional<String> reroutedTo = rerouteToOwningTeam(incident, admin.getId(), owningTeamId,
                ticketRouter.route(newCategory, owningTeamId, available,
                        TicketRouter.withReporter(Collections.emptySet(), incident.getReporterId())), now);
        approvePendingReassignments(incidentId, admin, now);
        Incident saved = incidentRepository.save(incident);

        eventPublisher.publish(new DomainEvent.IncidentClassified(
                UUID.randomUUID().toString(), now, incidentId, admin.getId(), newCategory, (service != null) ? service.getId() : serviceId));
        publishPriorityIfApplied(priorityApplies, incidentId, admin.getId(), decision, now);
        reroutedTo.ifPresent(techId -> eventPublisher.publish(new DomainEvent.IncidentAssigned(
                UUID.randomUUID().toString(), now, incidentId, admin.getId(), techId)));

        return saved;
    }

    /** The service with this id; null when none was sent; an unknown id is a 404 instead of being stored (audit M6). */
    private ItService findServiceOrFail(String serviceId) {
        if (serviceId == null) {
            return null;
        }
        return itServiceRepository.findById(serviceId).orElseThrow(() -> new ServiceNotFoundException(serviceId));
    }

    /** What the classifier makes of the ticket's text: the suggestion the triage queue offers for it. */
    private IncidentCategory suggestedCategory(Incident incident) {
        String description = incident.getDescription() != null ? incident.getDescription() : "";
        return incidentClassifier.classify(incident.getTitle(), description).category();
    }

    /** The admin's reclassify answers any open wrong-domain request for the ticket, as assign does. */
    private void approvePendingReassignments(String incidentId, User admin, Instant now) {
        for (ReassignmentRequest req : reassignmentRequestRepository.findByIncidentId(incidentId)) {
            if (req.getStatus() == ReassignmentRequest.Status.PENDING) {
                req.approve(admin.getId(), now);
                reassignmentRequestRepository.save(req);
            }
        }
    }

    private PriorityDecision recalculatePriority(Incident incident, User admin, ItService service, Instant now) {
        User reporter = userRepository.findById(incident.getReporterId()).orElse(admin);
        int recentTickets = countRecentUnresolvedTicketsOnService(incident.getReporterId(), incident.getServiceId(), now);
        int linkedDuplicates = incident.getLinkedIncidentIds().size();
        IncidentEvaluationContext evalContext = new IncidentEvaluationContext(recentTickets, linkedDuplicates, now);
        return priorityCalculator.calculate(incident, reporter, service, evalContext, now);
    }

    private SlaPolicy slaPolicyFor(PriorityDecision decision) {
        return slaPolicyRepository.findDefaultByPriority(decision.priority())
                .orElseGet(() -> SlaPolicy.defaultFor(decision.priority()));
    }

    /** A kept override or an earned verdict means the recalculated priority was not applied, so it is not recorded. */
    private void publishPriorityIfApplied(boolean applied, String incidentId, String adminId, PriorityDecision decision, Instant now) {
        if (applied) {
            eventPublisher.publish(new DomainEvent.PriorityCalculated(
                    UUID.randomUUID().toString(), now, incidentId, adminId, decision));
        }
    }

    /**
     * After a reclassify, an open ticket must not stay with a technician of the previous team: it is released
     * and, if the owning team has capacity, handed to one of its technicians; otherwise it waits in CLASSIFIED
     * for the dispatcher. A PENDING_INFO ticket is left alone: it is waiting for the employee with its SLA
     * clock paused, and sending it back through CLASSIFIED would leave that clock paused for good.
     */
    private Optional<String> rerouteToOwningTeam(
            Incident incident, String adminId, String owningTeamId, RoutedCandidates candidates, Instant now) {
        String currentId = incident.getAssigneeId();
        if (currentId == null || owningTeamId == null || !incident.getStatus().canBeRerouted()) {
            return Optional.empty();
        }
        Optional<Technician> current = technicianRepository.findById(currentId);
        if (current.isPresent() && owningTeamId.equalsIgnoreCase(current.get().getSupportTeamId())) {
            return Optional.empty();
        }
        incident.flagWrongDomain(adminId, "Reclassified into another team", now);
        current.ifPresent(tech -> {
            tech.releaseIncident(incident.getId());
            technicianRepository.save(tech);
        });
        AssignmentStrategy activeStrategy = assignmentStrategyRegistry.getActiveStrategy();
        Optional<String> assignedTo = technicianAssignmentService.tryAssignTechnician(
                incident, candidates, activeStrategy, adminId, now);
        assignedTo.ifPresent(techId -> incident.assign(techId, now));
        return assignedTo;
    }

    /** Looked up before the ticket is changed, so the query cannot flush half-finished changes. */
    private List<Technician> availableTechnicians() {
        return technicianRepository.findAvailableTechnicians().stream()
                .filter(Technician::hasCapacity)
                .collect(Collectors.toList());
    }

    /**
     * The triage queue: one entry per ticket that waits for an administrator's decision (ADR-029; a flagged ticket used to
     * be listed twice). A ticket a technician flagged carries that technician's reason and the request id; a ticket the
     * intake held back carries the low-confidence reason. Both carry the classifier's suggestion.
     */
    @Transactional(readOnly = true)
    public List<TriageQueueItemDto> getUnifiedTriageQueue() {
        Map<String, ReassignmentRequest> pending = newestPendingRequestPerTicket();
        Map<String, Incident> tickets = new LinkedHashMap<>();
        incidentRepository.findByNeedsTriageTrue().forEach(ticket -> tickets.put(ticket.getId(), ticket));
        pending.keySet().forEach(id -> {
            if (!tickets.containsKey(id)) {
                incidentRepository.findById(id).ifPresent(ticket -> tickets.put(id, ticket));
            }
        });
        return tickets.values().stream()
                .map(ticket -> triageItem(ticket, pending.get(ticket.getId())))
                .collect(Collectors.toList());
    }

    private Map<String, ReassignmentRequest> newestPendingRequestPerTicket() {
        Map<String, ReassignmentRequest> newest = new LinkedHashMap<>();
        reassignmentRequestRepository.findByStatus(ReassignmentRequest.Status.PENDING).stream()
                .sorted(Comparator.comparing(ReassignmentRequest::getRequestedAt))
                .forEach(request -> newest.put(request.getIncidentId(), request));
        return newest;
    }

    private TriageQueueItemDto triageItem(Incident ticket, ReassignmentRequest request) {
        boolean flagged = request != null;
        ClassificationSuggestion suggestion = suggestionFor(ticket);
        String serviceName = ticket.getServiceId() == null ? null
                : itServiceRepository.findById(ticket.getServiceId()).map(ItService::getName).orElse(null);
        return new TriageQueueItemDto(
                flagged ? TRIAGE_WRONG_DOMAIN : TRIAGE_LOW_CONFIDENCE,
                ticket.getId(),
                TicketDisplayName.of(ticket.getPriorityDecision() != null ? ticket.getPriorityDecision().priority() : null, ticket.getId()),
                ticket.getTitle(),
                ticket.getDescription(),
                ticket.getCategory() != null ? ticket.getCategory().name() : null,
                ticket.getServiceId(),
                serviceName,
                suggestion.confidence(),
                suggestion.categories(),
                flagged ? request.getRequestingTechnicianId() : null,
                flagged ? request.getReason() : LOW_CONFIDENCE_REASON,
                flagged ? request.getRequestedAt() : ticket.getCreatedAt(),
                flagged ? request.getId() : null);
    }

    private record ClassificationSuggestion(double confidence, List<TriageQueueItemDto.CategorySuggestionDto> categories) {}

    private ClassificationSuggestion suggestionFor(Incident ticket) {
        try {
            ClassificationResult prediction = incidentClassifier.classify(
                    ticket.getTitle(), ticket.getDescription() != null ? ticket.getDescription() : "");
            if (prediction != null) {
                return new ClassificationSuggestion(prediction.confidence(), List.of(
                        new TriageQueueItemDto.CategorySuggestionDto(prediction.category().name(), prediction.confidence())));
            }
        } catch (RuntimeException failure) {
            log.warn("The classifier could not suggest a category for ticket {}: {}", ticket.getId(), failure.getMessage());
        }
        return new ClassificationSuggestion(UNKNOWN_CONFIDENCE, List.of());
    }

    /**
     * Rejects a technician's wrong-domain request: the ticket goes back to that technician (the administrator decided it
     * belongs to the team after all), the request is closed as REJECTED and the reason is recorded as a note on the ticket
     * for the technician (ADR-029, decision D8c). All in one transaction: when the technician has no free capacity the
     * whole call fails (HTTP 409) and nothing changes.
     */
    public Incident rejectReassignment(String requestId, String adminId, String reason) {
        User admin = loadUser(adminId);
        accessPolicy.enforceAdmin(admin);
        if (reason == null || reason.isBlank()) {
            throw new ValidationException("reason", "a reason for rejecting the request is required");
        }
        ReassignmentRequest request = reassignmentRequestRepository.findById(requestId)
                .orElseThrow(() -> new ServiceNotFoundException("Reassignment request not found: " + requestId));
        Instant now = clock.instant();
        request.reject(admin.getId(), now);

        Incident incident = loadIncident(request.getIncidentId());
        Technician technician = technicianRepository.findById(request.getRequestingTechnicianId())
                .orElseThrow(() -> new ServiceNotFoundException("Technician not found: " + request.getRequestingTechnicianId()));
        incident.removeExcludedTechnician(technician.getId());
        incident.setNeedsTriage(false);
        incident.assign(technician.getId(), now);
        technician.assignIncident(incident.getId());
        technicianRepository.save(technician);
        incident.addNote(new InvestigationNote("NOTE-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                admin.getId(), admin.getName(), "Reassignment request rejected by the administrator: " + reason.trim(), now, true));
        reassignmentRequestRepository.save(request);
        Incident saved = incidentRepository.save(incident);
        decisionRecorder.recordRequeued(saved.getId(), technician.getId(), admin.getId(), now);

        eventPublisher.publish(new DomainEvent.IncidentAssigned(
                UUID.randomUUID().toString(), now, saved.getId(), admin.getId(), technician.getId()));
        return saved;
    }

    private void releaseSlot(String technicianId, String incidentId) {
        if (technicianId != null) {
            technicianRepository.findById(technicianId).ifPresent(tech -> {
                tech.releaseIncident(incidentId);
                technicianRepository.save(tech);
            });
        }
    }

    /** A ticket is in triage while the intake or a technician's flag holds it back for a human decision. */
    private boolean isInTriage(Incident incident) {
        return incident.isNeedsTriage() || reassignmentRequestRepository.findByIncidentId(incident.getId()).stream()
                .anyMatch(ReassignmentRequest::isPending);
    }

    /**
     * Accepts the suggested classification of a ticket in triage. {@code requestedCategory} may be null, which means
     * "the classifier's suggestion" (the same one the triage queue shows); an explicit category always wins.
     */
    public Incident acceptTriageSuggestion(String incidentId, String adminId, IncidentCategory requestedCategory, String serviceId) {
        User admin = loadUser(adminId);
        accessPolicy.enforceAdmin(admin);

        Incident incident = loadIncident(incidentId);
        if (!isInTriage(incident)) {
            throw new NotInTriageException(incident.getId(), incident.getStatus());
        }
        Instant now = clock.instant();

        IncidentCategory newCategory = (requestedCategory != null) ? requestedCategory : suggestedCategory(incident);
        ItService service = findServiceOrFail(serviceId);
        PriorityDecision decision = recalculatePriority(incident, admin, service, now);
        SlaPolicy slaPolicy = slaPolicyFor(decision);
        boolean priorityApplies = !incident.isPrioritySettled();

        incident.setNeedsTriage(false);
        incident.clearExcludedTechnicians();
        incident.reclassify(newCategory, (service != null) ? service.getId()
                : (incident.getServiceId() != null ? incident.getServiceId() : ItService.GENERAL_SERVICE_ID), decision, slaPolicy, now);

        // Resolve any pending reassignment requests for this incident
        approvePendingReassignments(incidentId, admin, now);

        // Attempt assignment: the team of the new category, skilled overflow, otherwise the ticket waits (ADR-036)
        RoutedCandidates candidates = ticketRouter.route(incident, availableTechnicians());
        if (!candidates.isEmpty()) {
            // The slot and the decision are saved in this transaction (ADR-026, ADR-044). If the ticket cannot be
            // assigned (it already has a technician) the whole call fails and both roll back; nothing is swallowed here.
            AssignmentStrategy activeStrategy = assignmentStrategyRegistry.getActiveStrategy();
            Optional<String> assignedId = technicianAssignmentService.tryAssignTechnician(
                    incident, candidates, activeStrategy, admin.getId(), now);
            assignedId.ifPresent(techId -> incident.assign(techId, now));
        }

        Incident saved = incidentRepository.save(incident);

        eventPublisher.publish(new DomainEvent.IncidentClassified(
                UUID.randomUUID().toString(), now, incidentId, admin.getId(), newCategory, incident.getServiceId()));
        publishPriorityIfApplied(priorityApplies, incidentId, admin.getId(), decision, now);
        if (saved.getAssigneeId() != null) {
            eventPublisher.publish(new DomainEvent.IncidentAssigned(
                    UUID.randomUUID().toString(), now, incidentId, admin.getId(), saved.getAssigneeId()));
        }

        return saved;
    }

    @Transactional(readOnly = true)
    public List<ReassignmentRequest> getPendingReassignments() {
        return reassignmentRequestRepository.findByStatus(ReassignmentRequest.Status.PENDING);
    }

    private int countRecentUnresolvedTicketsOnService(String reporterId, String serviceId, Instant now) {
        if (reporterId == null || serviceId == null) {
            return 0;
        }
        Instant cutoff = now.minus(java.time.Duration.ofDays(30));
        return (int) incidentRepository.findByReporterId(reporterId).stream()
                .filter(i -> Objects.equals(i.getServiceId(), serviceId))
                .filter(i -> i.getStatus() != IncidentStatus.RESOLVED && i.getStatus() != IncidentStatus.CLOSED)
                .filter(i -> i.getCreatedAt().isAfter(cutoff))
                .count();
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

