package com.iitsm.regression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.iitsm.application.service.AuditService;
import com.iitsm.application.service.IncidentService;
import com.iitsm.domain.model.AuditRecord;
import com.iitsm.domain.model.FactorContribution;
import com.iitsm.domain.model.Feedback;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.IncidentStatus;
import com.iitsm.domain.model.Priority;
import com.iitsm.domain.model.PriorityDecision;
import com.iitsm.domain.model.Resolution;
import com.iitsm.domain.model.SlaPolicy;
import com.iitsm.domain.model.User;
import com.iitsm.domain.repository.IncidentRepository;
import com.iitsm.domain.repository.UserRepository;
import com.iitsm.infrastructure.persistence.CompanySeeder;
import com.iitsm.infrastructure.security.JwtTokenProvider;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Remaining-defects plan, Phase C2 (ADR-028, decision D4): which administrator actions are allowed in which status.
 * A priority override is refused from RESOLVED on (the ticket's SLA verdict is final); a reclassify stays allowed in
 * every status but only relabels a finished ticket; accept-suggestion is only for tickets in triage. Every scenario
 * drives a real ticket into the state through the services and then makes the administrator's request over HTTP.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("regression")
class StatusRulesRegressionTest {

    private static final String EMPLOYEE = "employee@nimbusfinserve.com";
    private static final String REPORTER_ID = "EMP-DEMO-01";
    private static final String ADMIN_ID = "ADMIN-SYS-01";
    private static final String BAD_STATE = "ERR_INVALID_STATE_TRANSITION";
    private static final String NOT_IN_TRIAGE = "ERR_NOT_IN_TRIAGE";
    private static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");
    private static final Random RANDOM = new Random(41);

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider tokenProvider;
    @Autowired private IncidentService incidentService;
    @Autowired private IncidentRepository incidentRepository;
    @Autowired private AuditService auditService;
    @Autowired private UserRepository userRepository;
    @Autowired private CompanySeeder companySeeder;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM technician_active_incidents");
        if (userRepository.count() == 0) {
            companySeeder.seedCompanyData();
        }
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("DELETE FROM technician_active_incidents");
    }

    private static String word() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 9; i++) {
            sb.append((char) ('a' + RANDOM.nextInt(26)));
        }
        return sb.toString();
    }

    /** A new, auto-assigned ticket (status ASSIGNED). */
    private Incident assignedTicket() {
        StringBuilder description = new StringBuilder("The vpn and the gateway");
        for (int i = 0; i < 14; i++) {
            description.append(' ').append(word());
        }
        Incident created = incidentService.createIncident(EMPLOYEE, "VPN gateway " + word() + " " + word(),
                description.append(" vpn.").toString());
        Assumptions.assumeTrue(created.getAssigneeId() != null, "ticket must be auto-assigned");
        return created;
    }

    private Incident ticketIn(IncidentStatus target) {
        Incident ticket = assignedTicket();
        String id = ticket.getId();
        String technician = ticket.getAssigneeId();
        if (target == IncidentStatus.ASSIGNED) {
            return ticket;
        }
        incidentService.acknowledge(id, technician);
        if (target == IncidentStatus.ACKNOWLEDGED) {
            return reload(id);
        }
        incidentService.startInvestigation(id, technician);
        if (target == IncidentStatus.PENDING_INFO) {
            incidentService.requestInfo(id, technician, "Which office are you in?");
        }
        if (target.ordinal() >= IncidentStatus.RESOLVED.ordinal()) {
            incidentService.resolve(id, technician,
                    new Resolution("cause", "steps", "prevention", "NETWORK", technician, Instant.now()));
        }
        if (target.ordinal() >= IncidentStatus.VERIFIED.ordinal()) {
            incidentService.verify(id, REPORTER_ID);
        }
        if (target == IncidentStatus.CLOSED) {
            incidentService.close(id, REPORTER_ID, new Feedback(5, "Works now", Instant.now()));
        }
        Incident reached = reload(id);
        assertThat(reached.getStatus()).as("precondition: ticket reached the target state").isEqualTo(target);
        return reached;
    }

    private Incident reload(String id) {
        return incidentRepository.findById(id).orElseThrow();
    }

    private String adminToken() {
        User admin = userRepository.findById(ADMIN_ID).orElseThrow();
        return "Bearer " + tokenProvider.generateToken(admin);
    }

    private ResultActions adminPost(String path, String body) throws Exception {
        var request = post(path).header("Authorization", adminToken());
        return mockMvc.perform(body == null ? request : request.contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions override(Incident ticket, Priority priority) throws Exception {
        return adminPost("/api/admin/incidents/" + ticket.getId() + "/override-priority",
                "{\"priority\":\"" + priority + "\",\"reason\":\"status rule test\"}");
    }

    /** A confident ticket that waits for capacity: CLASSIFIED, no technician, not in triage. */
    private Incident waitingTicket() {
        Incident incident = new Incident("INC-RULE-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                "VPN gateway drops " + word(), "The vpn gateway keeps dropping connections " + word() + " " + word() + ".",
                REPORTER_ID, NOW);
        incident.classify(IncidentCategory.NETWORK, "SVC-VPN", new PriorityDecision(Priority.P3, 0.40,
                List.of(new FactorContribution("Crit", 0.4, 1.0, 0.4, "test")), List.of(), NOW, null, null),
                SlaPolicy.defaultFor(Priority.P3), NOW);
        return incidentRepository.save(incident);
    }

    // ---- Priority override --------------------------------------------------------------------------------------

    @Test
    @DisplayName("D4: a priority override is refused (409) on a RESOLVED, VERIFIED or CLOSED ticket and changes nothing")
    void overrideIsRefusedOnFinishedTickets() throws Exception {
        for (IncidentStatus target : List.of(IncidentStatus.RESOLVED, IncidentStatus.VERIFIED, IncidentStatus.CLOSED)) {
            Incident ticket = ticketIn(target);
            Priority before = ticket.getPriorityDecision().priority();
            Priority other = before == Priority.P1 ? Priority.P4 : Priority.P1;

            override(ticket, other)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value(BAD_STATE));

            Incident stored = reload(ticket.getId());
            assertThat(stored.getPriorityDecision().priority()).as(target.name()).isEqualTo(before);
            assertThat(stored.getPriorityDecision().isOverridden()).as(target.name()).isFalse();
            assertThat(stored.getSlaTracker().getStatus()).as(target.name()).isEqualTo(ticket.getSlaTracker().getStatus());
        }
    }

    @Test
    @DisplayName("D4 control: a priority override still works while the ticket is open (ASSIGNED, IN_PROGRESS, PENDING_INFO)")
    void overrideStillWorksOnOpenTickets() throws Exception {
        for (IncidentStatus target : List.of(IncidentStatus.ASSIGNED, IncidentStatus.IN_PROGRESS, IncidentStatus.PENDING_INFO)) {
            Incident ticket = ticketIn(target);
            Priority other = ticket.getPriorityDecision().priority() == Priority.P1 ? Priority.P4 : Priority.P1;

            override(ticket, other).andExpect(status().isOk());

            assertThat(reload(ticket.getId()).getPriorityDecision().priority()).as(target.name()).isEqualTo(other);
        }
    }

    // ---- Reclassify ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("D4: a reclassify of a CLOSED ticket only relabels it: category changes, priority, SLA verdict and technician do not")
    void reclassifyOfAClosedTicketOnlyRelabelsIt() throws Exception {
        Incident ticket = ticketIn(IncidentStatus.CLOSED);
        IncidentCategory other = ticket.getCategory() == IncidentCategory.SECURITY ? IncidentCategory.HARDWARE : IncidentCategory.SECURITY;

        adminPost("/api/admin/incidents/" + ticket.getId() + "/reclassify",
                "{\"category\":\"" + other + "\",\"reason\":\"relabel for the report\"}").andExpect(status().isOk());

        Incident stored = reload(ticket.getId());
        assertThat(stored.getCategory()).isEqualTo(other);
        assertThat(stored.getStatus()).isEqualTo(IncidentStatus.CLOSED);
        assertThat(stored.getAssigneeId()).isEqualTo(ticket.getAssigneeId());
        assertThat(stored.getPriorityDecision().priority()).isEqualTo(ticket.getPriorityDecision().priority());
        assertThat(stored.getSlaTracker().getStatus()).isEqualTo(ticket.getSlaTracker().getStatus());
        assertThat(stored.getSlaTracker().getResolvedAt()).isEqualTo(ticket.getSlaTracker().getResolvedAt());
        assertThat(stored.getSlaTracker().getResolutionDueAt()).isEqualTo(ticket.getSlaTracker().getResolutionDueAt());
    }

    // ---- Accept-suggestion ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("D4: accept-suggestion is refused (409) on a ticket that is not in triage: waiting for capacity, or already worked on")
    void acceptSuggestionNeedsATriageTicket() throws Exception {
        Incident waiting = waitingTicket();
        Incident resolved = ticketIn(IncidentStatus.RESOLVED);

        for (Incident ticket : List.of(waiting, resolved)) {
            adminPost("/api/admin/triage/" + ticket.getId() + "/accept-suggestion", "{\"category\":\"NETWORK\"}")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value(NOT_IN_TRIAGE));
        }

        Incident stored = reload(waiting.getId());
        assertThat(stored.getStatus()).isEqualTo(IncidentStatus.CLASSIFIED);
        assertThat(stored.getAssigneeId()).as("the dispatcher, not accept-suggestion, assigns a waiting ticket").isNull();
    }

    @Test
    @DisplayName("Accept-suggestion on a ticket in triage assigns it and announces the assignment (the technician is notified and it is audited)")
    void acceptSuggestionAnnouncesTheAssignment() throws Exception {
        Incident ticket = waitingTicket();
        ticket.setNeedsTriage(true);
        incidentRepository.save(ticket);

        adminPost("/api/admin/triage/" + ticket.getId() + "/accept-suggestion", "{\"category\":\"NETWORK\"}")
                .andExpect(status().isOk());

        Incident stored = reload(ticket.getId());
        assertThat(stored.getAssigneeId()).isNotNull();
        List<AuditRecord> trail = auditService.getAuditTrailForIncident(ticket.getId());
        assertThat(trail).as("IncidentAssigned is published, so the audit trail and the notification see it")
                .anyMatch(r -> "IncidentAssigned".equals(r.getAction()));
    }
}
