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
 * Remaining-defects plan, Phase H (ADR-037, decision D19, CLAUDE.md section 7 item 13): the activity history an employee
 * sees quoted the id of the ticket a report duplicates and the reason a technician gave for handing a ticket back. Both
 * entries now read neutrally; the full text stays in the audit trail the staff see.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("regression")
class EmployeeHistoryPrivacyRegressionTest {

    private static final String EMPLOYEE = "employee@nimbusfinserve.com";
    private static final String ADMIN_ID = "ADMIN-SYS-01";
    private static final String TECHNICIAN_ID = "TECH-NET-2";
    private static final String TECHNICIAN_REASON = "Belongs to the cloud platform team, not to network operations";

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

    private void send(String url, String token, Object body) throws Exception {
        var request = post(url).header("Authorization", token);
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        }
        mockMvc.perform(request).andExpect(status().is2xxSuccessful());
    }

    private JsonNode getJson(String url, String token) throws Exception {
        String json = mockMvc.perform(get(url).header("Authorization", token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json);
    }

    @Test
    @DisplayName("D19: no employee view contains another ticket's id or the technician's reason")
    void employeeViewsLeakNothingAboutOtherTickets() throws Exception {
        String text = "AnyConnect drops every few minutes with heavy packet loss on the office gateway tunnel.";
        Incident first = incidentService.createIncident(EMPLOYEE, "VPN gateway timeouts for the whole office", text);
        Incident second = incidentService.createIncident(EMPLOYEE, "VPN gateway timeouts for the whole office", text);
        createdTickets.add(first.getId());
        createdTickets.add(second.getId());
        send("/api/admin/incidents/" + first.getId() + "/assign", tokenOf(ADMIN_ID), Map.of("technicianId", TECHNICIAN_ID));
        send("/api/technician/incidents/" + first.getId() + "/flag-wrong-domain", tokenOf(TECHNICIAN_ID),
                Map.of("reason", TECHNICIAN_REASON));

        Integer links = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_records WHERE action = 'DuplicateDetected' AND incident_id IN (?, ?)",
                Integer.class, first.getId(), second.getId());
        Integer flags = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_records WHERE action = 'ReassignmentRequested' AND incident_id = ?",
                Integer.class, first.getId());
        assertThat(links).as("the second identical report is linked as a duplicate").isPositive();
        assertThat(flags).as("the flag is audited").isEqualTo(1);

        List<String> allTicketIds = jdbcTemplate.queryForList("SELECT id FROM incidents", String.class);
        String token = tokenOf(first.getReporterId());
        List<JsonNode> views = new ArrayList<>();
        getJson("/api/employee/incidents", token).forEach(views::add);
        views.add(getJson("/api/employee/incidents/" + first.getId(), token));
        views.add(getJson("/api/employee/incidents/" + second.getId(), token));

        assertThat(views).isNotEmpty();
        for (JsonNode view : views) {
            String own = view.get("id").asText();
            String json = view.toString();
            assertThat(allTicketIds).contains(own);
            for (String other : allTicketIds) {
                if (!other.equals(own)) {
                    assertThat(json).as("view of %s must not name %s", own, other).doesNotContain(other);
                }
            }
            assertThat(json).doesNotContain(TECHNICIAN_REASON).doesNotContain("requested reassignment");
        }
        JsonNode firstView = getJson("/api/employee/incidents/" + first.getId(), token);
        assertThat(firstView.get("auditTrail").toString()).contains("ReassignmentRequested");
    }
}
