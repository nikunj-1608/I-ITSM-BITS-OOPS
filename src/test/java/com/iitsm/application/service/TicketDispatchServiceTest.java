package com.iitsm.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.iitsm.application.service.TicketDispatchService.DispatchResult;
import com.iitsm.application.service.TicketDispatchService.Outcome;
import com.iitsm.domain.event.DomainEvent;
import com.iitsm.domain.event.DomainEventPublisher;
import com.iitsm.domain.model.Email;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.IncidentStatus;
import com.iitsm.domain.model.ItService;
import com.iitsm.domain.model.Priority;
import com.iitsm.domain.model.PriorityDecision;
import com.iitsm.domain.model.Skill;
import com.iitsm.domain.model.SupportTeam;
import com.iitsm.domain.model.SlaPolicy;
import com.iitsm.domain.model.Technician;
import com.iitsm.domain.repository.IncidentRepository;
import com.iitsm.domain.policy.RoutingPolicy;
import com.iitsm.domain.repository.ItServiceRepository;
import com.iitsm.domain.repository.SupportTeamRepository;
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
import org.mockito.ArgumentCaptor;
import com.iitsm.domain.strategy.assignment.RoutedCandidates;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class TicketDispatchServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");
    private static final String NETWORK_TEAM = "TEAM-NET";
    private static final String OTHER_TEAM = "TEAM-OTHER";

    private IncidentRepository incidentRepository;
    private TechnicianRepository technicianRepository;
    private TechnicianAssignmentService assignmentService;
    private DomainEventPublisher eventPublisher;
    private TicketDispatchService service;

    @BeforeEach
    void setUp() {
        incidentRepository = mock(IncidentRepository.class);
        technicianRepository = mock(TechnicianRepository.class);
        ItServiceRepository itServiceRepository = mock(ItServiceRepository.class);
        SupportTeamRepository supportTeamRepository = mock(SupportTeamRepository.class);
        when(supportTeamRepository.findByHandledCategory(IncidentCategory.NETWORK)).thenReturn(List.of(
                new SupportTeam(NETWORK_TEAM, "Network", Set.of(IncidentCategory.NETWORK), "LEAD-NET")));
        assignmentService = mock(TechnicianAssignmentService.class);
        eventPublisher = mock(DomainEventPublisher.class);
        ItService vpn = mock(ItService.class);
        when(vpn.getOwningTeamId()).thenReturn(NETWORK_TEAM);
        when(itServiceRepository.findById("SVC-VPN")).thenReturn(Optional.of(vpn));
        when(incidentRepository.save(any(Incident.class))).thenAnswer(call -> call.getArgument(0));
        TicketRouter router = new TicketRouter(supportTeamRepository, itServiceRepository, new CandidateFilter(),
                new RoutingPolicy());
        service = new TicketDispatchService(incidentRepository, technicianRepository, router,
                new AssignmentStrategyRegistry(), assignmentService, eventPublisher, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Incident waiting(String id) {
        Incident incident = new Incident(id, "VPN keeps dropping", "The VPN client drops every hour for all of us.", "EMP-1", NOW);
        PriorityDecision decision = new PriorityDecision(Priority.P3, 0.4, List.of(), List.of(), NOW, null, null);
        incident.classify(IncidentCategory.NETWORK, "SVC-VPN", decision, SlaPolicy.defaultFor(Priority.P3), NOW);
        return incident;
    }

    private static Technician technician(String id, String team) {
        return new Technician(id, "Tech " + id, new Email(id.toLowerCase() + "@test.com"), "hash",
                Set.of(new Skill("BGP", IncidentCategory.NETWORK)), 5, 5, team);
    }

    /** A technician of another team without any network skill. */
    private static Technician unskilled(String id, String team) {
        return new Technician(id, "Tech " + id, new Email(id.toLowerCase() + "@test.com"), "hash",
                Set.of(new Skill("Excel", IncidentCategory.SOFTWARE)), 5, 5, team);
    }

    private void tickets(Incident... incidents) {
        for (Incident incident : incidents) {
            when(incidentRepository.findById(incident.getId())).thenReturn(Optional.of(incident));
        }
    }

    @Test
    @DisplayName("Assigns the waiting ticket, saves it and announces the assignment as the given actor")
    void assignsAndAnnounces() {
        Incident ticket = waiting("INC-1");
        tickets(ticket);
        when(technicianRepository.findAvailableTechnicians()).thenReturn(List.of(technician("TECH-1", NETWORK_TEAM)));
        when(assignmentService.tryAssignTechnician(any(), any(), any(), any(), eq(NOW))).thenReturn(Optional.of("TECH-1"));

        DispatchResult result = service.dispatchOne("INC-1", "SYSTEM");

        assertThat(result.outcome()).isEqualTo(Outcome.ASSIGNED);
        assertThat(result.incident().getStatus()).isEqualTo(IncidentStatus.ASSIGNED);
        assertThat(result.incident().getAssigneeId()).isEqualTo("TECH-1");
        verify(incidentRepository).save(ticket);
        ArgumentCaptor<DomainEvent> event = ArgumentCaptor.forClass(DomainEvent.class);
        verify(eventPublisher).publish(event.capture());
        assertThat(event.getValue()).isInstanceOfSatisfying(DomainEvent.IncidentAssigned.class, assigned -> {
            assertThat(assigned.incidentId()).isEqualTo("INC-1");
            assertThat(assigned.actorId()).isEqualTo("SYSTEM");
            assertThat(assigned.technicianId()).isEqualTo("TECH-1");
        });
    }

    @Test
    @DisplayName("Offers the technicians of the team that handles the ticket's category first")
    void prefersTheOwningTeam() {
        Incident ticket = waiting("INC-1");
        tickets(ticket);
        Technician owner = technician("TECH-NET", NETWORK_TEAM);
        Technician other = technician("TECH-OTHER", OTHER_TEAM);
        when(technicianRepository.findAvailableTechnicians()).thenReturn(List.of(other, owner));
        when(assignmentService.tryAssignTechnician(any(), any(), any(), any(), any())).thenReturn(Optional.of("TECH-NET"));

        service.dispatchOne("INC-1", "SYSTEM");

        ArgumentCaptor<RoutedCandidates> offered = ArgumentCaptor.forClass(RoutedCandidates.class);
        verify(assignmentService).tryAssignTechnician(any(), offered.capture(), any(), any(), any());
        assertThat(offered.getValue().technicians()).containsExactly(owner);
    }

    @Test
    @DisplayName("ADR-036: overflows to a skilled technician of another team when the owning team is full")
    void overflowsToASkilledTechnicianWhenTheOwningTeamIsFull() {
        Incident ticket = waiting("INC-1");
        tickets(ticket);
        Technician other = technician("TECH-OTHER", OTHER_TEAM);
        when(technicianRepository.findAvailableTechnicians()).thenReturn(List.of(other));
        when(assignmentService.tryAssignTechnician(any(), any(), any(), any(), any())).thenReturn(Optional.of("TECH-OTHER"));

        assertThat(service.dispatchOne("INC-1", "DISPATCHER").outcome()).isEqualTo(Outcome.ASSIGNED);
    }

    @Test
    @DisplayName("ADR-036: an unskilled technician of another team is never offered the ticket; it waits")
    void anUnskilledTechnicianIsNeverOffered() {
        Incident ticket = waiting("INC-1");
        tickets(ticket);
        when(technicianRepository.findAvailableTechnicians()).thenReturn(List.of(unskilled("TECH-OTHER", OTHER_TEAM)));

        DispatchResult result = service.dispatchOne("INC-1", "DISPATCHER");

        assertThat(result.outcome()).isEqualTo(Outcome.NOT_ASSIGNED);
        assertThat(ticket.getStatus()).isEqualTo(IncidentStatus.CLASSIFIED);
        verify(assignmentService, never()).tryAssignTechnician(any(), any(), any(), any(), any());
        verify(incidentRepository, never()).save(any());
    }

    @Test
    @DisplayName("ADR-036: the category decides the team, not the service: a ticket of a service owned by another team")
    void theCategoryDecidesNotTheService() {
        Incident ticket = waiting("INC-1"); // NETWORK ticket on SVC-VPN
        tickets(ticket);
        Technician networkTech = technician("TECH-NET", NETWORK_TEAM);
        Technician deskTech = technician("TECH-DESK", OTHER_TEAM);
        when(technicianRepository.findAvailableTechnicians()).thenReturn(List.of(deskTech, networkTech));
        when(assignmentService.tryAssignTechnician(any(), any(), any(), any(), any())).thenReturn(Optional.of("TECH-NET"));

        service.dispatchOne("INC-1", "SYSTEM");

        ArgumentCaptor<RoutedCandidates> offered = ArgumentCaptor.forClass(RoutedCandidates.class);
        verify(assignmentService).tryAssignTechnician(any(), offered.capture(), any(), any(), any());
        assertThat(offered.getValue().technicians()).containsExactly(networkTech);
    }

    @Test
    @DisplayName("A technician who flagged the ticket as wrong domain is never offered it again, and nobody else is forced in")
    void honoursExclusionsWithoutFallback() {
        Incident ticket = waiting("INC-1");
        ticket.addExcludedTechnician("TECH-1");
        tickets(ticket);
        when(technicianRepository.findAvailableTechnicians()).thenReturn(List.of(technician("TECH-1", NETWORK_TEAM)));

        DispatchResult result = service.dispatchOne("INC-1", "DISPATCHER");

        assertThat(result.outcome()).isEqualTo(Outcome.NOT_ASSIGNED);
        verify(assignmentService, never()).tryAssignTechnician(any(), any(), any(), any(), any());
        verify(incidentRepository, never()).save(any());
    }

    @Test
    @DisplayName("NOT_WAITING for an unknown ticket, a ticket in triage and a ticket that already has a technician")
    void notWaiting() {
        Incident triage = waiting("INC-T");
        triage.setNeedsTriage(true);
        Incident assigned = waiting("INC-A");
        assigned.assign("TECH-9", NOW);
        tickets(triage, assigned);
        when(incidentRepository.findById("INC-NONE")).thenReturn(Optional.empty());

        assertThat(service.dispatchOne("INC-T", "SYSTEM").outcome()).isEqualTo(Outcome.NOT_WAITING);
        assertThat(service.dispatchOne("INC-A", "SYSTEM").outcome()).isEqualTo(Outcome.NOT_WAITING);
        DispatchResult unknown = service.dispatchOne("INC-NONE", "SYSTEM");
        assertThat(unknown.outcome()).isEqualTo(Outcome.NOT_WAITING);
        assertThat(unknown.incident()).isNull();
        verify(assignmentService, never()).tryAssignTechnician(any(), any(), any(), any(), any());
        verify(eventPublisher, never()).publish(any());
    }

    @Test
    @DisplayName("NO_CAPACITY when no technician has a free slot")
    void noCapacity() {
        Incident ticket = waiting("INC-1");
        tickets(ticket);
        Technician full = technician("TECH-1", NETWORK_TEAM);
        for (int slot = 0; slot < full.getMaxCapacity(); slot++) {
            full.assignIncident("INC-FILL-" + slot);
        }
        when(technicianRepository.findAvailableTechnicians()).thenReturn(List.of(full));

        assertThat(service.dispatchOne("INC-1", "SYSTEM").outcome()).isEqualTo(Outcome.NO_CAPACITY);
        verify(incidentRepository, never()).save(any());
    }

    @Test
    @DisplayName("When the slot cannot be taken (a capacity race) nothing is saved and nothing is announced")
    void slotNotTaken() {
        Incident ticket = waiting("INC-1");
        tickets(ticket);
        when(technicianRepository.findAvailableTechnicians()).thenReturn(List.of(technician("TECH-1", NETWORK_TEAM)));
        when(assignmentService.tryAssignTechnician(any(), any(), any(), any(), any())).thenReturn(Optional.empty());

        DispatchResult result = service.dispatchOne("INC-1", "SYSTEM");

        assertThat(result.outcome()).isEqualTo(Outcome.NOT_ASSIGNED);
        assertThat(ticket.getStatus()).isEqualTo(IncidentStatus.CLASSIFIED);
        verify(incidentRepository, never()).save(any());
        verify(eventPublisher, never()).publish(any());
    }

    @Test
    @DisplayName("ADR-026: one call is one transaction, so the slot and the assignee commit or roll back together")
    void oneCallIsOneTransaction() throws NoSuchMethodException {
        Transactional transactional = TicketDispatchService.class
                .getMethod("dispatchOne", String.class, String.class).getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRED);
    }
}
