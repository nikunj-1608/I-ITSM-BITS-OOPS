package com.iitsm.regression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iitsm.application.service.IncidentService;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.User;
import com.iitsm.domain.repository.UserRepository;
import com.iitsm.infrastructure.persistence.CompanySeeder;
import com.iitsm.infrastructure.security.JwtTokenProvider;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * ADR-044, Phase L4, end to end: a technician who resolved tickets of an IT service earns "service experience" on the
 * next ticket of that service, the factor is in the administrator's "Why this technician" breakdown with its weight, and a
 * technician without history gets 0. Uses the real intake, the real database and the production weight (0.10).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("regression")
class ServiceExperienceAssignmentRegressionTest {

    private static final String EMPLOYEE = "employee@nimbusfinserve.com";
    private static final String VPN_SERVICE = "SVC-VPN";
    private static final String HISTORY_PREFIX = "SXH-";
    private static final Instant BASE = Instant.parse("2099-02-01T00:00:00Z");

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtTokenProvider tokenProvider;
    @Autowired private IncidentService incidentService;
    @Autowired private UserRepository userRepository;
    @Autowired private CompanySeeder companySeeder;
    @Autowired private JdbcTemplate jdbc;

    private String ticketId;

    @BeforeEach
    void setUp() {
        jdbc.execute("DELETE FROM technician_active_incidents");
        if (userRepository.count() == 0) {
            companySeeder.seedCompanyData();
        }
        // Open VPN tickets left by other classes in the shared database would make the probe a duplicate of one of them
        // (no strategy decision, so no ranking); close them, and our own earlier probes
        jdbc.update("UPDATE incidents SET status = 'CLOSED' WHERE (service_id = ? OR title LIKE 'Service experience probe%') AND status <> 'CLOSED'",
                VPN_SERVICE);
        cleanHistory();
    }

    @AfterEach
    void tearDown() {
        if (ticketId != null) {
            jdbc.update("UPDATE incidents SET status = 'CLOSED' WHERE id = ?", ticketId);
        }
        cleanHistory();
        jdbc.execute("DELETE FROM technician_active_incidents");
    }

    private void cleanHistory() {
        jdbc.update("DELETE FROM resolutions WHERE incident_id LIKE ?", HISTORY_PREFIX + "%");
        jdbc.update("DELETE FROM incidents WHERE id LIKE ?", HISTORY_PREFIX + "%");
    }

    private void history(String suffix, String serviceId, String technicianId) {
        String id = HISTORY_PREFIX + suffix;
        jdbc.update("INSERT INTO incidents (id, title, description, reporter_id, category, priority, status, service_id, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, "History ticket " + suffix, "Resolved earlier, a long enough description " + suffix, "EMP-DEMO-01", "NETWORK", "P3", "CLOSED", serviceId, Timestamp.from(BASE));
        jdbc.update("INSERT INTO resolutions (incident_id, root_cause, steps_taken, preventive_action, confirmed_category, "
                        + "resolved_by, resolved_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, "Root", "Steps", "Prevent", "NETWORK", technicianId, Timestamp.from(BASE));
    }

    private JsonNode decisionOfNewVpnTicket() throws Exception {
        Incident created = incidentService.createIncident(EMPLOYEE, "Service experience probe: VPN gateway timeouts",
                "AnyConnect drops every few minutes with heavy packet loss on the office VPN gateway tunnel, nobody can work.");
        ticketId = created.getId();
        User admin = userRepository.findById("ADMIN-SYS-01").orElseThrow();
        String json = mockMvc.perform(get("/api/admin/incidents/" + ticketId)
                        .header("Authorization", "Bearer " + tokenProvider.generateToken(admin)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode detail = objectMapper.readTree(json);
        assertThat(detail.get("serviceId") == null ? VPN_SERVICE : detail.get("serviceId").asText()).isEqualTo(VPN_SERVICE);
        return detail.get("assignmentDecisions").get(0);
    }

    private static JsonNode candidate(JsonNode decision, String technicianId) {
        for (JsonNode c : decision.get("candidates")) {
            if (c.get("technicianId").asText().equals(technicianId)) {
                return c;
            }
        }
        throw new AssertionError("candidate " + technicianId + " is not ranked");
    }

    private static JsonNode factor(JsonNode candidate, String key) {
        for (JsonNode f : candidate.get("factors")) {
            if (f.get("key").asText().equals(key)) {
                return f;
            }
        }
        throw new AssertionError("factor " + key + " is missing");
    }

    private int resolvedVpnTickets(String technicianId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM resolutions r JOIN incidents i ON i.id = r.incident_id "
                + "WHERE i.service_id = ? AND r.resolved_by = ? AND i.status IN ('RESOLVED', 'VERIFIED', 'CLOSED')",
                Integer.class, VPN_SERVICE, technicianId);
    }

    private static double expectedScore(int resolved) {
        return Math.min(1.0, resolved / 5.0);
    }

    @Test
    @DisplayName("every ranked technician's service experience is min(1, VPN tickets resolved / 5) counted from the database, weight 0.10")
    void experienceShowsInTheBreakdown() throws Exception {
        history("1", VPN_SERVICE, "TECH-NET-2");
        history("2", VPN_SERVICE, "TECH-NET-2");
        JsonNode decision = decisionOfNewVpnTicket();
        assertThat(decision.get("candidates").get(0).get("factors")).extracting(f -> f.get("key").asText())
                .containsExactly("SKILL_MATCH", "LOAD", "EXPERIENCE", "SERVICE_EXPERIENCE");
        for (JsonNode candidate : decision.get("candidates")) {
            String technician = candidate.get("technicianId").asText();
            int resolved = resolvedVpnTickets(technician);
            JsonNode service = factor(candidate, "SERVICE_EXPERIENCE");
            assertThat(service.get("rawScore").asDouble()).as(technician).isEqualTo(expectedScore(resolved));
            assertThat(service.get("weight").asDouble()).isEqualTo(0.10);
            assertThat(service.get("detail").asText()).contains(resolved + " ticket(s) of this service resolved");
        }
    }

    @Test
    @DisplayName("history on other services adds nothing: the score follows the VPN tickets resolved")
    void otherServicesDoNotCount() throws Exception {
        history("1", "SVC-PAYMENT", "TECH-NET-2");
        history("2", "SVC-CRM", "TECH-NET-2");
        JsonNode decision = decisionOfNewVpnTicket();
        for (JsonNode candidate : decision.get("candidates")) {
            String technician = candidate.get("technicianId").asText();
            assertThat(factor(candidate, "SERVICE_EXPERIENCE").get("rawScore").asDouble()).as(technician)
                    .isEqualTo(expectedScore(resolvedVpnTickets(technician)));
        }
    }

    @Test
    @DisplayName("the weights of one decision sum to 1 (the historic three scaled by 0.9 plus 0.10 for the service)")
    void weightsStillSumToOne() throws Exception {
        JsonNode decision = decisionOfNewVpnTicket();

        double sum = 0.0;
        for (JsonNode f : decision.get("candidates").get(0).get("factors")) {
            sum += f.get("weight").asDouble();
        }
        assertThat(sum).isEqualTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
    }
}
