package com.iitsm.regression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iitsm.application.service.IncidentService;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.Priority;
import com.iitsm.domain.model.SlaPolicy;
import com.iitsm.domain.model.User;
import com.iitsm.domain.repository.IncidentRepository;
import com.iitsm.domain.repository.UserRepository;
import com.iitsm.infrastructure.persistence.CompanySeeder;
import com.iitsm.infrastructure.security.JwtTokenProvider;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * H6, phase 4: the override seen through the HTTP API and the three persona views. Admin and technician see the
 * new deadlines and the new display name; the employee sees nothing about priority, SLA or the override.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("regression")
class PriorityOverrideSlaHttpTest {

    private static final String EMPLOYEE = "employee@nimbusfinserve.com";
    private static final String ADMIN_ID = "ADMIN-SYS-01";
    private static final String REASON = "Board escalation for the trading floor";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtTokenProvider tokenProvider;
    @Autowired private IncidentService incidentService;
    @Autowired private IncidentRepository incidentRepository;
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

    private String tokenOf(String userId) {
        User user = userRepository.findById(userId).orElseThrow();
        return "Bearer " + tokenProvider.generateToken(user);
    }

    private Incident ticket() {
        Incident created = incidentService.createIncident(EMPLOYEE, "VPN gateway timeouts for the whole office",
                "AnyConnect drops every few minutes with heavy packet loss on the office gateway tunnel.");
        Assumptions.assumeTrue(created.getAssigneeId() != null, "ticket must be auto-assigned");
        return created;
    }

    private String employeeToken(Incident ticket) {
        return tokenOf(ticket.getReporterId());
    }

