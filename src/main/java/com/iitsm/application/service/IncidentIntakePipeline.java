package com.iitsm.application.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.iitsm.domain.event.DomainEvent;
import com.iitsm.domain.event.DomainEventPublisher;
import com.iitsm.domain.exception.ValidationException;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.ItService;
import com.iitsm.domain.model.Priority;
import com.iitsm.domain.model.PriorityDecision;
import com.iitsm.domain.model.SlaPolicy;
import com.iitsm.domain.model.User;
import com.iitsm.domain.repository.IncidentRepository;
import com.iitsm.domain.repository.ItServiceRepository;
import com.iitsm.domain.repository.SlaPolicyRepository;
import com.iitsm.domain.repository.UserRepository;
import com.iitsm.domain.strategy.classification.ClassificationResult;
import com.iitsm.domain.strategy.classification.IncidentClassifier;
import com.iitsm.domain.strategy.classification.ServiceCorroboration;
import com.iitsm.domain.strategy.classification.ServiceMatcher;
import com.iitsm.domain.strategy.priority.CompositePriorityCalculator;
import com.iitsm.domain.strategy.priority.IncidentEvaluationContext;

/**
 * Pipeline coordinating incident intake, ML classification, service matching,
 * duplicate correlation and multi-factor priority determination.
 *
 * <p>It does not assign a technician (ADR-026): the ticket is saved first, and the result says whether the ticket now
 * awaits automatic assignment. {@code IncidentService.createIncident} then assigns it in a second transaction
 * ({@link TicketDispatchService}), so a failed assignment can never lose the reporter's ticket and the technician's
 * capacity slot is saved together with the assignee. A duplicate that rides its parent's technician (F4) is the one
 * assignment made here, because it takes no slot.
 */
@Service
@Transactional
public class IncidentIntakePipeline {

    private static final Logger log = LoggerFactory.getLogger(IncidentIntakePipeline.class);
    /** The actor of automatic intake steps. */
    private static final String SYSTEM_ACTOR = "SYSTEM";

    /**
     * @param incident                    the saved ticket
     * @param awaitingAutomaticAssignment true for a confident ticket that is not a duplicate: it still needs a technician
     */
    public record IntakeResult(Incident incident, boolean awaitingAutomaticAssignment) {}

    private final IncidentRepository incidentRepository;
    private final UserRepository userRepository;
    private final SlaPolicyRepository slaPolicyRepository;
    private final ItServiceRepository itServiceRepository;
    private final DomainEventPublisher eventPublisher;
    private final IncidentFactory incidentFactory;
    private final IncidentClassifier incidentClassifier;
    private final ServiceMatcher serviceMatcher;
    private final TicketRouter ticketRouter;
    private final CompositePriorityCalculator priorityCalculator;
    private final DuplicateDetectionService duplicateDetectionService;
    private final AssignmentDecisionRecorder decisionRecorder;
    private final Clock clock;

    @Value("${iitsm.classification.min-confidence:0.60}")
    private double minConfidence = 0.60;

    public IncidentIntakePipeline(
            IncidentRepository incidentRepository,
            UserRepository userRepository,
            SlaPolicyRepository slaPolicyRepository,
            ItServiceRepository itServiceRepository,
            DomainEventPublisher eventPublisher,
            IncidentFactory incidentFactory,
            IncidentClassifier incidentClassifier,
            ServiceMatcher serviceMatcher,
            TicketRouter ticketRouter,
            CompositePriorityCalculator priorityCalculator,
            DuplicateDetectionService duplicateDetectionService,
            AssignmentDecisionRecorder decisionRecorder,
            Clock clock) {
        this.incidentRepository = Objects.requireNonNull(incidentRepository, "incidentRepository cannot be null");
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository cannot be null");
        this.slaPolicyRepository = Objects.requireNonNull(slaPolicyRepository, "slaPolicyRepository cannot be null");
        this.itServiceRepository = Objects.requireNonNull(itServiceRepository, "itServiceRepository cannot be null");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher cannot be null");
        this.incidentFactory = Objects.requireNonNull(incidentFactory, "incidentFactory cannot be null");
        this.incidentClassifier = Objects.requireNonNull(incidentClassifier, "incidentClassifier cannot be null");
        this.serviceMatcher = Objects.requireNonNull(serviceMatcher, "serviceMatcher cannot be null");
        this.ticketRouter = Objects.requireNonNull(ticketRouter, "ticketRouter cannot be null");
        this.priorityCalculator = Objects.requireNonNull(priorityCalculator, "priorityCalculator cannot be null");
        this.duplicateDetectionService = Objects.requireNonNull(duplicateDetectionService, "duplicateDetectionService cannot be null");
        this.decisionRecorder = Objects.requireNonNull(decisionRecorder, "decisionRecorder cannot be null");
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
    }

