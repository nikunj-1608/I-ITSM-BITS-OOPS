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
import com.iitsm.domain.repository.TechnicianRepository;
import com.iitsm.domain.repository.UserRepository;
import com.iitsm.infrastructure.persistence.CompanySeeder;
import com.iitsm.infrastructure.security.JwtTokenProvider;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * ADR-026: the administrator can see where a technician's stored slots differ from the tickets that name them, can have
 * the differences repaired, and sees how many linked duplicates each technician carries without a slot. Slots are
 * damaged directly in the database (as an old leak would have left them), because the application no longer produces
 * such damage itself.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("regression")
class CapacityReconciliationEndpointTest {

    private static final String EMPLOYEE = "employee@nimbusfinserve.com";
    private static final String ADMIN_ID = "ADMIN-SYS-01";
    private static final String TECHNICIAN_ID = "TECH-NET-1";
    private static final String EMPLOYEE_ID = "EMP-DEMO-01";
    private static final String REPORT = "/api/admin/capacity/reconcile";
    private static final Random RANDOM = new Random(31);

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtTokenProvider tokenProvider;
    @Autowired private IncidentService incidentService;
    @Autowired private TechnicianRepository technicianRepository;
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

    private static String word() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 9; i++) {
            sb.append((char) ('a' + RANDOM.nextInt(26)));
        }
        return sb.toString();
    }

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

    /**
     * Earlier tests in the shared database leave open tickets without slots on the same technician. The repair takes
     * a missing slot only while the technician has room (oldest ticket first), so with many leftovers it would
     * correctly stop before reaching this test's ticket. Closing the leftovers makes the outcome deterministic.
     */
    private void closeLeftoverTicketsOf(Incident ticket) {
        jdbcTemplate.update("UPDATE incidents SET status = 'CLOSED' WHERE assignee_id = ? AND id <> ? "
                + "AND status IN ('ASSIGNED', 'ACKNOWLEDGED', 'IN_PROGRESS', 'PENDING_INFO')",
                ticket.getAssigneeId(), ticket.getId());
    }

    private JsonNode adminJson(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) throws Exception {
        String body = mockMvc.perform(request.header("Authorization", tokenOf(ADMIN_ID)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private static List<String> keys(JsonNode items) {
        List<String> keys = new ArrayList<>();
        items.forEach(i -> keys.add(i.get("kind").asText() + "|" + i.get("technicianId").asText() + "|" + i.get("incidentId").asText()));
        return keys;
    }

    private static Map<String, JsonNode> byId(JsonNode roster) {
        Map<String, JsonNode> technicians = new HashMap<>();
        roster.forEach(t -> technicians.put(t.get("id").asText(), t));
        return technicians;
    }

    @Test
    @DisplayName("The report lists a phantom slot and a missing slot, and the repair fixes both")
    void reportsAndRepairsPhantomAndMissingSlots() throws Exception {
        String ghost = "INC-GHOST-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        Incident ticket = assignedTicket();
        closeLeftoverTicketsOf(ticket);
        jdbcTemplate.update("INSERT INTO technician_active_incidents (technician_id, incident_id) VALUES (?, ?)", TECHNICIAN_ID, ghost);
        jdbcTemplate.update("DELETE FROM technician_active_incidents WHERE incident_id = ?", ticket.getId());
        String phantom = "PHANTOM_SLOT|" + TECHNICIAN_ID + "|" + ghost;
        String missing = "MISSING_SLOT|" + ticket.getAssigneeId() + "|" + ticket.getId();

        JsonNode report = adminJson(get(REPORT));
        assertThat(keys(report.get("discrepancies"))).contains(phantom, missing);
        assertThat(report.get("discrepancyCount").asInt()).isEqualTo(report.get("discrepancies").size());

        JsonNode repair = adminJson(post(REPORT));
        assertThat(keys(repair.get("repaired"))).contains(phantom, missing);
        assertThat(repair.get("repairedCount").asInt()).isEqualTo(repair.get("repaired").size());

        assertThat(technicianRepository.findById(TECHNICIAN_ID).orElseThrow().getActiveIncidentIds()).doesNotContain(ghost);
        assertThat(technicianRepository.findById(ticket.getAssigneeId()).orElseThrow().getActiveIncidentIds())
                .contains(ticket.getId());
        assertThat(keys(adminJson(get(REPORT)).get("discrepancies"))).doesNotContain(phantom, missing);
    }

    @Test
    @DisplayName("Only administrators may read the report or run the repair")
    void onlyAdministrators() throws Exception {
        for (String userId : List.of(TECHNICIAN_ID, EMPLOYEE_ID)) {
            mockMvc.perform(get(REPORT).header("Authorization", tokenOf(userId))).andExpect(status().isForbidden());
            mockMvc.perform(post(REPORT).header("Authorization", tokenOf(userId))).andExpect(status().isForbidden());
        }
        mockMvc.perform(get(REPORT)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("The roster shows the duplicates a technician carries without a slot, next to the slots in use")
    void rosterShowsCarriedDuplicates() throws Exception {
        Map<String, JsonNode> before = byId(adminJson(get("/api/admin/technicians")));
        StringBuilder description = new StringBuilder("The vpn and the gateway");
        for (int i = 0; i < 14; i++) {
            description.append(' ').append(word());
        }
        String title = "VPN gateway " + word() + " " + word();
        Incident parent = incidentService.createIncident(EMPLOYEE, title, description.append(" vpn.").toString());
        Assumptions.assumeTrue(parent.getAssigneeId() != null, "parent must be auto-assigned");
        Incident child = incidentService.createIncident(EMPLOYEE, title, description.toString());
        Assumptions.assumeTrue(child.getLinkedIncidentIds().contains(parent.getId()), "identical text must be linked");
        Assumptions.assumeTrue(parent.getAssigneeId().equals(child.getAssigneeId()), "the duplicate rides the parent's technician");

        JsonNode after = byId(adminJson(get("/api/admin/technicians"))).get(parent.getAssigneeId());

        assertThat(after.get("carriedDuplicateCount").asInt() - before.get(parent.getAssigneeId()).get("carriedDuplicateCount").asInt())
                .as("one more linked duplicate carried by the parent's technician").isEqualTo(1);
        assertThat(after.get("activeIncidentCount").asInt())
                .as("the parent holds the only slot; the duplicate takes none (F4)").isEqualTo(1);
    }
}
