package com.iitsm.regression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
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
import org.springframework.test.web.servlet.ResultActions;

/**
 * Votes on suggested resolutions, Phase K2 (ADR-041), end to end over HTTP: one vote per voter, source and ticket (a
 * second vote changes it), the counts and the bounded score in the suggestion list, and every refusal with its stable
 * code. No 500 for any client mistake.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("regression")
class SuggestionVoteRegressionTest {

    private static final String EMPLOYEE_ID = "EMP-DEMO-01";
    private static final String ADMIN_ID = "ADMIN-SYS-01";
    private static final String TECHNICIAN_ID = "TECH-NET-2";
    private static final String TEAM_LEAD_ID = "LEAD-NET";
    private static final String OTHER_TEAM_TECHNICIAN_ID = "TECH-DB-1";
    private static final double VOTE_WEIGHT = 0.20;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtTokenProvider tokenProvider;
    @Autowired private IncidentService incidentService;
    @Autowired private UserRepository userRepository;
    @Autowired private CompanySeeder companySeeder;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final List<String> createdTickets = new ArrayList<>();
    private String resolved;
    private String open;

    @BeforeEach
    void setUp() throws Exception {
        jdbcTemplate.execute("DELETE FROM technician_active_incidents");
        if (userRepository.count() == 0) {
            companySeeder.seedCompanyData();
        }
        resolved = resolvedVpnTicket();
        open = ticketWithTechnician("AnyConnect VPN tunnel fails with certificate error",
                "Since this morning the VPN gateway refuses my AnyConnect tunnel and shows a certificate warning.");
    }

    @AfterEach
    void tearDown() {
        createdTickets.forEach(id -> {
            jdbcTemplate.update("DELETE FROM suggestion_votes WHERE target_incident_id = ? OR source_id = ?", id, id);
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

    private String resolvedVpnTicket() throws Exception {
        String id = ticketWithTechnician("VPN gateway rejects every AnyConnect login",
                "Remote staff cannot connect: the AnyConnect VPN gateway refuses the tunnel with a certificate error.");
        String technician = tokenOf(TECHNICIAN_ID);
        send("/api/technician/incidents/" + id + "/acknowledge", technician, null);
        send("/api/technician/incidents/" + id + "/start-investigation", technician, null);
        send("/api/technician/incidents/" + id + "/resolve", technician, Map.of(
                "rootCause", "Expired TLS certificate on the AnyConnect VPN gateway",
                "stepsTaken", "Renewed the gateway certificate and restarted the VPN tunnel service",
                "preventiveAction", "Certificate expiry alert 30 days ahead",
                "confirmedCategory", "NETWORK"));
        return id;
    }

    private String votesUrl(String ticket) {
        return "/api/technician/incidents/" + ticket + "/suggested-resolutions/votes";
    }

    private ResultActions vote(String ticket, String userId, String json) throws Exception {
        var request = post(votesUrl(ticket)).contentType(MediaType.APPLICATION_JSON).content(json);
        if (userId != null) {
            request = request.header("Authorization", tokenOf(userId));
        }
        return mockMvc.perform(request);
    }

    private String body(String sourceType, String sourceId, Object worked) throws Exception {
        java.util.Map<String, Object> map = new java.util.HashMap<>();
        map.put("sourceType", sourceType);
        map.put("sourceId", sourceId);
        map.put("worked", worked);
        return objectMapper.writeValueAsString(map);
    }

    private JsonNode suggestionFor(String ticket, String sourceId) throws Exception {
        String json = mockMvc.perform(get("/api/technician/incidents/" + ticket + "/suggested-resolutions")
                        .header("Authorization", tokenOf(TECHNICIAN_ID)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        for (JsonNode node : objectMapper.readTree(json)) {
            if (sourceId.equals(node.get("sourceId").asText())) {
                return node;
            }
        }
        throw new AssertionError(sourceId + " not suggested for " + ticket + ": " + json);
    }

    private int storedVotes() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM suggestion_votes WHERE source_id = ?", Integer.class, resolved);
    }

    @Test
    @DisplayName("A vote is stored under the voter's id; voting again changes it instead of adding a second one")
    void voteIsAnUpsert() throws Exception {
        vote(open, TECHNICIAN_ID, body("RESOLVED_TICKET", resolved, true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.worked").value(true))
                .andExpect(jsonPath("$.workedVotes").value(1))
                .andExpect(jsonPath("$.didNotWorkVotes").value(0))
                .andExpect(jsonPath("$.targetIncidentId").value(open));
        vote(open, TECHNICIAN_ID, body("RESOLVED_TICKET", resolved, false))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workedVotes").value(0))
                .andExpect(jsonPath("$.didNotWorkVotes").value(1));

        assertThat(storedVotes()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT voter_id FROM suggestion_votes WHERE source_id = ?", String.class, resolved))
                .isEqualTo(TECHNICIAN_ID);
    }

    @Test
    @DisplayName("Votes of different staff add up; the list shows the counts and score = similarity x bounded factor")
    void countsAndScoreInTheList() throws Exception {
        vote(open, TECHNICIAN_ID, body("RESOLVED_TICKET", resolved, true)).andExpect(status().isOk());
        vote(open, TEAM_LEAD_ID, body("RESOLVED_TICKET", resolved, true)).andExpect(status().isOk());
        vote(open, ADMIN_ID, body("RESOLVED_TICKET", resolved, false)).andExpect(status().isOk());

        JsonNode hit = suggestionFor(open, resolved);
        double similarity = hit.get("similarity").asDouble();
        double expectedFactor = 1 - VOTE_WEIGHT + 2 * VOTE_WEIGHT * (2 + 1.0) / (2 + 1 + 2);

        assertThat(hit.get("workedVotes").asLong()).isEqualTo(2);
        assertThat(hit.get("didNotWorkVotes").asLong()).isEqualTo(1);
        assertThat(hit.get("score").asDouble()).isCloseTo(similarity * expectedFactor, within(1e-9));
        assertThat(hit.get("score").asDouble() / similarity).isBetween(1 - VOTE_WEIGHT, 1 + VOTE_WEIGHT);
    }

    @Test
    @DisplayName("Malformed bodies are 400 with a stable code, never 500")
    void malformedBodies() throws Exception {
        vote(open, TECHNICIAN_ID, body("SOMETHING", resolved, true))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ERR_MALFORMED_JSON"));
        vote(open, TECHNICIAN_ID, body("RESOLVED_TICKET", resolved, null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ERR_VALIDATION_FAILED"));
        vote(open, TECHNICIAN_ID, body("RESOLVED_TICKET", " ", true))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ERR_VALIDATION_FAILED"));
        vote(open, TECHNICIAN_ID, body(null, resolved, true))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ERR_VALIDATION_FAILED"));
        vote(open, TECHNICIAN_ID, "{not json")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ERR_MALFORMED_JSON"));
        // a resolved ticket voted as its own fix
        vote(resolved, TECHNICIAN_ID, body("RESOLVED_TICKET", resolved, true))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ERR_VALIDATION_FAILED"));
        assertThat(storedVotes()).isZero();
    }

    @Test
    @DisplayName("An unknown source, an open ticket as source, an article (before K3) and an unknown ticket are 404")
    void notFound() throws Exception {
        vote(open, TECHNICIAN_ID, body("RESOLVED_TICKET", "NET-2099-999999", true))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ERR_NOT_FOUND"));
        String otherOpen = ticketWithTechnician("AnyConnect VPN drops at the gateway",
                "The VPN gateway drops my AnyConnect tunnel every few minutes from home.");
        vote(open, TECHNICIAN_ID, body("RESOLVED_TICKET", otherOpen, true))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ERR_NOT_FOUND"));
        vote(open, TECHNICIAN_ID, body("KB_ARTICLE", "KB-1", true))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ERR_NOT_FOUND"));
        vote("NET-2099-999999", ADMIN_ID, body("RESOLVED_TICKET", resolved, true))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ERR_SERVICE_NOT_FOUND"));
    }

    @Test
    @DisplayName("Only staff who may view the ticket can vote: other team 403, employee 403, no token 401")
    void accessRules() throws Exception {
        vote(open, OTHER_TEAM_TECHNICIAN_ID, body("RESOLVED_TICKET", resolved, true))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ERR_UNAUTHORIZED_OPERATION"));
        vote(open, EMPLOYEE_ID, body("RESOLVED_TICKET", resolved, true)).andExpect(status().isForbidden());
        vote(open, null, body("RESOLVED_TICKET", resolved, true)).andExpect(status().isUnauthorized());
        assertThat(storedVotes()).isZero();
    }
}
