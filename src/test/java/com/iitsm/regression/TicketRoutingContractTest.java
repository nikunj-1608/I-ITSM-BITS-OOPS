package com.iitsm.regression;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitsm.application.service.AdminIncidentService;
import com.iitsm.application.service.IncidentService;
import com.iitsm.application.service.TicketDispatchService;
import com.iitsm.application.service.UnassignedIncidentDispatcher;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.IncidentStatus;
import com.iitsm.domain.model.Priority;
import com.iitsm.domain.model.PriorityDecision;
import com.iitsm.domain.model.Skill;
import com.iitsm.domain.model.SlaPolicy;
import com.iitsm.domain.model.SupportTeam;
import com.iitsm.domain.model.Technician;
import com.iitsm.domain.repository.IncidentRepository;
import com.iitsm.domain.repository.SupportTeamRepository;
import com.iitsm.domain.repository.TechnicianRepository;
import com.iitsm.domain.repository.UserRepository;
import com.iitsm.infrastructure.persistence.CompanySeeder;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Remaining-defects plan, Phase G (ADR-036, decisions D17 and D18, stress-audit item H9 cause 1): one routing rule, used by
 * every caller that assigns or reroutes a ticket. The classified category decides the team; the service decides
 * criticality only; when the owning team is full the ticket may go to a skilled technician of another team, never to an
 * unskilled one, and otherwise it waits.
 *
 * <p>The contract test runs the same expectation through all four callers: the intake, the dispatcher, the
 * administrator's reclassify reroute and accept-suggestion.
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("regression")
class TicketRoutingContractTest {

    private static final String EMPLOYEE = "employee@nimbusfinserve.com";
    private static final String REPORTER_ID = "EMP-DEMO-01";
    private static final String ADMIN_EMAIL = "admin@nimbusfinserve.com";
    private static final String GENERAL_SERVICE = "SRV-GEN-001";
    private static final String OVERFLOW_SKILL = "Overflow routing test";
    private static final Instant NOW = Instant.parse("2026-11-06T10:00:00Z");
    private static final AtomicInteger COUNTER = new AtomicInteger();

    /** The callers of the routing rule. */
    enum Caller { INTAKE, DISPATCHER, RECLASSIFY, ACCEPT_SUGGESTION }