    private int override(String token, String ticketId, Map<String, Object> body) throws Exception {
        return mockMvc.perform(post("/api/admin/incidents/" + ticketId + "/override-priority")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn().getResponse().getStatus();
    }

    private JsonNode getJson(String url, String token) throws Exception {
        String json = mockMvc.perform(get(url).header("Authorization", token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json);
    }

    // ---- admin ------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "[{index}] admin override to {0} returns 200 and the {0} resolution deadline")
    @EnumSource(Priority.class)
    void adminResponseCarriesTheNewDeadline(Priority target) throws Exception {
        Incident created = ticket();
        SlaPolicy policy = SlaPolicy.defaultFor(target);

        String json = mockMvc.perform(post("/api/admin/incidents/" + created.getId() + "/override-priority")
                        .header("Authorization", tokenOf(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("priority", target.name(), "reason", REASON))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priorityDecision.priority").value(target.name()))
                .andExpect(jsonPath("$.displayId").value(target.name() + "-" + created.getId()))
                .andReturn().getResponse().getContentAsString();

        JsonNode view = objectMapper.readTree(json);
        JsonNode tracker = view.get("slaTracker");
        java.time.Instant createdAt = java.time.Instant.parse(view.get("createdAt").asText());
        assertThat(java.time.Instant.parse(tracker.get("resolutionDueAt").asText()))
                .isEqualTo(createdAt.plus(policy.getResolutionTarget()));
        assertThat(java.time.Instant.parse(tracker.get("responseDueAt").asText()))
                .isEqualTo(createdAt.plus(policy.getResponseTarget()));
    }

    @Test
    @DisplayName("H6: the admin detail view of the ticket shows the new deadline and the override record")
    void adminDetailShowsTheOverride() throws Exception {
        Incident created = ticket();
        override(tokenOf(ADMIN_ID), created.getId(), Map.of("priority", "P1", "reason", REASON));

        JsonNode detail = getJson("/api/admin/incidents/" + created.getId(), tokenOf(ADMIN_ID));

        assertThat(detail.get("displayId").asText()).isEqualTo("P1-" + created.getId());
        assertThat(detail.get("priorityDecision").get("overrideReason").asText()).isEqualTo(REASON);
        java.time.Instant createdAt = java.time.Instant.parse(detail.get("createdAt").asText());
        assertThat(java.time.Instant.parse(detail.get("slaTracker").get("resolutionDueAt").asText()))
                .isEqualTo(createdAt.plus(SlaPolicy.defaultFor(Priority.P1).getResolutionTarget()));
    }

    @Test
    @DisplayName("H6: the admin detail can be opened by the new display name (P1-...)")
    void adminDetailByDisplayName() throws Exception {
        Incident created = ticket();
        override(tokenOf(ADMIN_ID), created.getId(), Map.of("priority", "P1", "reason", REASON));

        mockMvc.perform(get("/api/admin/incidents/P1-" + created.getId()).header("Authorization", tokenOf(ADMIN_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(created.getId()));
    }

    @Test
    @DisplayName("H6: the Command Centre list filtered by P1 finds the overridden ticket")
    void listFilterFindsOverriddenTicket() throws Exception {
        Incident created = ticket();
        override(tokenOf(ADMIN_ID), created.getId(), Map.of("priority", "P1", "reason", REASON));

        String json = mockMvc.perform(get("/api/admin/incidents?priority=P1&query=" + created.getId())
                        .header("Authorization", tokenOf(ADMIN_ID)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(json).contains(created.getId());
    }

    // ---- validation and authorisation ----------------------------------------------------------------------

    @ParameterizedTest(name = "[{index}] reason \"{0}\" gives 400 and leaves the deadline alone")
    @ValueSource(strings = {"", " ", "   "})
    void blankReasonIs400(String reason) throws Exception {
        Incident created = ticket();
        java.time.Instant before = incidentRepository.findById(created.getId()).orElseThrow()
                .getSlaTracker().getResolutionDueAt();

        assertThat(override(tokenOf(ADMIN_ID), created.getId(), Map.of("priority", "P1", "reason", reason)))
                .isEqualTo(400);

        assertThat(incidentRepository.findById(created.getId()).orElseThrow().getSlaTracker().getResolutionDueAt())
                .isEqualTo(before);
    }

    @Test
    @DisplayName("H6: a missing priority is 400")
    void missingPriorityIs400() throws Exception {
        Incident created = ticket();

        assertThat(override(tokenOf(ADMIN_ID), created.getId(), Map.of("reason", REASON))).isEqualTo(400);
    }

    @Test
    @DisplayName("H6: a missing reason is 400")
    void missingReasonIs400() throws Exception {
        Incident created = ticket();

        assertThat(override(tokenOf(ADMIN_ID), created.getId(), Map.of("priority", "P1"))).isEqualTo(400);
    }

    @Test
    @DisplayName("H6: an unknown ticket is 404")
    void unknownTicketIs404() throws Exception {
        assertThat(override(tokenOf(ADMIN_ID), "ZZZ-2026-999999", Map.of("priority", "P1", "reason", REASON)))
                .isEqualTo(404);
    }

    @Test
    @DisplayName("H6: an employee gets 403 and the deadline does not move")
    void employeeIsForbidden() throws Exception {
        Incident created = ticket();
        java.time.Instant before = incidentRepository.findById(created.getId()).orElseThrow()
                .getSlaTracker().getResolutionDueAt();

        assertThat(override(employeeToken(created), created.getId(), Map.of("priority", "P1", "reason", REASON)))
                .isEqualTo(403);

        assertThat(incidentRepository.findById(created.getId()).orElseThrow().getSlaTracker().getResolutionDueAt())
                .isEqualTo(before);
    }

    @Test
    @DisplayName("H6: a technician gets 403 and the deadline does not move")
    void technicianIsForbidden() throws Exception {
        Incident created = ticket();
        java.time.Instant before = incidentRepository.findById(created.getId()).orElseThrow()
                .getSlaTracker().getResolutionDueAt();

        assertThat(override(tokenOf(created.getAssigneeId()), created.getId(),
                Map.of("priority", "P1", "reason", REASON))).isEqualTo(403);

        assertThat(incidentRepository.findById(created.getId()).orElseThrow().getSlaTracker().getResolutionDueAt())
                .isEqualTo(before);
    }

    @Test
    @DisplayName("H6: a request without a token is rejected")
    void anonymousIsRejected() throws Exception {
        Incident created = ticket();

        int code = mockMvc.perform(post("/api/admin/incidents/" + created.getId() + "/override-priority")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"priority\":\"P1\",\"reason\":\"x\"}"))
                .andReturn().getResponse().getStatus();

        assertThat(code).isIn(401, 403);
    }

    // ---- technician ----------------------------------------------------------------------------------------

    @Test
    @DisplayName("H6: the assigned technician sees the new deadlines and display name")
    void technicianViewShowsTheNewDeadline() throws Exception {
        Incident created = ticket();
        override(tokenOf(ADMIN_ID), created.getId(), Map.of("priority", "P1", "reason", REASON));

        JsonNode view = getJson("/api/technician/incidents/" + created.getId(), tokenOf(created.getAssigneeId()));

        assertThat(view.get("displayId").asText()).isEqualTo("P1-" + created.getId());
        java.time.Instant createdAt = incidentRepository.findById(created.getId()).orElseThrow()
                .getSlaTracker().getCreatedAt();
        assertThat(java.time.Instant.parse(view.get("resolutionDueAt").asText()))
                .isEqualTo(createdAt.plus(SlaPolicy.defaultFor(Priority.P1).getResolutionTarget()));
    }

    // ---- employee privacy ------------------------------------------------------------------------------------

    @Test
    @DisplayName("H6: the employee's ticket JSON names no priority, SLA, display name or override after an override")
    void employeeSeesNothingAboutThePriority() throws Exception {
        Incident created = ticket();
        override(tokenOf(ADMIN_ID), created.getId(), Map.of("priority", "P1", "reason", REASON));

        String json = mockMvc.perform(get("/api/employee/incidents/" + created.getId())
                        .header("Authorization", employeeToken(created)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(json).doesNotContainIgnoringCase("priority")
                .doesNotContainIgnoringCase("sla")
                .doesNotContain("resolutionDueAt")
                .doesNotContain("responseDueAt")
                .doesNotContain("displayId")
                .doesNotContain("P1-")
                .doesNotContain(REASON)
                .doesNotContain("PriorityOverridden")
                .doesNotContain("SlaViolated");
    }

    @Test
    @DisplayName("H6: the employee's ticket list leaks nothing about the override either")
    void employeeListIsClean() throws Exception {
        Incident created = ticket();
        override(tokenOf(ADMIN_ID), created.getId(), Map.of("priority", "P1", "reason", REASON));

        JsonNode list = getJson("/api/employee/incidents", employeeToken(created));

        JsonNode entry = null;
        for (JsonNode candidate : list) {
            if (created.getId().equals(candidate.path("id").asText())) {
                entry = candidate;
            }
        }
        assertThat(entry).as("the overridden ticket is in the employee's list").isNotNull();
        assertThat(entry.fieldNames()).toIterable()
                .noneMatch(name -> name.toLowerCase().contains("priority") || name.toLowerCase().contains("sla")
                        || name.toLowerCase().contains("due") || name.equals("displayId"));
        assertThat(entry.toString()).doesNotContain(REASON).doesNotContain("P1-");
    }

    @Test
    @DisplayName("H6: the employee's activity history has no entry about the priority change")
    void employeeHistoryHasNoOverrideEntry() throws Exception {
        Incident created = ticket();
        override(tokenOf(ADMIN_ID), created.getId(), Map.of("priority", "P1", "reason", REASON));

        JsonNode view = getJson("/api/employee/incidents/" + created.getId(), employeeToken(created));

        for (JsonNode record : view.path("auditTrail")) {
            assertThat(record.path("action").asText()).isNotIn("PriorityOverridden", "PriorityCalculated",
                    "SlaWarning", "SlaViolated", "IncidentEscalated");
            assertThat(record.path("description").asText()).doesNotContain("Priority changed");
        }
    }

    @Test
    @DisplayName("H6: another employee cannot read the overridden ticket")
    void otherEmployeeIsForbidden() throws Exception {
        Incident created = ticket();
        override(tokenOf(ADMIN_ID), created.getId(), Map.of("priority", "P1", "reason", REASON));
        User other = userRepository.findAll().stream()
                .filter(u -> u.getRole() == com.iitsm.domain.model.UserRole.ROLE_EMPLOYEE
                        && !u.getId().equals(created.getReporterId()))
                .findFirst().orElse(null);
        Assumptions.assumeTrue(other != null);

        int code = mockMvc.perform(get("/api/employee/incidents/" + created.getId())
                        .header("Authorization", tokenOf(other.getId())))
                .andReturn().getResponse().getStatus();

        assertThat(code).isEqualTo(403);
    }
}