    public IntakeResult processIntake(String reporterIdOrEmail, String title, String description) {
        User reporter = loadUser(reporterIdOrEmail);

        Incident incident = incidentFactory.createIncident(title, description, reporter);
        Instant now = clock.instant();

        // Step 1: Natural Language & ML Automated Classification
        ClassificationResult classResult = incidentClassifier.classify(title, description);

        // Step 2: IT Service Identification. A matched service owned by the team of the classified category is a second,
        // independent signal for a ticket the keywords support (ADR-052, Phase 5).
        List<ItService> services = itServiceRepository.findAll();
        Optional<ItService> matchedService = serviceMatcher.findBestMatch(title, description, services);
        classResult = ServiceCorroboration.apply(
                classResult, matchedService, ticketRouter.owningTeamId(classResult.category(), null));
        IncidentCategory category = classResult.category();
        ItService service = matchedService.orElseGet(() ->
                itServiceRepository.findById(ItService.GENERAL_SERVICE_ID)
                        .or(() -> services.stream().filter(s -> s.getName().toLowerCase().contains("general") || s.getName().toLowerCase().contains("unclassified")).findFirst())
                        .orElse(null));
        String serviceId = (service != null) ? service.getId() : (services.isEmpty() ? null : services.getFirst().getId());
        incident.setCategory(category);
        incident.setServiceId(serviceId);

        // Step 3: Duplicate Detection and Correlation
        Optional<DuplicateDetectionService.DuplicateMatch> duplicateMatch = duplicateDetectionService.checkAndLinkDuplicate(incident);
        boolean isDuplicate = duplicateMatch.isPresent();

        // Step 4: Multi-Factor Priority Calculation (6 factors + Policy Override Rules)
        int recentTickets = countRecentUnresolvedTicketsOnService(reporter.getId(), serviceId, now);
        int linkedDuplicates = isDuplicate ? 0 : incident.getLinkedIncidentIds().size();
        IncidentEvaluationContext evalContext = new IncidentEvaluationContext(recentTickets, linkedDuplicates, now);

        PriorityDecision decision = priorityCalculator.calculate(incident, reporter, service, evalContext, now);
        Priority priority = decision.priority();

        // Step 5: Attach SLA Policy matching calculated priority
        SlaPolicy slaPolicy = slaPolicyRepository.findDefaultByPriority(priority)
                .orElseGet(() -> SlaPolicy.defaultFor(priority));

        incident.classify(category, serviceId, decision, slaPolicy, now);

        // Step 6: Cluster linkage and the confidence gate. The gate applies to every ticket, duplicates included
        // (ADR-009, ADR-020): humans own low-confidence tickets, so they never inherit an assignee or reach the dispatcher.
        boolean confident = classResult.confidence() >= minConfidence;
        String inheritedFrom = null;
        if (isDuplicate) {
            inheritedFrom = linkToParentCluster(incident, duplicateMatch.get().parentIncidentId(), confident, now);
        }
        if (!confident) {
            routeToTriage(incident, classResult.confidence());
        }
        // A confident ticket that is not a duplicate gets its technician after this transaction commits (ADR-026).
        boolean awaitingAssignment = confident && !isDuplicate;

        Incident saved = incidentRepository.save(incident);
        if (inheritedFrom != null) {
            // Saved after the ticket itself: the decision refers to it (ADR-044)
            decisionRecorder.recordInheritedFromParent(saved.getId(), saved.getAssigneeId(), inheritedFrom, SYSTEM_ACTOR, now);
        }

        // Publish event spine
        eventPublisher.publish(new DomainEvent.IncidentCreated(
                UUID.randomUUID().toString(), now, saved.getId(), reporter.getId(), title, description));
        eventPublisher.publish(new DomainEvent.IncidentClassified(
                UUID.randomUUID().toString(), now, saved.getId(), "SYSTEM", category, serviceId));
        eventPublisher.publish(new DomainEvent.PriorityCalculated(
                UUID.randomUUID().toString(), now, saved.getId(), "SYSTEM", decision));

        if (saved.getAssigneeId() != null) {
            eventPublisher.publish(new DomainEvent.IncidentAssigned(
                    UUID.randomUUID().toString(), now, saved.getId(), "SYSTEM", saved.getAssigneeId()));
        }

        log.info("Incident {} created with category {}, priority {} ({}), assignee {}, awaiting automatic assignment: {}",
                saved.getId(), category, priority, decision.compositeScore(), saved.getAssigneeId(), awaitingAssignment);
        return new IntakeResult(saved, awaitingAssignment);
    }

