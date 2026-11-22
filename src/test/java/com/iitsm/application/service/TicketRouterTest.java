package com.iitsm.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.iitsm.domain.model.Email;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.ItService;
import com.iitsm.domain.model.Priority;
import com.iitsm.domain.model.PriorityDecision;
import com.iitsm.domain.model.Skill;
import com.iitsm.domain.model.SlaPolicy;
import com.iitsm.domain.model.SupportTeam;
import com.iitsm.domain.model.Technician;
import com.iitsm.domain.policy.RoutingPolicy;
import com.iitsm.domain.repository.ItServiceRepository;
import com.iitsm.domain.repository.SupportTeamRepository;
import com.iitsm.domain.strategy.assignment.CandidateFilter;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Remaining-defects plan, Phase G (ADR-036): the router names the owning team by the ticket's category and offers only
 * technicians who may take it. The service owner is not consulted unless no team handles the category.
 */
class TicketRouterTest {

    private static final Instant NOW = Instant.parse("2026-11-06T10:00:00Z");

    private SupportTeamRepository teams;
    private ItServiceRepository services;
    private TicketRouter router;

    @BeforeEach
    void setUp() {
        teams = mock(SupportTeamRepository.class);
        services = mock(ItServiceRepository.class);
        when(teams.findByHandledCategory(IncidentCategory.NETWORK)).thenReturn(List.of(team("TEAM-NET", IncidentCategory.NETWORK)));
        when(teams.findByHandledCategory(IncidentCategory.DATABASE)).thenReturn(List.of(team("TEAM-DB", IncidentCategory.DATABASE)));
        when(teams.findByHandledCategory(IncidentCategory.CLOUD)).thenReturn(List.of());
        when(teams.findById("TEAM-NET")).thenReturn(Optional.of(team("TEAM-NET", IncidentCategory.NETWORK)));
        router = new TicketRouter(teams, services, new CandidateFilter(), new RoutingPolicy());
    }

    private static SupportTeam team(String id, IncidentCategory... categories) {
        return new SupportTeam(id, "Team " + id, Set.of(categories), "LEAD-" + id);
    }

    private static ItService serviceOwnedBy(String teamId) {
        ItService service = mock(ItService.class);
        when(service.getOwningTeamId()).thenReturn(teamId);
        return service;
    }

    private static Incident ticket(IncidentCategory category, String serviceId) {
        Incident incident = new Incident("FIN-2026-000001", "Something is down", "Everybody in the office is affected.", "EMP-1", NOW);
        PriorityDecision decision = new PriorityDecision(Priority.P3, 0.4, List.of(), List.of(), NOW, null, null);
        incident.classify(category, serviceId, decision, SlaPolicy.defaultFor(Priority.P3), NOW);
        return incident;
    }

    private static Technician technician(String id, String team, IncidentCategory skill) {
        return new Technician(id, "Tech " + id, new Email(id.toLowerCase() + "@test.com"), "hash",
                Set.of(new Skill("Skill " + id, skill)), 5, 3, team);
    }

    @Test
    @DisplayName("The team of the category owns the ticket, whatever service it has")
    void categoryTeamOwnsTheTicket() {
        ItService cloudOwned = serviceOwnedBy("TEAM-CLOUD");
        when(services.findById("SVC-PAYMENT")).thenReturn(Optional.of(cloudOwned));

        assertThat(router.owningTeamId(ticket(IncidentCategory.NETWORK, "SVC-PAYMENT"))).contains("TEAM-NET");
        assertThat(router.owningTeamId(IncidentCategory.DATABASE, cloudOwned)).contains("TEAM-DB");
    }

    @Test
    @DisplayName("When no team handles the category the owner of the ticket's service is looked up as the fallback")
    void serviceOwnerFallback() {
        ItService cloudOwned = serviceOwnedBy("TEAM-CLOUD");
        when(services.findById("SVC-PAYMENT")).thenReturn(Optional.of(cloudOwned));

        assertThat(router.owningTeamId(ticket(IncidentCategory.CLOUD, "SVC-PAYMENT"))).contains("TEAM-CLOUD");
        assertThat(router.owningTeamId(ticket(IncidentCategory.CLOUD, null))).isEmpty();
    }

    @Test
    @DisplayName("The service is not even looked up when a team handles the category")
    void serviceIsNotLookedUpForACategoryWithATeam() {
        router.owningTeamId(ticket(IncidentCategory.NETWORK, "SVC-ANY"));

        org.mockito.Mockito.verifyNoInteractions(services);
    }

    @Test
    @DisplayName("Candidates: the owning team first, skilled overflow when it is full, nobody unskilled")
    void candidatesFollowTheRule() {
        Incident incident = ticket(IncidentCategory.NETWORK, "SVC-VPN");
        Technician owner = technician("NET-1", "TEAM-NET", IncidentCategory.NETWORK);
        Technician skilled = technician("DB-NET", "TEAM-DB", IncidentCategory.NETWORK);
        Technician unskilled = technician("DB-1", "TEAM-DB", IncidentCategory.DATABASE);

        assertThat(router.candidates(incident, List.of(owner, skilled, unskilled))).containsExactly(owner);
        for (int slot = 0; slot < owner.getMaxCapacity(); slot++) {
            owner.assignIncident("FILL-" + slot);
        }
        assertThat(router.candidates(incident, List.of(owner, skilled, unskilled))).containsExactly(skilled);
        assertThat(router.candidates(incident, List.of(owner, unskilled))).isEmpty();
    }

    @Test
    @DisplayName("A technician who flagged the ticket as wrong domain is never a candidate for it")
    void exclusionsAreHonoured() {
        Incident incident = ticket(IncidentCategory.NETWORK, "SVC-VPN");
        incident.addExcludedTechnician("NET-1");
        Technician excluded = technician("NET-1", "TEAM-NET", IncidentCategory.NETWORK);
        Technician other = technician("NET-2", "TEAM-NET", IncidentCategory.NETWORK);

        assertThat(router.candidates(incident, List.of(excluded, other))).containsExactly(other);
    }

    @Test
    @DisplayName("The categories a team handles come from its definition; an unknown team handles none")
    void categoriesOfATeam() {
        assertThat(router.categoriesHandledBy("TEAM-NET")).containsExactly(IncidentCategory.NETWORK);
        assertThat(router.categoriesHandledBy("TEAM-NOPE")).isEmpty();
    }
}
