package com.iitsm.regression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iitsm.application.service.IncidentService;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.ItService;
import com.iitsm.domain.model.SupportTeam;
import com.iitsm.domain.model.User;
import com.iitsm.domain.repository.IncidentRepository;
import com.iitsm.domain.repository.ItServiceRepository;
import com.iitsm.domain.repository.SupportTeamRepository;
import com.iitsm.domain.repository.TechnicianRepository;
import com.iitsm.domain.repository.UserRepository;
import com.iitsm.infrastructure.persistence.CompanySeeder;
import com.iitsm.infrastructure.security.JwtTokenProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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

/**
 * H7 through the door the browser uses. The reclassify dialog in app.js posts only {category, reason}: no
 * service id. A reclassify must still route the ticket, so the owning team comes from the team that handles
 * the new category. Also covers the HTTP contract (roles, validation, unknown ticket) and a concurrent pair.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("regression")
class ReclassifyEndpointRegressionTest {

    private static final String EMPLOYEE = "employee@nimbusfinserve.com";
    private static final String ADMIN_ID = "ADMIN-SYS-01";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtTokenProvider tokenProvider;
    @Autowired private IncidentService incidentService;
    @Autowired private IncidentRepository incidentRepository;
    @Autowired private TechnicianRepository technicianRepository;
    @Autowired private SupportTeamRepository supportTeamRepository;
    @Autowired private ItServiceRepository itServiceRepository;
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

    private Incident assignedTicket() {
        Incident created = incidentService.createIncident(EMPLOYEE, "VPN gateway timeouts for the whole office",
                "AnyConnect drops every few minutes with heavy packet loss on the office gateway tunnel.");
        Assumptions.assumeTrue(created.getAssigneeId() != null, "ticket must be auto-assigned");
        return created;
    }

    private String teamOf(String technicianId) {
        return technicianRepository.findById(technicianId).orElseThrow().getSupportTeamId();
    }

    private String handlingTeam(IncidentCategory category) {
        return supportTeamRepository.findByHandledCategory(category).stream()
                .map(SupportTeam::getId).findFirst().orElseThrow();
    }

    private String body(Map<String, Object> fields) throws Exception {
        return objectMapper.writeValueAsString(fields);
    }

    private int reclassifyStatus(String token, String ticketId, Map<String, Object> fields) throws Exception {
        return mockMvc.perform(post("/api/admin/incidents/" + ticketId + "/reclassify")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(fields)))
                .andReturn().getResponse().getStatus();
    }

    @Test
    @DisplayName("H7 E1: the dialog's body (category and reason, no service) moves the ticket to the team handling that category")
    void dialogShapedRequestRoutesToTheCategoryTeam() throws Exception {
        Incident ticket = assignedTicket();
        String oldTeam = teamOf(ticket.getAssigneeId());
        Assumptions.assumeTrue(!handlingTeam(IncidentCategory.SECURITY).equals(oldTeam));

        mockMvc.perform(post("/api/admin/incidents/" + ticket.getId() + "/reclassify")
                        .header("Authorization", tokenOf(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("category", "SECURITY", "reason", "Human triage reclassification"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.category").value("SECURITY"));

        Incident after = incidentRepository.findById(ticket.getId()).orElseThrow();
        assertThat(after.getAssigneeId()).isNotNull();
        assertThat(teamOf(after.getAssigneeId())).isEqualTo(handlingTeam(IncidentCategory.SECURITY));
    }

    @Test
    @DisplayName("H7 E2: a category handled by the technician's own team does not move the ticket")
    void sameCategoryTeamDoesNotMove() throws Exception {
        Incident ticket = assignedTicket();
        String tech = ticket.getAssigneeId();
        IncidentCategory ownCategory = supportTeamRepository.findById(teamOf(tech)).orElseThrow()
                .getHandledCategories().iterator().next();

        mockMvc.perform(post("/api/admin/incidents/" + ticket.getId() + "/reclassify")
                        .header("Authorization", tokenOf(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("category", ownCategory.name(), "reason", "Confirmed"))))
                .andExpect(status().isOk());

        assertThat(incidentRepository.findById(ticket.getId()).orElseThrow().getAssigneeId()).isEqualTo(tech);
    }

    @Test
    @DisplayName("H7 E3 (ADR-036): the category's team wins, a service that another team owns only sets criticality")
    void categoryTeamWinsOverTheServiceOwner() throws Exception {
        Incident ticket = assignedTicket();
        String oldTeam = teamOf(ticket.getAssigneeId());
        ItService target = itServiceRepository.findAll().stream()
                .filter(s -> s.getOwningTeamId() != null && !s.getOwningTeamId().equals(oldTeam))
                .filter(s -> !s.getOwningTeamId().equals(handlingTeam(IncidentCategory.HARDWARE)))
                .findFirst().orElseThrow();

        mockMvc.perform(post("/api/admin/incidents/" + ticket.getId() + "/reclassify")
                        .header("Authorization", tokenOf(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("category", "HARDWARE", "serviceId", target.getId(), "reason", "Service decides"))))
                .andExpect(status().isOk());

        Incident after = incidentRepository.findById(ticket.getId()).orElseThrow();
        assertThat(teamOf(after.getAssigneeId())).isEqualTo(handlingTeam(IncidentCategory.HARDWARE));
        assertThat(after.getServiceId()).isEqualTo(target.getId());
    }

    @Test
    @DisplayName("H7 E4: an employee token is refused and the ticket is unchanged")
    void employeeIsRefused() throws Exception {
        Incident ticket = assignedTicket();
        String employeeId = userRepository.findAll().stream()
                .filter(u -> EMPLOYEE.equalsIgnoreCase(u.getEmail().value())).findFirst().orElseThrow().getId();

        int status = reclassifyStatus(tokenOf(employeeId), ticket.getId(), Map.of("category", "SECURITY", "reason", "x"));

        assertThat(status).isEqualTo(403);
        assertThat(incidentRepository.findById(ticket.getId()).orElseThrow().getCategory()).isEqualTo(ticket.getCategory());
    }

    @Test
    @DisplayName("H7 E5: a technician token is refused")
    void technicianIsRefused() throws Exception {
        Incident ticket = assignedTicket();

        int status = reclassifyStatus(tokenOf(ticket.getAssigneeId()), ticket.getId(), Map.of("category", "SECURITY", "reason", "x"));

        assertThat(status).isEqualTo(403);
    }

    @Test
    @DisplayName("H7 E6: no token is refused with 401 or 403")
    void anonymousIsRefused() throws Exception {
        Incident ticket = assignedTicket();

        int status = mockMvc.perform(post("/api/admin/incidents/" + ticket.getId() + "/reclassify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("category", "SECURITY", "reason", "x"))))
                .andReturn().getResponse().getStatus();

        assertThat(status).isIn(401, 403);
    }

    @Test
    @DisplayName("H7 E7: a missing category is a 400 and the ticket is unchanged")
    void missingCategoryIsBadRequest() throws Exception {
        Incident ticket = assignedTicket();

        int status = reclassifyStatus(tokenOf(ADMIN_ID), ticket.getId(), Map.of("reason", "No category"));

        assertThat(status).isEqualTo(400);
        assertThat(incidentRepository.findById(ticket.getId()).orElseThrow().getAssigneeId()).isEqualTo(ticket.getAssigneeId());
    }

    @Test
    @DisplayName("H7 E8: an unknown ticket is a 404")
    void unknownTicketIsNotFound() throws Exception {
        int status = reclassifyStatus(tokenOf(ADMIN_ID), "NOPE-2026-000001", Map.of("category", "SECURITY", "reason", "x"));

        assertThat(status).isEqualTo(404);
    }

    @Test
    @DisplayName("H7 E9: the response is the admin view and still shows the override after a reclassify")
    void responseKeepsTheOverride() throws Exception {
        Incident ticket = assignedTicket();
        incidentService.overridePriority(ticket.getId(), "admin@nimbusfinserve.com",
                com.iitsm.domain.model.Priority.P1, "Business impact is higher");

        mockMvc.perform(post("/api/admin/incidents/" + ticket.getId() + "/reclassify")
                        .header("Authorization", tokenOf(ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("category", "SECURITY", "reason", "Wrong domain"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priorityDecision.priority").value("P1"))
                .andExpect(jsonPath("$.priorityDecision.overriddenBy").isNotEmpty());
    }

    @Test
    @DisplayName("H7 E10: two simultaneous reclassifies of one ticket never produce a 500")
    void simultaneousReclassifiesNeverFailWithServerError() throws Exception {
        String token = tokenOf(ADMIN_ID);
        List<Integer> statuses = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 6; round++) {
                Incident ticket = assignedTicket();
                CyclicBarrier barrier = new CyclicBarrier(2);
                List<Future<Integer>> results = new ArrayList<>();
                for (String category : List.of("SECURITY", "CLOUD")) {
                    Callable<Integer> call = () -> {
                        barrier.await();
                        return reclassifyStatus(token, ticket.getId(), Map.of("category", category, "reason", "Race"));
                    };
                    results.add(pool.submit(call));
                }
                for (Future<Integer> result : results) {
                    statuses.add(result.get());
                }
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(statuses).hasSize(12).allMatch(s -> s == 200 || s == 409);
        assertThat(statuses).contains(200);
    }
}
