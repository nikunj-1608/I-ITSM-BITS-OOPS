package com.iitsm.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.iitsm.domain.event.DomainEvent;
import com.iitsm.domain.event.DomainEventPublisher;
import com.iitsm.domain.exception.ServiceNotFoundException;
import com.iitsm.domain.exception.UnauthorizedOperationException;
import com.iitsm.domain.exception.ValidationException;
import com.iitsm.domain.model.Administrator;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.Priority;
import com.iitsm.domain.model.PriorityDecision;
import com.iitsm.domain.model.SlaPolicy;
import com.iitsm.domain.model.SlaStatus;
import com.iitsm.domain.policy.AccessPolicy;
import com.iitsm.domain.repository.IncidentRepository;
import com.iitsm.domain.repository.ItServiceRepository;
import com.iitsm.domain.repository.ReassignmentRequestRepository;
import com.iitsm.domain.repository.SlaPolicyRepository;
import com.iitsm.domain.repository.SupportTeamRepository;
import com.iitsm.domain.repository.TechnicianRepository;
import com.iitsm.domain.repository.UserRepository;
import com.iitsm.domain.strategy.assignment.AssignmentStrategyRegistry;
import com.iitsm.domain.strategy.classification.IncidentClassifier;
import com.iitsm.domain.strategy.priority.CompositePriorityCalculator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/**
 * H6, phase 3: orchestration of {@code AdminIncidentService.overridePriority} with every collaborator mocked,
 * so the order of work, the policy lookup and the failure paths are pinned without a database.
 */
class AdminIncidentServiceOverrideTest {

    private static final Instant T0 = Instant.parse("2026-11-03T10:00:00Z");
    private static final Instant NOW = T0.plus(Duration.ofMinutes(10));
    private static final String ADMIN_ID = "ADMIN-SYS-01";
    private static final String TICKET = "FIN-2026-000142";

    private IncidentRepository incidentRepository;
    private UserRepository userRepository;
    private SlaPolicyRepository slaPolicyRepository;
    private DomainEventPublisher eventPublisher;
    private AccessPolicy accessPolicy;
    private SlaService slaService;
    private AdminIncidentService service;
    private Incident incident;

    @BeforeEach
    void setUp() {
        incidentRepository = mock(IncidentRepository.class);
        userRepository = mock(UserRepository.class);
        slaPolicyRepository = mock(SlaPolicyRepository.class);
        eventPublisher = mock(DomainEventPublisher.class);
        accessPolicy = mock(AccessPolicy.class);
        slaService = mock(SlaService.class);
        service = new AdminIncidentService(
                incidentRepository,
                userRepository,
                mock(TechnicianRepository.class),
                slaPolicyRepository,
                mock(ItServiceRepository.class),
                mock(SupportTeamRepository.class),
                mock(ReassignmentRequestRepository.class),
                eventPublisher,
                accessPolicy,
                mock(IncidentClassifier.class),
                mock(CompositePriorityCalculator.class),
                mock(AssignmentStrategyRegistry.class),
                mock(TicketRouter.class),
                mock(TechnicianAssignmentService.class),
                slaService,
                mock(AssignmentDecisionRecorder.class),
                Clock.fixed(NOW, ZoneOffset.UTC));

        Administrator admin = mock(Administrator.class);
        when(admin.getId()).thenReturn(ADMIN_ID);
        when(userRepository.findById(ADMIN_ID)).thenReturn(Optional.of(admin));
        incident = classified(Priority.P3);
        when(incidentRepository.findById(TICKET)).thenReturn(Optional.of(incident));
        when(incidentRepository.save(any(Incident.class))).thenAnswer(call -> call.getArgument(0));
        when(slaPolicyRepository.findDefaultByPriority(any())).thenReturn(Optional.empty());
    }

    private static Incident classified(Priority priority) {
        Incident ticket = new Incident(TICKET, "Valid Title", "Valid description that is long enough.", "EMP-1", T0);
        ticket.classify(IncidentCategory.SOFTWARE, "SVC-1",
                new PriorityDecision(priority, 0.4, List.of(), List.of(), T0, null, null),
                SlaPolicy.defaultFor(priority), T0);
        return ticket;
    }

    @ParameterizedTest(name = "[{index}] default policy of {0} is used when the repository has none")
    @EnumSource(Priority.class)
    void fallsBackToTheFactoryPolicy(Priority target) {
        service.overridePriority(TICKET, ADMIN_ID, target, "Reason");

        assertThat(incident.getSlaTracker().getResolutionDueAt())
                .isEqualTo(T0.plus(SlaPolicy.defaultFor(target).getResolutionTarget()));
    }

    @Test
    @DisplayName("a policy stored in the repository wins over the factory default")
    void repositoryPolicyWins() {
        SlaPolicy custom = new SlaPolicy("SLA-CUSTOM-P1", Priority.P1, null, Duration.ofMinutes(2), Duration.ofMinutes(12), 0.75);
        when(slaPolicyRepository.findDefaultByPriority(Priority.P1)).thenReturn(Optional.of(custom));

        service.overridePriority(TICKET, ADMIN_ID, Priority.P1, "Reason");

        assertThat(incident.getSlaTracker().getResolutionDueAt()).isEqualTo(T0.plus(Duration.ofMinutes(12)));
        assertThat(incident.getSlaTracker().getResponseDueAt()).isEqualTo(T0.plus(Duration.ofMinutes(2)));
    }

