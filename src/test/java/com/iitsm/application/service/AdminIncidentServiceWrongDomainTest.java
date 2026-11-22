package com.iitsm.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.iitsm.application.dto.TriageQueueItemDto;
import com.iitsm.domain.event.DomainEvent;
import com.iitsm.domain.event.DomainEventPublisher;
import com.iitsm.domain.exception.NotInTriageException;
import com.iitsm.domain.exception.ReassignmentRequestNotPendingException;
import com.iitsm.domain.exception.ServiceNotFoundException;
import com.iitsm.domain.exception.TechnicianOverloadedException;
import com.iitsm.domain.exception.ValidationException;
import com.iitsm.domain.model.AdminLevel;
import com.iitsm.domain.model.Administrator;
import com.iitsm.domain.model.Email;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.IncidentStatus;
import com.iitsm.domain.model.Priority;
import com.iitsm.domain.model.PriorityDecision;
import com.iitsm.domain.model.ReassignmentRequest;
import com.iitsm.domain.model.Skill;
import com.iitsm.domain.model.SlaPolicy;
import com.iitsm.domain.model.Technician;
import com.iitsm.domain.policy.AccessPolicy;
import com.iitsm.domain.repository.IncidentRepository;
import com.iitsm.domain.repository.ItServiceRepository;
import com.iitsm.domain.repository.ReassignmentRequestRepository;
import com.iitsm.domain.repository.SlaPolicyRepository;
import com.iitsm.domain.repository.SupportTeamRepository;
import com.iitsm.domain.repository.TechnicianRepository;
import com.iitsm.domain.repository.UserRepository;
import com.iitsm.domain.strategy.assignment.AssignmentStrategyRegistry;
import com.iitsm.domain.strategy.classification.ClassificationResult;
import com.iitsm.domain.strategy.classification.IncidentClassifier;
import com.iitsm.domain.strategy.priority.CompositePriorityCalculator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Phase C2 (ADR-029): rejecting a reassignment request, one triage entry per ticket, and who may accept a suggestion. */
class AdminIncidentServiceWrongDomainTest {

    private static final Instant T0 = Instant.parse("2026-11-05T09:00:00Z");
    private static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");
    private static final String ADMIN_ID = "ADMIN-SYS-01";
    private static final String TECHNICIAN_ID = "TECH-NET-1";
    private static final String TICKET = "FIN-2026-000007";
    private static final String REQUEST_ID = "REASSIGN-AAAA1111";

    private IncidentRepository incidentRepository;
    private TechnicianRepository technicianRepository;
    private ReassignmentRequestRepository requestRepository;
    private IncidentClassifier classifier;
    private DomainEventPublisher eventPublisher;
    private AdminIncidentService service;
    private Technician technician;
    private Incident flagged;
    private ReassignmentRequest pending;

    @BeforeEach
    void setUp() {
        incidentRepository = mock(IncidentRepository.class);
        technicianRepository = mock(TechnicianRepository.class);
        requestRepository = mock(ReassignmentRequestRepository.class);
        classifier = mock(IncidentClassifier.class);
        eventPublisher = mock(DomainEventPublisher.class);
        UserRepository userRepository = mock(UserRepository.class);
        CompositePriorityCalculator calculator = mock(CompositePriorityCalculator.class);
        when(calculator.calculate(any(), any(), any(), any(), any()))
                .thenReturn(new PriorityDecision(Priority.P3, 0.4, List.of(), List.of(), NOW, null, null));
        service = new AdminIncidentService(incidentRepository, userRepository, technicianRepository,
                mock(SlaPolicyRepository.class), mock(ItServiceRepository.class), mock(SupportTeamRepository.class),
                requestRepository, eventPublisher, mock(AccessPolicy.class), classifier, calculator,
                mock(AssignmentStrategyRegistry.class), routerWithNobodyFree(), mock(TechnicianAssignmentService.class),
                mock(SlaService.class), mock(AssignmentDecisionRecorder.class), Clock.fixed(NOW, ZoneOffset.UTC));
        when(userRepository.findById(ADMIN_ID)).thenReturn(Optional.of(
                new Administrator(ADMIN_ID, "Chief Admin", new Email("admin@test.com"), "hash", AdminLevel.SYSTEM_ADMIN)));

        technician = new Technician(TECHNICIAN_ID, "David Kim", new Email("david@test.com"), "hash",
                Set.of(new Skill("BGP", IncidentCategory.NETWORK)), 5, 2, "TEAM-NET");
        flagged = classifiedTicket(TICKET, T0);
        flagged.addExcludedTechnician(TECHNICIAN_ID);
        flagged.setNeedsTriage(true);
        pending = new ReassignmentRequest(REQUEST_ID, TICKET, TECHNICIAN_ID, "This is a database fault", T0);

        when(technicianRepository.findById(TECHNICIAN_ID)).thenReturn(Optional.of(technician));
        when(incidentRepository.findById(TICKET)).thenReturn(Optional.of(flagged));
        when(incidentRepository.save(any(Incident.class))).thenAnswer(call -> call.getArgument(0));
        when(requestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(pending));
        when(requestRepository.findByIncidentId(TICKET)).thenReturn(List.of(pending));
        when(requestRepository.findByStatus(ReassignmentRequest.Status.PENDING)).thenReturn(List.of(pending));
        when(incidentRepository.findByNeedsTriageTrue()).thenReturn(List.of(flagged));
        when(classifier.classify(any(), any())).thenReturn(ClassificationResult.of(IncidentCategory.NETWORK, 0.62, "TEST", List.of()));
    }

