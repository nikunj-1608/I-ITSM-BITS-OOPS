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
 * Suggested resolutions, Phase K1 (spec feature G, ADR-040), end to end over HTTP: a resolved ticket is suggested for a
 * similar new one, a dissimilar ticket gets an empty list, and the endpoint follows the staff roles and the ticket's
 * access rules. Nothing of it reaches an employee.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("regression")
class SuggestedResolutionRegressionTest {

    private static final String EMPLOYEE_ID = "EMP-DEMO-01";
    private static final String ADMIN_ID = "ADMIN-SYS-01";
    private static final String TECHNICIAN_ID = "TECH-NET-2";
    private static final String TEAM_LEAD_ID = "LEAD-NET";
    private static final String OTHER_TEAM_TECHNICIAN_ID = "TECH-DB-1";
    private static final String ROOT_CAUSE = "Expired TLS certificate on the AnyConnect VPN gateway";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtTokenProvider tokenProvider;
    @Autowired private IncidentService incidentService;
    @Autowired private UserRepository userRepository;
    @Autowired private CompanySeeder companySeeder;
    @Autowired private JdbcTemplate jdbcTemplate;

    /**
     * Tickets created here are closed afterwards (an open one would become a duplicate parent for other classes) and lose
     * their resolution (a resolved one would compete, with the same text, in other classes' suggestions).
     */
    private final List<String> createdTickets = new ArrayList<>();

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM technician_active_incidents");
        if (userRepository.count() == 0) {
            companySeeder.seedCompanyData();
        }
    }

    @AfterEach
    void tearDown() {
        // closed and stripped of their resolution, so they are no longer suggestion candidates for other classes
        createdTickets.forEach(id -> {
            jdbcTemplate.update("DELETE FROM resolutions WHERE incident_id = ?", id);
            jdbcTemplate.update("UPDATE incidents SET status = 'CLOSED' WHERE id = ?", id);
        });
        createdTickets.clear();
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

    private String ticketWithTechnician(String title, String description) throws Exception {
        Incident created = incidentService.createIncident(EMPLOYEE_ID, title, description);
        createdTickets.add(created.getId());
        send("/api/admin/incidents/" + created.getId() + "/assign", tokenOf(ADMIN_ID), Map.of("technicianId", TECHNICIAN_ID));
        return created.getId();
    }

    /** Ticket A: a VPN outage fixed by renewing the gateway certificate. */
    private String resolvedVpnTicket() throws Exception {
        String id = ticketWithTechnician("VPN gateway rejects every AnyConnect login",
                "Remote staff cannot connect: the AnyConnect VPN gateway refuses the tunnel with a certificate error.");
        String technician = tokenOf(TECHNICIAN_ID);
        send("/api/technician/incidents/" + id + "/acknowledge", technician, null);
        send("/api/technician/incidents/" + id + "/start-investigation", technician, null);
        send("/api/technician/incidents/" + id + "/resolve", technician, Map.of(
                "rootCause", ROOT_CAUSE,
                "stepsTaken", "Renewed the gateway certificate and restarted the VPN tunnel service",
                "preventiveAction", "Certificate expiry alert 30 days ahead",
                "confirmedCategory", "NETWORK"));
        return id;
    }

    private JsonNode suggestions(String ticketId, String userId) throws Exception {
        String json = mockMvc.perform(get("/api/technician/incidents/" + ticketId + "/suggested-resolutions")
                        .header("Authorization", tokenOf(userId)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json);
    }

    private static JsonNode suggestionFor(JsonNode list, String sourceId) {
        for (JsonNode node : list) {
            if (sourceId.equals(node.get("sourceId").asText())) {
                return node;
            }
        }
        return null;
    }

    @Test
    @DisplayName("Resolve A, report a similar B: B's suggestions contain A with score, explanation and technical content only")
    void resolvedTicketIsSuggestedForSimilarTicket() throws Exception {
        String a = resolvedVpnTicket();
        String b = ticketWithTechnician("AnyConnect VPN tunnel fails with certificate error",
                "Since this morning the VPN gateway refuses my AnyConnect tunnel and shows a certificate warning.");

        JsonNode list = suggestions(b, TECHNICIAN_ID);
        JsonNode hit = suggestionFor(list, a);

        assertThat(list.size()).isBetween(1, 3);
        assertThat(hit).as("ticket A among the suggestions for B: " + list).isNotNull();
        assertThat(hit.get("sourceType").asText()).isEqualTo("RESOLVED_TICKET");
        assertThat(hit.get("sourceDisplayId").asText()).endsWith(a).startsWith("P");
        assertThat(hit.get("rootCause").asText()).isEqualTo(ROOT_CAUSE);
        assertThat(hit.get("similarity").asDouble()).isBetween(0.20, 1.0);
        assertThat(hit.get("matchedTerms")).isNotEmpty();
        assertThat(hit.has("description")).isFalse();
        assertThat(hit.has("problemText")).isFalse();
        assertThat(hit.has("reporterId")).isFalse();
        assertThat(suggestionFor(list, b)).as("the ticket itself is never suggested").isNull();
    }

    @Test
    @DisplayName("A ticket with no technical word in common with any fix gets an empty list, not a weak match")
    void dissimilarTicketGetsEmptyList() throws Exception {
        String c = ticketWithTechnician("Question about the quarterly offsite catering menu",
                "Could someone tell me whether the quarterly offsite lunch includes vegetarian options for everyone?");

        assertThat(suggestions(c, ADMIN_ID)).isEmpty();
    }

    @Test
    @DisplayName("Staff who may view the ticket get its suggestions; others are refused with stable codes")
    void accessRules() throws Exception {
        String b = ticketWithTechnician("AnyConnect VPN drops at the gateway",
                "The VPN gateway drops my AnyConnect tunnel every few minutes from home.");
        String url = "/api/technician/incidents/" + b + "/suggested-resolutions";

        suggestions(b, TEAM_LEAD_ID);
        suggestions(b, ADMIN_ID);
        mockMvc.perform(get(url).header("Authorization", tokenOf(OTHER_TEAM_TECHNICIAN_ID)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ERR_UNAUTHORIZED_OPERATION"));
        mockMvc.perform(get(url).header("Authorization", tokenOf(EMPLOYEE_ID))).andExpect(status().isForbidden());
        mockMvc.perform(get(url)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/technician/incidents/NET-2099-999999/suggested-resolutions")
                        .header("Authorization", tokenOf(ADMIN_ID)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ERR_SERVICE_NOT_FOUND"));
    }

    @Test
    @DisplayName("The employee's own view of a ticket carries nothing about suggestions")
    void employeeViewHasNoSuggestions() throws Exception {
        String a = resolvedVpnTicket();

        String json = mockMvc.perform(get("/api/employee/incidents/" + a).header("Authorization", tokenOf(EMPLOYEE_ID)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(json.toLowerCase()).doesNotContain("suggest").doesNotContain("matchedterms").doesNotContain("similarity");
    }
}
