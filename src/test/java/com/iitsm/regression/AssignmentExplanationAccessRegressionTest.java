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
import com.iitsm.web.dto.AdminIncidentView;
import com.iitsm.web.dto.EmployeeIncidentView;
import com.iitsm.web.dto.TechnicianIncidentView;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
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
 * ADR-044: "Why this technician" is for administrators only. Candidates' scores reveal colleagues' load and experience,
 * so the decisions are in the administrator's detail view and in no technician or employee response; this is proven
 * through the response types and over HTTP, not only through the screen.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("regression")
class AssignmentExplanationAccessRegressionTest {

    private static final String EMPLOYEE_EMAIL = "employee@nimbusfinserve.com";
    private static final String EMPLOYEE_ID = "EMP-DEMO-01";
    private static final String ADMIN_ID = "ADMIN-SYS-01";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtTokenProvider tokenProvider;
    @Autowired private IncidentService incidentService;
    @Autowired private UserRepository userRepository;
    @Autowired private CompanySeeder companySeeder;
    @Autowired private JdbcTemplate jdbcTemplate;

    private String ticketId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM technician_active_incidents");
        if (userRepository.count() == 0) {
            companySeeder.seedCompanyData();
        }
        // An open ticket with the same text from another class would make this one a duplicate (no strategy decision)
        jdbcTemplate.update("UPDATE incidents SET status = 'CLOSED' WHERE title = ? AND status <> 'CLOSED'",
                "VPN gateway timeouts for the whole office");
        Incident created = incidentService.createIncident(EMPLOYEE_EMAIL, "VPN gateway timeouts for the whole office",
                "AnyConnect drops every few minutes with heavy packet loss on the office gateway tunnel.");
        ticketId = created.getId();
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("UPDATE incidents SET status = 'CLOSED' WHERE id = ?", ticketId);
        jdbcTemplate.execute("DELETE FROM technician_active_incidents");
    }

    private String tokenOf(String userId) {
        User user = userRepository.findById(userId).orElseThrow();
        return "Bearer " + tokenProvider.generateToken(user);
    }

    private String body(String url, String token, int expectedStatus) throws Exception {
        var request = get(url);
        if (token != null) {
            request = request.header("Authorization", token);
        }
        return mockMvc.perform(request).andExpect(status().is(expectedStatus)).andReturn().getResponse().getContentAsString();
    }

    private String assignee() {
        return jdbcTemplate.queryForObject("SELECT assignee_id FROM incidents WHERE id = ?", String.class, ticketId);
    }

    @Test
    @DisplayName("the administrator's detail explains the assignment: pool, strategy, sentence, candidates, factors, names")
    void administratorSeesTheExplanation() throws Exception {
        JsonNode detail = objectMapper.readTree(body("/api/admin/incidents/" + ticketId, tokenOf(ADMIN_ID), 200));
        JsonNode decision = detail.get("assignmentDecisions").get(0);

        assertThat(decision.get("poolKind").asText()).isEqualTo("OWNING_TEAM");
        assertThat(decision.get("strategyName").asText()).isEqualTo("WEIGHTED_COMPOSITE");
        assertThat(decision.get("chosenTechnicianId").asText()).isEqualTo(assignee());
        assertThat(decision.get("chosenTechnicianName").asText()).isNotBlank();
        assertThat(decision.get("summary").asText()).contains("WEIGHTED_COMPOSITE");
        JsonNode top = decision.get("candidates").get(0);
        assertThat(top.get("chosen").asBoolean()).isTrue();
        assertThat(top.get("rank").asInt()).isEqualTo(1);
        assertThat(top.get("technicianName").asText()).isNotBlank();
        List<String> factorKeys = top.get("factors").findValuesAsText("key");
        assertThat(factorKeys).containsExactly("SKILL_MATCH", "LOAD", "EXPERIENCE", "SERVICE_EXPERIENCE");
        JsonNode factor = top.get("factors").get(0);
        assertThat(factor.get("contribution").asDouble())
                .isEqualTo(factor.get("rawScore").asDouble() * factor.get("weight").asDouble());
    }

    @Test
    @DisplayName("the assigned technician cannot open the admin detail, and their own ticket view has no candidates")
    void technicianSeesNoCandidates() throws Exception {
        String technician = tokenOf(assignee());

        body("/api/admin/incidents/" + ticketId, technician, 403);
        String own = body("/api/technician/incidents/" + ticketId, technician, 200);
        String queue = body("/api/technician/queue", technician, 200);

        for (String json : List.of(own, queue)) {
            assertThat(json).doesNotContain("assignmentDecisions", "candidates", "candidateCount", "SKILL_MATCH");
        }
    }

    @Test
    @DisplayName("the employee cannot open the admin detail and sees nothing of the explanation")
    void employeeSeesNothing() throws Exception {
        String employee = tokenOf(EMPLOYEE_ID);

        body("/api/admin/incidents/" + ticketId, employee, 403);
        String own = body("/api/employee/incidents/" + ticketId, employee, 200);
        assertThat(own).doesNotContain("assignmentDecisions", "candidates", "SKILL_MATCH", "WEIGHTED_COMPOSITE");
        body("/api/admin/incidents/" + ticketId, null, 401);
    }

    @Test
    @DisplayName("by type: no technician, employee or list view has a field that could carry an explanation")
    void responseTypesCannotCarryAnExplanation() {
        for (Class<?> view : List.of(TechnicianIncidentView.class, EmployeeIncidentView.class, AdminIncidentView.class)) {
            List<String> components = Arrays.stream(view.getRecordComponents()).map(RecordComponent::getName).toList();
            assertThat(components).as(view.getSimpleName())
                    .doesNotContain("assignmentDecisions", "candidates", "candidateScores", "ranking");
            assertThat(Arrays.stream(view.getRecordComponents()).map(c -> c.getGenericType().getTypeName()))
                    .as(view.getSimpleName()).noneMatch(type -> type.contains("AssignmentDecision")
                            || type.contains("CandidateView") || type.contains("CandidateScore"));
        }
    }
}