    @Test
    @DisplayName("the policy is looked up for the NEW priority, not the old one")
    void looksUpTheNewPriority() {
        service.overridePriority(TICKET, ADMIN_ID, Priority.P1, "Reason");

        verify(slaPolicyRepository).findDefaultByPriority(Priority.P1);
        verify(slaPolicyRepository, never()).findDefaultByPriority(Priority.P3);
    }

    @Test
    @DisplayName("the save happens before the SLA announcement, which happens before the PriorityOverridden event")
    void orderOfWork() {
        service.overridePriority(TICKET, ADMIN_ID, Priority.P1, "Reason");

        InOrder order = inOrder(incidentRepository, slaService, eventPublisher);
        order.verify(incidentRepository).save(incident);
        order.verify(slaService).announceTransition(eq(incident), any(SlaStatus.class), eq(NOW));
        order.verify(eventPublisher).publish(any(DomainEvent.PriorityOverridden.class));
    }

    @Test
    @DisplayName("the announcement gets the status the tracker had BEFORE the override")
    void announcementGetsThePreviousStatus() {
        incident.getSlaTracker().recordResponse(T0.plusSeconds(30));
        SlaStatus before = incident.getSlaTracker().getStatus();

        service.overridePriority(TICKET, ADMIN_ID, Priority.P1, "Reason");

        verify(slaService).announceTransition(incident, before, NOW);
    }

    @Test
    @DisplayName("PriorityOverridden carries the old and new priority, the admin and the reason")
    void overriddenEventFacts() {
        service.overridePriority(TICKET, ADMIN_ID, Priority.P1, "Board escalation");

        ArgumentCaptor<DomainEvent> captor = ArgumentCaptor.forClass(DomainEvent.class);
        verify(eventPublisher).publish(captor.capture());
        DomainEvent.PriorityOverridden event = (DomainEvent.PriorityOverridden) captor.getValue();
        assertThat(event.previousPriority()).isEqualTo(Priority.P3);
        assertThat(event.newPriority()).isEqualTo(Priority.P1);
        assertThat(event.actorId()).isEqualTo(ADMIN_ID);
        assertThat(event.reason()).isEqualTo("Board escalation");
        assertThat(event.occurredAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("a non-admin caller is rejected before anything is loaded, saved or announced")
    void nonAdminIsRejectedEarly() {
        doThrow(new UnauthorizedOperationException("Admin only")).when(accessPolicy).enforceAdmin(any());

        assertThatThrownBy(() -> service.overridePriority(TICKET, ADMIN_ID, Priority.P1, "Reason"))
                .isInstanceOf(UnauthorizedOperationException.class);

        verify(incidentRepository, never()).save(any());
        verify(slaService, never()).announceTransition(any(), any(), any());
        verify(eventPublisher, never()).publish(any());
    }

    @Test
    @DisplayName("an unknown ticket is rejected and nothing is saved or announced")
    void unknownTicketIsRejected() {
        when(incidentRepository.findById("ZZZ-2026-000001")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.overridePriority("ZZZ-2026-000001", ADMIN_ID, Priority.P1, "Reason"))
                .isInstanceOf(ServiceNotFoundException.class);

        verify(incidentRepository, never()).save(any());
        verify(slaService, never()).announceTransition(any(), any(), any());
    }

    @Test
    @DisplayName("a blank reason is rejected by the domain and nothing is saved, announced or published")
    void blankReasonIsRejected() {
        assertThatThrownBy(() -> service.overridePriority(TICKET, ADMIN_ID, Priority.P1, " "))
                .isInstanceOf(ValidationException.class);

        verify(incidentRepository, never()).save(any());
        verify(slaService, never()).announceTransition(any(), any(), any());
        verify(eventPublisher, never()).publish(any());
        assertThat(incident.getPriorityDecision().priority()).isEqualTo(Priority.P3);
    }

    @Test
    @DisplayName("a blank admin id is rejected")
    void blankAdminIsRejected() {
        assertThatThrownBy(() -> service.overridePriority(TICKET, " ", Priority.P1, "Reason"))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("the staff display name (P3-...) is accepted as the ticket reference")
    void displayNameIsAccepted() {
        service.overridePriority("P3-" + TICKET, ADMIN_ID, Priority.P1, "Reason");

        assertThat(incident.getPriorityDecision().priority()).isEqualTo(Priority.P1);
    }

    @Test
    @DisplayName("an admin can be addressed by e-mail as well as by id")
    void adminByEmail() {
        Administrator byEmail = mock(Administrator.class);
        when(byEmail.getId()).thenReturn(ADMIN_ID);
        when(userRepository.findByEmail(any())).thenReturn(Optional.of(byEmail));

        service.overridePriority(TICKET, "admin@nimbusfinserve.com", Priority.P1, "Reason");

        assertThat(incident.getPriorityDecision().priority()).isEqualTo(Priority.P1);
    }

    @Test
    @DisplayName("a failing announcement does not hide itself: the exception reaches the caller (transaction rolls back)")
    void announcementFailurePropagates() {
        doThrow(new IllegalStateException("publisher down")).when(slaService).announceTransition(any(), any(), any());

        assertThatThrownBy(() -> service.overridePriority(TICKET, ADMIN_ID, Priority.P1, "Reason"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("overriding to the same priority still records the override and re-anchors without error")
    void samePriorityOverride() {
        service.overridePriority(TICKET, ADMIN_ID, Priority.P3, "Confirmed by service owner");

        assertThat(incident.getPriorityDecision().isOverridden()).isTrue();
        assertThat(incident.getSlaTracker().getResolutionDueAt())
                .isEqualTo(T0.plus(SlaPolicy.defaultFor(Priority.P3).getResolutionTarget()));
    }
}