    private static Incident classifiedTicket(String id, Instant createdAt) {
        Incident incident = new Incident(id, "VPN keeps dropping", "The VPN client drops every hour for all of us.", "EMP-1", createdAt);
        incident.classify(IncidentCategory.NETWORK, "SVC-VPN",
                new PriorityDecision(Priority.P3, 0.4, List.of(), List.of(), createdAt, null, null),
                SlaPolicy.defaultFor(Priority.P3), createdAt);
        return incident;
    }

    // ---- reject ---------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Reject gives the ticket back to the technician, takes his slot, closes the request and records the reason")
    void rejectGivesTheTicketBack() {
        Incident saved = service.rejectReassignment(REQUEST_ID, ADMIN_ID, "  It is a VPN problem, carry on  ");

        assertThat(saved.getStatus()).isEqualTo(IncidentStatus.ASSIGNED);
        assertThat(saved.getAssigneeId()).isEqualTo(TECHNICIAN_ID);
        assertThat(saved.isNeedsTriage()).isFalse();
        assertThat(saved.getExcludedTechnicianIds()).doesNotContain(TECHNICIAN_ID);
        assertThat(technician.getActiveIncidentIds()).containsExactly(TICKET);
        assertThat(pending.getStatus()).isEqualTo(ReassignmentRequest.Status.REJECTED);
        assertThat(pending.getReviewedByAdminId()).isEqualTo(ADMIN_ID);
        assertThat(saved.getNotes()).singleElement().satisfies(note -> {
            assertThat(note.authorId()).isEqualTo(ADMIN_ID);
            assertThat(note.authorName()).isEqualTo("Chief Admin");
            assertThat(note.noteText()).endsWith("It is a VPN problem, carry on");
            assertThat(note.internalOnly()).isTrue();
        });
        verify(technicianRepository).save(technician);
        verify(requestRepository).save(pending);
        ArgumentCaptor<DomainEvent> event = ArgumentCaptor.forClass(DomainEvent.class);
        verify(eventPublisher).publish(event.capture());
        assertThat(event.getValue()).isInstanceOfSatisfying(DomainEvent.IncidentAssigned.class, assigned -> {
            assertThat(assigned.actorId()).isEqualTo(ADMIN_ID);
            assertThat(assigned.technicianId()).isEqualTo(TECHNICIAN_ID);
        });
    }

    @Test
    @DisplayName("A request that was already decided is refused before anything changes")
    void decidedRequestIsRefused() {
        pending.approve("ADMIN-OTHER", T0);

        assertThatThrownBy(() -> service.rejectReassignment(REQUEST_ID, ADMIN_ID, "carry on"))
                .isInstanceOf(ReassignmentRequestNotPendingException.class);

        assertThat(flagged.getStatus()).isEqualTo(IncidentStatus.CLASSIFIED);
        verify(technicianRepository, never()).save(any());
        verify(incidentRepository, never()).save(any());
    }

    @Test
    @DisplayName("An unknown request is not found and a blank reason is a validation error")
    void unknownRequestAndBlankReason() {
        assertThatThrownBy(() -> service.rejectReassignment("REASSIGN-NOSUCH", ADMIN_ID, "carry on"))
                .isInstanceOf(ServiceNotFoundException.class);
        when(requestRepository.findById("REASSIGN-NOSUCH")).thenReturn(Optional.empty());
        for (String blank : new String[] {null, "", "   "}) {
            assertThatThrownBy(() -> service.rejectReassignment(REQUEST_ID, ADMIN_ID, blank)).isInstanceOf(ValidationException.class);
        }
        assertThat(pending.isPending()).as("a refused call decides nothing").isTrue();
    }

    @Test
    @DisplayName("A technician without free capacity cannot take the ticket back: nothing is saved")
    void fullTechnicianIsRefused() {
        technician.assignIncident("INC-A");
        technician.assignIncident("INC-B");

        assertThatThrownBy(() -> service.rejectReassignment(REQUEST_ID, ADMIN_ID, "carry on"))
                .isInstanceOf(TechnicianOverloadedException.class);

        verify(technicianRepository, never()).save(any());
        verify(incidentRepository, never()).save(any());
        verify(requestRepository, never()).save(any());
    }

    // ---- one triage entry per ticket -----------------------------------------------------------------------------------

