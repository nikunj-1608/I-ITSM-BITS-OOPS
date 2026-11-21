package com.iitsm.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.iitsm.domain.event.DomainEventPublisher;
import com.iitsm.domain.model.Email;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.IncidentStatus;
import com.iitsm.domain.model.Priority;
import com.iitsm.domain.model.PriorityDecision;
import com.iitsm.domain.model.Skill;
import com.iitsm.domain.model.SlaPolicy;
import com.iitsm.domain.model.Technician;
import com.iitsm.domain.repository.IncidentRepository;
import com.iitsm.domain.repository.ItServiceRepository;
import com.iitsm.domain.repository.TechnicianRepository;
import com.iitsm.domain.strategy.assignment.AssignmentStrategyRegistry;
import com.iitsm.domain.strategy.assignment.CandidateFilter;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

class UnassignedIncidentDispatcherTest {

    private static final Instant NOW = Instant.parse("2026-10-30T10:00:00Z");

    private IncidentRepository incidentRepository;
    private TechnicianRepository technicianRepository;
    private TechnicianAssignmentService assignmentService;
    private UnassignedIncidentDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        incidentRepository = mock(IncidentRepository.class);
        technicianRepository = mock(TechnicianRepository.class);
        assignmentService = mock(TechnicianAssignmentService.class);
        ItServiceRepository itServiceRepository = mock(ItServiceRepository.class);
        when(itServiceRepository.findById(any())).thenReturn(Optional.empty());
        dispatcher = new UnassignedIncidentDispatcher(
                incidentRepository, technicianRepository, itServiceRepository, new CandidateFilter(),
                new AssignmentStrategyRegistry(), assignmentService, mock(DomainEventPublisher.class),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Incident classified(String id) {
        Incident incident = new Incident(id, "VPN keeps dropping", "The VPN client drops every hour for all of us.", "EMP-1", NOW);
        PriorityDecision decision = new PriorityDecision(Priority.P3, 0.4, List.of(), List.of(), NOW, null, null);
        incident.classify(IncidentCategory.NETWORK, "SVC-VPN", decision, SlaPolicy.defaultFor(Priority.P3), NOW);
        return incident;
    }

    private static Technician technician(String id) {
        return new Technician(id, "Tech " + id, new Email(id.toLowerCase() + "@test.com"), "hash",
                Set.of(new Skill("BGP", IncidentCategory.NETWORK)), 5, 5, "TEAM-NET");
    }

    @Test
    @DisplayName("Assigns a waiting CLASSIFIED incident once a technician has capacity")
    void assignsWaitingIncident() {
        Incident waiting = classified("INC-1");
        when(incidentRepository.findByStatus(IncidentStatus.CLASSIFIED)).thenReturn(List.of(waiting));
        when(technicianRepository.findAvailableTechnicians()).thenReturn(List.of(technician("TECH-1")));
        when(assignmentService.tryAssignTechnician(any(), any(), any(), any())).thenReturn(Optional.of("TECH-1"));

        assertThat(dispatcher.dispatchWaitingIncidents()).isEqualTo(1);
        assertThat(waiting.getStatus()).isEqualTo(IncidentStatus.ASSIGNED);
        assertThat(waiting.getAssigneeId()).isEqualTo("TECH-1");
        verify(incidentRepository).save(waiting);
    }

    @Test
    @DisplayName("Never auto-assigns incidents flagged for human triage")
    void skipsTriageIncidents() {
        Incident triage = classified("INC-T");
        triage.setNeedsTriage(true);
        when(incidentRepository.findByStatus(IncidentStatus.CLASSIFIED)).thenReturn(List.of(triage));

        assertThat(dispatcher.dispatchWaitingIncidents()).isZero();
        verify(assignmentService, never()).tryAssignTechnician(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Does nothing when no technician has free capacity")
    void doesNothingWithoutCapacity() {
        when(incidentRepository.findByStatus(IncidentStatus.CLASSIFIED)).thenReturn(List.of(classified("INC-1")));
        when(technicianRepository.findAvailableTechnicians()).thenReturn(List.of());

        assertThat(dispatcher.dispatchWaitingIncidents()).isZero();
        verify(assignmentService, never()).tryAssignTechnician(any(), any(), any(), any());
    }

    @Test
    @DisplayName("A lost optimistic-lock race on one incident does not abort the rest of the cycle")
    void lockRaceDoesNotAbortCycle() {
        Incident first = classified("INC-1");
        Incident second = classified("INC-2");
        when(incidentRepository.findByStatus(IncidentStatus.CLASSIFIED)).thenReturn(List.of(first, second));
        when(technicianRepository.findAvailableTechnicians()).thenReturn(List.of(technician("TECH-1")));
        when(assignmentService.tryAssignTechnician(any(), any(), any(), any()))
                .thenThrow(new ObjectOptimisticLockingFailureException(Technician.class, "TECH-1"))
                .thenReturn(Optional.of("TECH-1"));

        assertThat(dispatcher.dispatchWaitingIncidents()).isEqualTo(1);
        assertThat(first.getStatus()).isEqualTo(IncidentStatus.CLASSIFIED);
        assertThat(second.getStatus()).isEqualTo(IncidentStatus.ASSIGNED);
    }
}
