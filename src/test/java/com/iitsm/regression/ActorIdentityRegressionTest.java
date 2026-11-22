package com.iitsm.regression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iitsm.application.service.IncidentService;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.User;
import com.iitsm.domain.repository.UserRepository;
import com.iitsm.infrastructure.persistence.CompanySeeder;
import com.iitsm.infrastructure.security.JwtTokenProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
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

/**
 * Remaining-defects plan, Phase H (ADR-037, decision D20, CLAUDE.md section 7 item 2): the signed-in user's login
 * e-mail was stored as the actor of an assignment, a priority override, a note author, the resolver and the audit
 * entries. Now every stored actor is the user's id, and the staff views show the name beside it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("regression")
class ActorIdentityRegressionTest {

    private static final String EMPLOYEE = "employee@nimbusfinserve.com";
    private static final String ADMIN_ID = "ADMIN-SYS-01";
    private static final String TECHNICIAN_ID = "TECH-NET-2";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtTokenProvider tokenProvider;
    @Autowired private IncidentService incidentService;
    @Autowired private UserRepository userRepository;
    @Autowired private CompanySeeder companySeeder;
    @Autowired private JdbcTemplate jdbcTemplate;

    /** Tickets created by this class are closed afterwards: open ones would become duplicate parents for other classes. */
    private final java.util.List<String> createdTickets = new java.util.ArrayList<>();

    private void closeCreatedTickets() {
        createdTickets.forEach(id -> jdbcTemplate.update("UPDATE incidents SET status = 'CLOSED' WHERE id = ?", id));
        createdTickets.clear();
    }

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM technician_active_incidents");
        if (userRepository.count() == 0) {
            companySeeder.seedCompanyData();
        }
    }

    @AfterEach
    void tearDown() {
        closeCreatedTickets();
        jdbcTemplate.execute("DELETE FROM technician_active_incidents");
    }

    private String tokenOf(String userId) {
        User user = userRepository.findById(userId).orElseThrow();
        return "Bearer " + tokenProvider.generateToken(user);
    }

    private String nameOf(String userId) {
        return userRepository.findById(userId).orElseThrow().getName();
    }

    private JsonNode send(String url, String token, Object body) throws Exception {
        var request = post(url).header("Authorization", token);
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        }
        String json = mockMvc.perform(request).andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
        return json.isBlank() ? null : objectMapper.readTree(json);
    }

    private JsonNode getJson(String url, String token) throws Exception {
        String json = mockMvc.perform(get(url).header("Authorization", token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json);
    }

    /** A network ticket put explicitly with TECH-NET-2 by the administrator (that is the assign call under test). */
    private Incident assignedTicket() throws Exception {
        Incident created = incidentService.createIncident(EMPLOYEE, "VPN gateway timeouts for the whole office",
                "AnyConnect drops every few minutes with heavy packet loss on the office gateway tunnel.");
        createdTickets.add(created.getId());
        send("/api/admin/incidents/" + created.getId() + "/assign", tokenOf(ADMIN_ID), Map.of("technicianId", TECHNICIAN_ID));
        return created;
    }

    private List<String> actorsInAuditTrail(String ticketId) throws Exception {
        JsonNode detail = getJson("/api/admin/incidents/" + ticketId, tokenOf(ADMIN_ID));
        List<String> actors = new ArrayList<>();
        detail.get("auditTrail").forEach(record -> actors.add(record.get("actorId").asText()));
        return actors;
    }

    @Test
    @DisplayName("D20: assign, override, note and resolve store the user's id, never the login e-mail")
    void storedActorsAreIds() throws Exception {
        Incident ticket = assignedTicket();
        String id = ticket.getId();

        send("/api/admin/incidents/" + id + "/override-priority", tokenOf(ADMIN_ID),
                Map.of("priority", "P1", "reason", "Board escalation for the trading floor"));
        String technician = tokenOf(TECHNICIAN_ID);
        send("/api/technician/incidents/" + id + "/acknowledge", technician, null);
        send("/api/technician/incidents/" + id + "/start-investigation", technician, null);
        send("/api/technician/incidents/" + id + "/notes", technician, Map.of("noteText", "Checked the gateway", "internalOnly", true));
        send("/api/technician/incidents/" + id + "/resolve", technician, Map.of("rootCause", "Gateway firmware bug",
                "stepsTaken", "Rolled the gateway back", "confirmedCategory", "NETWORK"));

        JsonNode detail = getJson("/api/admin/incidents/" + id, tokenOf(ADMIN_ID));
        assertThat(detail.get("priorityDecision").get("overriddenBy").asText()).isEqualTo(ADMIN_ID);
        assertThat(detail.get("notes").get(0).get("authorId").asText()).isEqualTo(TECHNICIAN_ID);
        assertThat(detail.get("notes").get(0).get("authorName").asText()).isEqualTo(nameOf(TECHNICIAN_ID));
        assertThat(detail.get("resolution").get("resolvedBy").asText()).isEqualTo(TECHNICIAN_ID);
        assertThat(actorsInAuditTrail(id)).isNotEmpty().noneMatch(actor -> actor.contains("@"));
        assertThat(actorsInAuditTrail(id)).contains(ADMIN_ID, TECHNICIAN_ID);
    }

    @Test
    @DisplayName("D20: the employee's own actions (verify, close) are recorded under the employee's id")
    void employeeActionsStoreTheId() throws Exception {
        Incident ticket = assignedTicket();
        String id = ticket.getId();
        String technician = tokenOf(TECHNICIAN_ID);
        send("/api/technician/incidents/" + id + "/acknowledge", technician, null);
        send("/api/technician/incidents/" + id + "/start-investigation", technician, null);
        send("/api/technician/incidents/" + id + "/resolve", technician, Map.of("rootCause", "Gateway firmware bug",
                "stepsTaken", "Rolled the gateway back", "confirmedCategory", "NETWORK"));
        String employeeToken = tokenOf(ticket.getReporterId());
        send("/api/employee/incidents/" + id + "/verify", employeeToken, null);
        send("/api/employee/incidents/" + id + "/feedback", employeeToken, Map.of("rating", 5, "comment", "Thanks"));

        List<String> actors = actorsInAuditTrail(id);
        assertThat(actors).noneMatch(actor -> actor.contains("@"));
        assertThat(actors).contains(ticket.getReporterId());
    }

    @Test
    @DisplayName("D20: the staff views show the name beside the id (override, resolver and audit actor)")
    void staffViewsShowNames() throws Exception {
        Incident ticket = assignedTicket();
        String id = ticket.getId();
        send("/api/admin/incidents/" + id + "/override-priority", tokenOf(ADMIN_ID),
                Map.of("priority", "P2", "reason", "Several departments are affected"));
        String technician = tokenOf(TECHNICIAN_ID);
        send("/api/technician/incidents/" + id + "/acknowledge", technician, null);
        send("/api/technician/incidents/" + id + "/start-investigation", technician, null);
        send("/api/technician/incidents/" + id + "/resolve", technician, Map.of("rootCause", "Gateway firmware bug",
                "stepsTaken", "Rolled the gateway back", "confirmedCategory", "NETWORK"));

        JsonNode detail = getJson("/api/admin/incidents/" + id, tokenOf(ADMIN_ID));
        assertThat(detail.get("priorityDecision").get("overriddenByName").asText()).isEqualTo(nameOf(ADMIN_ID));
        assertThat(detail.get("resolution").get("resolvedByName").asText()).isEqualTo(nameOf(TECHNICIAN_ID));
        boolean everyActorNamed = true;
        for (JsonNode record : detail.get("auditTrail")) {
            String actor = record.get("actorId").asText();
            if (userRepository.findById(actor).isPresent()) {
                everyActorNamed &= record.get("actorName").asText().equals(nameOf(actor));
            }
        }
        assertThat(everyActorNamed).as("every audit actor that is a user carries that user's name").isTrue();

        JsonNode technicianView = getJson("/api/technician/incidents/" + id, technician);
        assertThat(technicianView.get("resolution").get("resolvedByName").asText()).isEqualTo(nameOf(TECHNICIAN_ID));
    }

    @Test
    @DisplayName("D20: services called with an e-mail instead of an id still store the id")
    void servicesNormaliseTheActor() throws Exception {
        Incident ticket = assignedTicket();
        String id = ticket.getId();
        String technicianEmail = userRepository.findById(TECHNICIAN_ID).orElseThrow().getEmail().value();
        String adminEmail = userRepository.findById(ADMIN_ID).orElseThrow().getEmail().value();

        incidentService.overridePriority(id, adminEmail, com.iitsm.domain.model.Priority.P1, "Board escalation");
        incidentService.acknowledge(id, technicianEmail);
        incidentService.startInvestigation(id, technicianEmail);
        incidentService.addNote(id, technicianEmail, "Checked the gateway", true);
        incidentService.resolve(id, technicianEmail, new com.iitsm.domain.model.Resolution("Gateway firmware bug",
                "Rolled the gateway back", "None", "NETWORK", technicianEmail, java.time.Instant.parse("2026-11-07T10:00:00Z")));

        Incident stored = incidentService.getIncident(ADMIN_ID, id);
        assertThat(stored.getPriorityDecision().overriddenBy()).isEqualTo(ADMIN_ID);
        assertThat(stored.getNotes().get(0).authorId()).isEqualTo(TECHNICIAN_ID);
        assertThat(stored.getNotes().get(0).authorName()).isEqualTo(nameOf(TECHNICIAN_ID));
        assertThat(stored.getResolution().resolvedBy()).isEqualTo(TECHNICIAN_ID);
        assertThat(actorsInAuditTrail(id)).noneMatch(actor -> actor.contains("@"));
    }
}