    @Autowired private IncidentService incidentService;
    @Autowired private AdminIncidentService adminService;
    @Autowired private TicketDispatchService dispatchService;
    @Autowired private UnassignedIncidentDispatcher dispatcher;
    @Autowired private IncidentRepository incidentRepository;
    @Autowired private TechnicianRepository technicianRepository;
    @Autowired private SupportTeamRepository supportTeamRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private CompanySeeder companySeeder;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        if (userRepository.count() == 0) {
            companySeeder.seedCompanyData();
        }
        clearSlots();
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM technician_skills WHERE skill_name = ?", OVERFLOW_SKILL);
        // A planted ticket that still waits must not be picked up by the dispatcher of a later test
        jdbcTemplate.update("UPDATE incidents SET needs_triage = TRUE WHERE id LIKE 'TST-ROUTE-%' AND assignee_id IS NULL");
        clearSlots();
    }

    private void clearSlots() {
        jdbcTemplate.execute("DELETE FROM technician_active_incidents");
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    private String teamHandling(IncidentCategory category) {
        return supportTeamRepository.findByHandledCategory(category).stream()
                .map(SupportTeam::getId).findFirst().orElseThrow();
    }

    private String teamOf(String technicianId) {
        return technicianRepository.findById(technicianId).orElseThrow().getSupportTeamId();
    }

    private void fillTeam(String teamId) {
        for (Technician t : technicianRepository.findBySupportTeamId(teamId)) {
            for (int slot = 0; slot < t.getMaxCapacity(); slot++) {
                t.assignIncident("DUMMY-SLOT-" + slot);
            }
            technicianRepository.save(t);
        }
    }

    /** A ticket that waits for a technician (CLASSIFIED, not in triage), or sits in triage when asked. */
    private Incident planted(IncidentCategory category, String serviceId, boolean triage) {
        Incident incident = new Incident("TST-ROUTE-" + System.nanoTime() + "-" + COUNTER.incrementAndGet(),
                "Routing contract " + category, "A ticket planted to test which team receives it, category " + category + ".",
                REPORTER_ID, NOW);
        PriorityDecision decision = new PriorityDecision(Priority.P3, 0.4, List.of(), List.of(), NOW, null, null);
        incident.classify(category, serviceId, decision, SlaPolicy.defaultFor(Priority.P3), NOW);
        incident.setNeedsTriage(triage);
        return incidentRepository.save(incident);
    }

    private Incident load(String id) {
        return incidentRepository.findById(id).orElseThrow();
    }

    /** Puts the technician in a team other than the category's and gives them the category skill. */
    private Technician skilledOutsider(String technicianId, IncidentCategory category) {
        Technician technician = technicianRepository.findById(technicianId).orElseThrow();
        technician.addSkill(new Skill(OVERFLOW_SKILL, category));
        return technicianRepository.save(technician);
    }

    // ---- the contract, through every caller --------------------------------------------------------------------

    static Stream<Arguments> everyCallerAndCategory() {
        List<IncidentCategory> categories = List.of(IncidentCategory.NETWORK, IncidentCategory.DATABASE,
                IncidentCategory.CLOUD, IncidentCategory.SECURITY, IncidentCategory.HARDWARE, IncidentCategory.APPLICATION);
        return categories.stream().flatMap(category -> Stream.of(Caller.values()).map(caller -> Arguments.of(caller, category)));
    }

    /** Runs the ticket through the caller and returns the technician it ended with, or null when it waits. */
    private String assigneeAfter(Caller caller, IncidentCategory category) {
        switch (caller) {
            case INTAKE:
                // The intake assigns through TicketDispatchService right after the ticket commits
                return dispatchService.dispatchOne(planted(category, GENERAL_SERVICE, false).getId(), "SYSTEM")
                        .incident().getAssigneeId();
            case DISPATCHER: {
                Incident ticket = planted(category, GENERAL_SERVICE, false);
                dispatcher.dispatchWaitingIncidents();
                return load(ticket.getId()).getAssigneeId();
            }
            case RECLASSIFY: {
                Incident created = incidentService.createIncident(EMPLOYEE, "VPN gateway timeouts for the whole office",
                        "AnyConnect drops every few minutes with heavy packet loss on the office gateway tunnel.");
                IncidentCategory target = (category == IncidentCategory.NETWORK) ? IncidentCategory.DATABASE : category;
                adminService.reclassify(created.getId(), ADMIN_EMAIL, target, null, "Wrong domain");
                return load(created.getId()).getAssigneeId();
            }
            case ACCEPT_SUGGESTION: {
                Incident triage = planted(IncidentCategory.APPLICATION, GENERAL_SERVICE, true);
                adminService.acceptTriageSuggestion(triage.getId(), ADMIN_EMAIL, category, null);
                return load(triage.getId()).getAssigneeId();
            }
            default:
                throw new IllegalStateException();
        }
    }

    @ParameterizedTest(name = "{0}: a {1} ticket goes to the team that handles {1}")
    @MethodSource("everyCallerAndCategory")
    @DisplayName("ADR-036 contract: every caller gives the ticket to the team of its category")
    void everyCallerRoutesByCategory(Caller caller, IncidentCategory category) {
        IncidentCategory effective = (caller == Caller.RECLASSIFY && category == IncidentCategory.NETWORK)
                ? IncidentCategory.DATABASE : category;

        String assignee = assigneeAfter(caller, category);

        assertThat(assignee).as("%s gave the %s ticket a technician", caller, effective).isNotNull();
        assertThat(teamOf(assignee)).isEqualTo(teamHandling(effective));
    }

    // ---- the scenarios of the plan -----------------------------------------------------------------------------

    @Test
    @DisplayName("H9: a DNS ticket that matches no service goes to the Network team, not the Service Desk")
    void dnsTicketGoesToNetwork() {
        Incident created = incidentService.createIncident(EMPLOYEE, "DNS lookups failing for internal hosts",
                "Internal hostnames do not resolve and nslookup times out against the corporate DNS servers.");

        Incident ticket = load(created.getId());

        assertThat(ticket.getCategory()).isEqualTo(IncidentCategory.NETWORK);
        assertThat(ticket.getServiceId()).as("the service stays the general one: it only carries criticality")
                .isEqualTo(GENERAL_SERVICE);
        assertThat(ticket.getAssigneeId()).isNotNull();
        assertThat(teamOf(ticket.getAssigneeId())).isEqualTo(teamHandling(IncidentCategory.NETWORK));
    }

    @Test
    @DisplayName("D17: a ticket whose service is owned by another team goes to the team of its category")
    void serviceOwnerDoesNotDecide() {
        // SVC-PAYMENT belongs to the Cloud team; the ticket is a network ticket
        Incident ticket = planted(IncidentCategory.NETWORK, "SVC-PAYMENT", false);

        dispatchService.dispatchOne(ticket.getId(), "SYSTEM");

        assertThat(teamOf(load(ticket.getId()).getAssigneeId())).isEqualTo(teamHandling(IncidentCategory.NETWORK));
    }

    @Test
    @DisplayName("D18: with the owning team full the ticket goes to a skilled technician of another team, never an unskilled one")
    void fullTeamOverflowsToASkilledTechnician() {
        fillTeam(teamHandling(IncidentCategory.NETWORK));
        skilledOutsider("TECH-SEC-1", IncidentCategory.NETWORK);
        Incident ticket = planted(IncidentCategory.NETWORK, GENERAL_SERVICE, false);

        dispatchService.dispatchOne(ticket.getId(), "SYSTEM");

        String assignee = load(ticket.getId()).getAssigneeId();
        assertThat(assignee).isEqualTo("TECH-SEC-1");
        assertThat(technicianRepository.findById(assignee).orElseThrow().hasCategorySkill(IncidentCategory.NETWORK)).isTrue();
    }

    @Test
    @DisplayName("D18: when nobody skilled is free the ticket waits in CLASSIFIED, and the dispatcher applies the same rule")
    void ticketWaitsWhenNobodySkilledIsFree() {
        String networkTeam = teamHandling(IncidentCategory.NETWORK);
        fillTeam(networkTeam);
        Incident ticket = planted(IncidentCategory.NETWORK, GENERAL_SERVICE, false);

        TicketDispatchService.DispatchResult result = dispatchService.dispatchOne(ticket.getId(), "SYSTEM");
        dispatcher.dispatchWaitingIncidents();

        assertThat(result.outcome()).isEqualTo(TicketDispatchService.Outcome.NOT_ASSIGNED);
        Incident waiting = load(ticket.getId());
        assertThat(waiting.getStatus()).isEqualTo(IncidentStatus.CLASSIFIED);
        assertThat(waiting.getAssigneeId()).as("no technician of another team is forced in").isNull();

        clearSlots(); // the network team has capacity again
        dispatcher.dispatchWaitingIncidents();

        Incident assigned = load(ticket.getId());
        assertThat(assigned.getAssigneeId()).isNotNull();
        assertThat(teamOf(assigned.getAssigneeId())).isEqualTo(networkTeam);
    }

    @Test
    @DisplayName("D18: accept-suggestion follows the same rule: a full team without skilled help leaves the ticket waiting")
    void acceptSuggestionWaitsToo() {
        fillTeam(teamHandling(IncidentCategory.NETWORK));
        Incident triage = planted(IncidentCategory.APPLICATION, GENERAL_SERVICE, true);

        adminService.acceptTriageSuggestion(triage.getId(), ADMIN_EMAIL, IncidentCategory.NETWORK, null);

        Incident after = load(triage.getId());
        assertThat(after.getCategory()).isEqualTo(IncidentCategory.NETWORK);
        assertThat(after.getAssigneeId()).isNull();
        assertThat(after.getStatus()).isEqualTo(IncidentStatus.CLASSIFIED);
        assertThat(after.isNeedsTriage()).isFalse();
    }

    @Test
    @DisplayName("D17: the administrator's reclassify dialog sends no service, and the category's team receives the ticket")
    void reclassifyWithoutAServiceUsesTheCategoryTeam() {
        Incident created = incidentService.createIncident(EMPLOYEE, "VPN gateway timeouts for the whole office",
                "AnyConnect drops every few minutes with heavy packet loss on the office gateway tunnel.");
        String oldTech = created.getAssigneeId();

        adminService.reclassify(created.getId(), ADMIN_EMAIL, IncidentCategory.SECURITY, null, "Wrong domain");

        Incident after = load(created.getId());
        assertThat(after.getAssigneeId()).isNotNull().isNotEqualTo(oldTech);
        assertThat(teamOf(after.getAssigneeId())).isEqualTo(teamHandling(IncidentCategory.SECURITY));
        assertThat(new HashSet<>(technicianRepository.findById(oldTech).orElseThrow().getActiveIncidentIds()))
                .doesNotContain(created.getId());
    }
}
