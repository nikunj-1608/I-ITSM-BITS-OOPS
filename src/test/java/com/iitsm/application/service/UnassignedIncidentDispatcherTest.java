package com.iitsm.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.iitsm.application.service.TicketDispatchService.DispatchResult;
import com.iitsm.application.service.TicketDispatchService.Outcome;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.IncidentStatus;
import com.iitsm.domain.model.Priority;
import com.iitsm.domain.model.PriorityDecision;
import com.iitsm.domain.model.SlaPolicy;
import com.iitsm.domain.repository.IncidentRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.annotation.Transactional;

class UnassignedIncidentDispatcherTest {

    private static final Instant NOW = Instant.parse("2026-10-30T10:00:00Z");

    private IncidentRepository incidentRepository;
    private TicketDispatchService dispatchService;
    private UnassignedIncidentDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        incidentRepository = mock(IncidentRepository.class);
        dispatchService = mock(TicketDispatchService.class);
        dispatcher = new UnassignedIncidentDispatcher(incidentRepository, dispatchService);
    }

    private static Incident classified(String id, Priority priority) {
        Incident incident = new Incident(id, "VPN keeps dropping", "The VPN client drops every hour for all of us.", "EMP-1", NOW);
        PriorityDecision decision = new PriorityDecision(priority, 0.4, List.of(), List.of(), NOW, null, null);
        incident.classify(IncidentCategory.NETWORK, "SVC-VPN", decision, SlaPolicy.defaultFor(priority), NOW);
        return incident;
    }

    private static Incident classified(String id) {
        return classified(id, Priority.P3);
    }

    private static DispatchResult result(Outcome outcome, Incident incident) {
        return new DispatchResult(outcome, incident);
    }

    @Test
    @DisplayName("Assigns a waiting CLASSIFIED incident once a technician has capacity, recorded as the dispatcher")
    void assignsWaitingIncident() {
        Incident waiting = classified("INC-1");
        when(incidentRepository.findByStatus(IncidentStatus.CLASSIFIED)).thenReturn(List.of(waiting));
        when(dispatchService.dispatchOne("INC-1", "DISPATCHER")).thenReturn(result(Outcome.ASSIGNED, waiting));

        assertThat(dispatcher.dispatchWaitingIncidents()).isEqualTo(1);
        verify(dispatchService).dispatchOne("INC-1", "DISPATCHER");
    }

    @Test
    @DisplayName("Never touches incidents flagged for human triage or already assigned")
    void skipsTriageAndAssignedIncidents() {
        Incident triage = classified("INC-T");
        triage.setNeedsTriage(true);
        Incident assigned = classified("INC-A");
        assigned.assign("TECH-1", NOW);
        when(incidentRepository.findByStatus(IncidentStatus.CLASSIFIED)).thenReturn(List.of(triage, assigned));

        assertThat(dispatcher.dispatchWaitingIncidents()).isZero();
        verify(dispatchService, never()).dispatchOne(any(), any());
    }

    @Test
    @DisplayName("Stops the cycle when no technician has free capacity anywhere")
    void stopsWhenThereIsNoCapacity() {
        Incident first = classified("INC-1");
        Incident second = classified("INC-2");
        when(incidentRepository.findByStatus(IncidentStatus.CLASSIFIED)).thenReturn(List.of(first, second));
        when(dispatchService.dispatchOne(eq("INC-1"), any())).thenReturn(result(Outcome.NO_CAPACITY, first));

        assertThat(dispatcher.dispatchWaitingIncidents()).isZero();
        verify(dispatchService, never()).dispatchOne(eq("INC-2"), any());
    }

    @Test
    @DisplayName("A lost optimistic-lock race on one incident does not abort the rest of the cycle")
    void lockRaceDoesNotAbortCycle() {
        Incident first = classified("INC-1");
        Incident second = classified("INC-2");
        when(incidentRepository.findByStatus(IncidentStatus.CLASSIFIED)).thenReturn(List.of(first, second));
        when(dispatchService.dispatchOne(eq("INC-1"), any()))
                .thenThrow(new ObjectOptimisticLockingFailureException(Incident.class, "INC-1"));
        when(dispatchService.dispatchOne(eq("INC-2"), any())).thenReturn(result(Outcome.ASSIGNED, second));

        assertThat(dispatcher.dispatchWaitingIncidents()).isEqualTo(1);
    }

    @Test
    @DisplayName("An unexpected failure on one incident is isolated too: the others are still assigned")
    void unexpectedFailureDoesNotAbortCycle() {
        Incident first = classified("INC-1");
        Incident second = classified("INC-2");
        when(incidentRepository.findByStatus(IncidentStatus.CLASSIFIED)).thenReturn(List.of(first, second));
        when(dispatchService.dispatchOne(eq("INC-1"), any())).thenThrow(new DataIntegrityViolationException("constraint"));
        when(dispatchService.dispatchOne(eq("INC-2"), any())).thenReturn(result(Outcome.ASSIGNED, second));

        assertThat(dispatcher.dispatchWaitingIncidents()).isEqualTo(1);
        verify(dispatchService).dispatchOne(eq("INC-2"), any());
    }

    @Test
    @DisplayName("The most urgent waiting incident is dispatched first")
    void dispatchesMostUrgentFirst() {
        Incident routine = classified("INC-P3", Priority.P3);
        Incident critical = classified("INC-P1", Priority.P1);
        when(incidentRepository.findByStatus(IncidentStatus.CLASSIFIED)).thenReturn(List.of(routine, critical));
        when(dispatchService.dispatchOne(any(), any())).thenReturn(result(Outcome.ASSIGNED, critical));

        dispatcher.dispatchWaitingIncidents();

        InOrder order = inOrder(dispatchService);
        order.verify(dispatchService).dispatchOne(eq("INC-P1"), any());
        order.verify(dispatchService).dispatchOne(eq("INC-P3"), any());
    }

    @Test
    @DisplayName("ADR-026: the cycle itself is not transactional; every incident gets its own transaction")
    void cycleIsNotOneTransaction() throws NoSuchMethodException {
        assertThat(UnassignedIncidentDispatcher.class.getAnnotation(Transactional.class)).isNull();
        assertThat(UnassignedIncidentDispatcher.class.getMethod("dispatchWaitingIncidents").getAnnotation(Transactional.class))
                .as("one transaction around the whole cycle is what leaked slots before ADR-026").isNull();
    }
}