    /**
     * Recomputes the cluster root's priority; a confident duplicate also rides the root's technician (F4).
     *
     * @return the id of the parent whose technician the duplicate took, or null when it took none
     */
    private String linkToParentCluster(Incident incident, String parentId, boolean inheritAssignee, Instant now) {
        Incident rootParent = incidentRepository.findById(parentId).orElse(null);
        if (rootParent == null) {
            return null;
        }
        String inheritedFrom = null;
        if (inheritAssignee && rootParent.getAssigneeId() != null) {
            incident.assign(rootParent.getAssigneeId(), now);
            inheritedFrom = rootParent.getId();
        }
        refreshParentPriority(rootParent, now);
        return inheritedFrom;
    }

    /** Recalculates the parent ticket's priority with its updated cluster size and publishes the change. */
    private void refreshParentPriority(Incident rootParent, Instant now) {
        User parentReporter = loadUser(rootParent.getReporterId());
        ItService parentService = rootParent.getServiceId() != null
                ? itServiceRepository.findById(rootParent.getServiceId()).orElse(null)
                : null;
        int parentRecent = countRecentUnresolvedTicketsOnService(parentReporter.getId(), rootParent.getServiceId(), now);
        int parentDuplicates = rootParent.getLinkedIncidentIds().size();
        IncidentEvaluationContext parentContext = new IncidentEvaluationContext(parentRecent, parentDuplicates, now);

        PriorityDecision parentDecision = priorityCalculator.calculate(rootParent, parentReporter, parentService, parentContext, now);
        SlaPolicy parentSla = slaPolicyRepository.findDefaultByPriority(parentDecision.priority())
                .orElseGet(() -> SlaPolicy.defaultFor(parentDecision.priority()));
        rootParent.updatePriorityDecision(parentDecision, parentSla, now);
        incidentRepository.save(rootParent);

        eventPublisher.publish(new DomainEvent.PriorityCalculated(
                UUID.randomUUID().toString(), now, rootParent.getId(), "SYSTEM", parentDecision));
    }

    private void routeToTriage(Incident incident, double confidence) {
        incident.setNeedsTriage(true);
        log.info("Incident {} classification confidence {} is below threshold {}; leaving unassigned in triage queue",
                incident.getId(), confidence, minConfidence);
    }

    private int countRecentUnresolvedTicketsOnService(String reporterId, String serviceId, Instant now) {
        if (reporterId == null || serviceId == null) {
            return 0;
        }
        Instant cutoff = now.minus(java.time.Duration.ofDays(30));
        return (int) incidentRepository.findByReporterId(reporterId).stream()
                .filter(i -> Objects.equals(i.getServiceId(), serviceId))
                .filter(i -> i.getStatus() != com.iitsm.domain.model.IncidentStatus.RESOLVED && i.getStatus() != com.iitsm.domain.model.IncidentStatus.CLOSED)
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
}