    @Test
    @DisplayName("A flagged ticket is one WRONG_DOMAIN entry with the technician, the reason, the request id and the suggestion")
    void flaggedTicketIsOneEntry() {
        List<TriageQueueItemDto> queue = service.getUnifiedTriageQueue();

        assertThat(queue).singleElement().satisfies(item -> {
            assertThat(item.triageType()).isEqualTo("WRONG_DOMAIN");
            assertThat(item.incidentId()).isEqualTo(TICKET);
            assertThat(item.requestingTechnicianId()).isEqualTo(TECHNICIAN_ID);
            assertThat(item.reason()).isEqualTo("This is a database fault");
            assertThat(item.requestId()).isEqualTo(REQUEST_ID);
            assertThat(item.createdAt()).isEqualTo(T0);
            assertThat(item.suggestions()).singleElement().satisfies(s -> assertThat(s.category()).isEqualTo("NETWORK"));
        });
    }

    @Test
    @DisplayName("A ticket held back for low confidence keeps its own entry, without a technician or a request")
    void lowConfidenceTicketKeepsItsEntry() {
        Incident vague = classifiedTicket("FIN-2026-000008", T0);
        vague.setNeedsTriage(true);
        when(incidentRepository.findByNeedsTriageTrue()).thenReturn(List.of(flagged, vague));

        List<TriageQueueItemDto> queue = service.getUnifiedTriageQueue();

        assertThat(queue).hasSize(2);
        assertThat(queue.get(1)).satisfies(item -> {
            assertThat(item.triageType()).isEqualTo("LOW_CONFIDENCE");
            assertThat(item.requestingTechnicianId()).isNull();
            assertThat(item.requestId()).isNull();
            assertThat(item.createdAt()).isEqualTo(T0);
        });
    }

    @Test
    @DisplayName("A pending request is listed even when its ticket is not flagged for triage, and the newest of two requests wins")
    void pendingRequestWithoutTriageFlagAndTwoRequests() {
        flagged.setNeedsTriage(false);
        ReassignmentRequest newer = new ReassignmentRequest("REASSIGN-BBBB2222", TICKET, "TECH-NET-2", "second flag", T0.plus(Duration.ofMinutes(5)));
        when(incidentRepository.findByNeedsTriageTrue()).thenReturn(List.of());
        when(requestRepository.findByStatus(ReassignmentRequest.Status.PENDING)).thenReturn(List.of(newer, pending));

        List<TriageQueueItemDto> queue = service.getUnifiedTriageQueue();

        assertThat(queue).singleElement().satisfies(item -> {
            assertThat(item.requestId()).isEqualTo("REASSIGN-BBBB2222");
            assertThat(item.requestingTechnicianId()).isEqualTo("TECH-NET-2");
        });
    }

    @Test
    @DisplayName("Phase J: each triage entry carries the staff display name (priority and id) for the card")
    void triageEntryCarriesTheDisplayName() {
        assertThat(service.getUnifiedTriageQueue()).singleElement()
                .satisfies(item -> assertThat(item.displayId()).isEqualTo("P3-" + TICKET));
    }

    @Test
    @DisplayName("A classifier failure does not break the queue: the entry is listed without a suggestion")
    void classifierFailureIsTolerated() {
        when(classifier.classify(any(), any())).thenThrow(new IllegalStateException("model not loaded"));

        assertThat(service.getUnifiedTriageQueue()).singleElement().satisfies(item -> {
            assertThat(item.suggestions()).isEmpty();
            assertThat(item.confidence()).isEqualTo(0.50);
        });
    }

    // ---- accept-suggestion: only a ticket in triage ----------------------------------------------------------------------

    @Test
    @DisplayName("Accept-suggestion is refused for a ticket that is neither held for triage nor has a pending request")
    void acceptSuggestionNeedsTriage() {
        flagged.setNeedsTriage(false);
        when(requestRepository.findByIncidentId(TICKET)).thenReturn(List.of());

        assertThatThrownBy(() -> service.acceptTriageSuggestion(TICKET, ADMIN_ID, IncidentCategory.NETWORK, null))
                .isInstanceOf(NotInTriageException.class).hasMessageContaining(TICKET).hasMessageContaining("CLASSIFIED");

        verify(incidentRepository, never()).save(any());
    }

    @Test
    @DisplayName("A pending request alone is enough to be in triage, even when the triage flag is off: the suggestion is accepted and the request approved")
    void pendingRequestMakesATicketEligible() {
        flagged.setNeedsTriage(false);

        Incident saved = service.acceptTriageSuggestion(TICKET, ADMIN_ID, IncidentCategory.NETWORK, null);

        assertThat(saved.getCategory()).isEqualTo(IncidentCategory.NETWORK);
        assertThat(pending.getStatus()).isEqualTo(ReassignmentRequest.Status.APPROVED);
        assertThat(pending.getReviewedByAdminId()).isEqualTo(ADMIN_ID);
    }

    /** A mocked router that offers nobody (Mockito would answer null for the RoutedCandidates of ADR-044). */
    private static TicketRouter routerWithNobodyFree() {
        return org.mockito.Mockito.mock(TicketRouter.class, invocation ->
                invocation.getMethod().getReturnType() == com.iitsm.domain.strategy.assignment.RoutedCandidates.class
                        ? com.iitsm.domain.strategy.assignment.RoutedCandidates.none()
                        : org.mockito.Mockito.RETURNS_DEFAULTS.answer(invocation));
    }
}
