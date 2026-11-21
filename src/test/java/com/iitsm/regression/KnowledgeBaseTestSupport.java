package com.iitsm.regression;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iitsm.application.service.IncidentService;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.User;
import com.iitsm.domain.repository.UserRepository;
import com.iitsm.infrastructure.security.JwtTokenProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Shared steps of the knowledge-base regression tests (ADR-042): tickets created through the real intake, assigned,
 * resolved over HTTP, and removed afterwards (articles, votes and resolutions deleted, tickets closed) so they never
 * compete in another class's suggestions.
 */
final class KnowledgeBaseTestSupport {

    static final String EMPLOYEE_ID = "EMP-DEMO-01";
    static final String ADMIN_ID = "ADMIN-SYS-01";
    static final String TECHNICIAN_ID = "TECH-NET-2";

    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;
    private final JwtTokenProvider tokenProvider;
    private final IncidentService incidentService;
    private final UserRepository userRepository;
    private final JdbcTemplate jdbc;
    private final List<String> created = new ArrayList<>();

    KnowledgeBaseTestSupport(MockMvc mockMvc, ObjectMapper objectMapper, JwtTokenProvider tokenProvider,
            IncidentService incidentService, UserRepository userRepository, JdbcTemplate jdbc) {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
        this.tokenProvider = tokenProvider;
        this.incidentService = incidentService;
        this.userRepository = userRepository;
        this.jdbc = jdbc;
    }

    String tokenOf(String userId) {
        User user = userRepository.findById(userId).orElseThrow();
        return "Bearer " + tokenProvider.generateToken(user);
    }

    void send(String url, String userId, Object body) throws Exception {
        perform(url, userId, body).andExpect(status().is2xxSuccessful());
    }

    ResultActions perform(String url, String userId, Object body) throws Exception {
        var request = post(url);
        if (userId != null) {
            request = request.header("Authorization", tokenOf(userId));
        }
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON)
                    .content(body instanceof String text ? text : objectMapper.writeValueAsString(body));
        }
        return mockMvc.perform(request);
    }

    String ticketWithTechnician(String title, String description) throws Exception {
        Incident incident = incidentService.createIncident(EMPLOYEE_ID, title, description);
        created.add(incident.getId());
        send("/api/admin/incidents/" + incident.getId() + "/assign", ADMIN_ID, Map.of("technicianId", TECHNICIAN_ID));
        return incident.getId();
    }

    String resolved(String title, String description, String rootCause, String steps) throws Exception {
        String id = ticketWithTechnician(title, description);
        send("/api/technician/incidents/" + id + "/acknowledge", TECHNICIAN_ID, null);
        send("/api/technician/incidents/" + id + "/start-investigation", TECHNICIAN_ID, null);
        send("/api/technician/incidents/" + id + "/resolve", TECHNICIAN_ID, Map.of(
                "rootCause", rootCause, "stepsTaken", steps,
                "preventiveAction", "Certificate expiry alert 30 days ahead", "confirmedCategory", "NETWORK"));
        return id;
    }

    ResultActions promote(String userId, String ticket, String title) throws Exception {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("sourceIncidentId", ticket);
        if (title != null) {
            body.put("title", title);
        }
        return perform("/api/technician/knowledge-articles", userId, body);
    }

    JsonNode suggestions(String ticket, String userId) throws Exception {
        String json = mockMvc.perform(get("/api/technician/incidents/" + ticket + "/suggested-resolutions")
                        .header("Authorization", tokenOf(userId)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json);
    }

    static JsonNode find(JsonNode list, String sourceId) {
        for (JsonNode node : list) {
            if (sourceId.equals(node.get("sourceId").asText())) {
                return node;
            }
        }
        return null;
    }

    static JsonNode findById(JsonNode list, String id) {
        for (JsonNode node : list) {
            if (id.equals(node.get("id").asText())) {
                return node;
            }
        }
        return null;
    }

    void cleanUp() {
        for (String id : created) {
            jdbc.update("DELETE FROM suggestion_votes WHERE target_incident_id = ? OR source_id = ? OR source_id = ?",
                    id, id, "KB-" + id);
            jdbc.update("DELETE FROM knowledge_articles WHERE source_incident_id = ?", id);
            jdbc.update("DELETE FROM resolutions WHERE incident_id = ?", id);
            jdbc.update("UPDATE incidents SET status = 'CLOSED' WHERE id = ?", id);
        }
        created.clear();
        jdbc.execute("DELETE FROM technician_active_incidents");
    }
}
