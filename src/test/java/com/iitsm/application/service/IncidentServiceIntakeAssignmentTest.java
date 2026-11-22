package com.iitsm.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.iitsm.application.service.IncidentIntakePipeline.IntakeResult;
import com.iitsm.application.service.TicketDispatchService.DispatchResult;
import com.iitsm.application.service.TicketDispatchService.Outcome;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.Priority;
import com.iitsm.domain.model.PriorityDecision;
import com.iitsm.domain.model.SlaPolicy;
import com.iitsm.domain.strategy.assignment.AssignmentStrategyRegistry;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** ADR-026: the ticket is committed first; its assignment is a second, separate step that can never lose the ticket. */
class IncidentServiceIntakeAssignmentTest {

    private static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");

    private IncidentIntakePipeline pipeline;
    private TicketDispatchService dispatchService;
    private IncidentService service;

    @BeforeEach
    void setUp() {
        pipeline = mock(IncidentIntakePipeline.class);
        dispatchService = mock(TicketDispatchService.class);
        service = new IncidentService(pipeline, dispatchService, mock(IncidentLifecycleService.class),
                mock(AdminIncidentService.class), new AssignmentStrategyRegistry(), mock(DuplicateDetectionService.class),
                new IntakeGate(true, 1000));
    }

    private static Incident classified(String id) {
        Incident incident = new Incident(id, "VPN keeps dropping", "The VPN client drops every hour for all of us.", "EMP-1", NOW);
        PriorityDecision decision = new PriorityDecision(Priority.P3, 0.4, List.of(), List.of(), NOW, null, null);
        incident.classify(IncidentCategory.NETWORK, "SVC-VPN", decision, SlaPolicy.defaultFor(Priority.P3), NOW);
        return incident;
    }

    private void intakeProduces(Incident ticket, boolean awaitingAssignment) {
        when(pipeline.processIntake(any(), any(), any())).thenReturn(new IntakeResult(ticket, awaitingAssignment));
    }

    @Test
    @DisplayName("A confident ticket is assigned right after intake, as SYSTEM, and the assigned ticket is returned")
    void assignsAfterIntake() {
        Incident created = classified("INC-1");
        Incident assigned = classified("INC-1");
        assigned.assign("TECH-1", NOW);
        intakeProduces(created, true);
        when(dispatchService.dispatchOne("INC-1", "SYSTEM")).thenReturn(new DispatchResult(Outcome.ASSIGNED, assigned));

        assertThat(service.createIncident("EMP-1", "t", "d")).isSameAs(assigned);
    }

    @Test
    @DisplayName("A ticket that is not waiting for assignment (triage, duplicate) is returned as saved, without a dispatch")
    void noDispatchWhenNothingToAssign() {
        Incident created = classified("INC-1");
        intakeProduces(created, false);

        assertThat(service.createIncident("EMP-1", "t", "d")).isSameAs(created);
        verify(dispatchService, never()).dispatchOne(any(), any());
    }

    @Test
    @DisplayName("When no technician could take it, the reporter gets the saved ticket, left for the dispatcher")
    void noCapacityReturnsTheSavedTicket() {
        Incident created = classified("INC-1");
        intakeProduces(created, true);
        when(dispatchService.dispatchOne(eq("INC-1"), any())).thenReturn(new DispatchResult(Outcome.NO_CAPACITY, created));

        assertThat(service.createIncident("EMP-1", "t", "d")).isSameAs(created);
    }

    @Test
    @DisplayName("A lost optimistic-lock race in the assignment step never loses the ticket")
    void lostRaceKeepsTheTicket() {
        Incident created = classified("INC-1");
        intakeProduces(created, true);
        when(dispatchService.dispatchOne(eq("INC-1"), any()))
                .thenThrow(new ObjectOptimisticLockingFailureException(Incident.class, "INC-1"));

        assertThat(service.createIncident("EMP-1", "t", "d")).isSameAs(created);
    }

    @Test
    @DisplayName("Any other failure in the assignment step does not hide the committed ticket either")
    void unexpectedFailureKeepsTheTicket() {
        Incident created = classified("INC-1");
        intakeProduces(created, true);
        when(dispatchService.dispatchOne(eq("INC-1"), any())).thenThrow(new DataIntegrityViolationException("constraint"));

        assertThat(service.createIncident("EMP-1", "t", "d")).isSameAs(created);
    }

    @Test
    @DisplayName("The intake itself still fails for the reporter when the ticket could not be saved")
    void intakeFailurePropagates() {
        when(pipeline.processIntake(any(), any(), any())).thenThrow(new IllegalStateException("cannot save"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.createIncident("EMP-1", "t", "d"))
                .isInstanceOf(IllegalStateException.class);
        verify(dispatchService, never()).dispatchOne(any(), any());
    }

    @Test
    @DisplayName("ADR-018/026: createIncident has no transaction of its own, so the intake and the assignment commit separately")
    void createIncidentIsNotTransactional() throws NoSuchMethodException {
        Transactional transactional = IncidentService.class
                .getMethod("createIncident", String.class, String.class, String.class).getAnnotation(Transactional.class);

        assertThat(transactional.propagation()).isEqualTo(Propagation.SUPPORTS);
    }
}
