package com.iitsm.regression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iitsm.application.service.IncidentService;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.IncidentStatus;
import com.iitsm.domain.model.Technician;
import com.iitsm.domain.model.User;
import com.iitsm.domain.repository.IncidentRepository;
import com.iitsm.domain.repository.TechnicianRepository;
import com.iitsm.domain.repository.UserRepository;
import com.iitsm.infrastructure.persistence.CompanySeeder;
import com.iitsm.infrastructure.security.JwtTokenProvider;
import com.iitsm.testsupport.CapacityInvariant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Phase M3 (ADR-045): many team lead moves in a row. Four tickets, seeded random walks of lead reassignments mixed with
 * the technicians' own acknowledge and start steps; after <em>every</em> step the capacity invariant holds, each open
 * ticket's slot is held by exactly its assignee, and the answers are only the documented ones (200, or 409 when the
 * status or a full technician refuses; never 500). Also proves the administrator's "Why This Technician" shows a lead's
 * move by name.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("regression")
class TeamLeadReassignmentSequenceTest {

    private static final String EMPLOYEE = "employee@nimbusfinserve.com";
    private static final String ADMIN_ID = "ADMIN-SYS-01";
    private static final String NETWORK_LEAD = "LEAD-NET";
    private static final int TICKETS = 4;
    private static final int STEPS_PER_SEED = 40;
    private static final long[] SEEDS = {45L, 46L, 47L};

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtTokenProvider tokenProvider;
    @Autowired private IncidentService incidentService;
    @Autowired private IncidentRepository incidentRepository;
    @Autowired private TechnicianRepository technicianRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private CompanySeeder companySeeder;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final List<String> created = new ArrayList<>();
    private List<Technician> members;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM technician_active_incidents");
        if (userRepository.count() == 0) {
            companySeeder.seedCompanyData();
        }
        Technician lead = technicianRepository.findById(NETWORK_LEAD).orElseThrow();
        members = technicianRepository.findBySupportTeamId(lead.getSupportTeamId()).stream()
                .sorted(java.util.Comparator.comparing(Technician::getId)).toList();
    }

    @AfterEach
    void tearDown() {
        for (String id : created) {
            incidentRepository.findById(id).ifPresent(i -> {
                i.transitionTo(IncidentStatus.CLOSED);
                incidentRepository.save(i);
            });
        }
        created.clear();
        jdbcTemplate.execute("DELETE FROM technician_active_incidents");
    }

    private static String word(Random random) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 9; i++) {
            sb.append((char) ('a' + random.nextInt(26)));
        }
        return sb.toString();
    }

    private Incident newTicket(Random random) {
        StringBuilder description = new StringBuilder("The vpn and the gateway");
        for (int i = 0; i < 14; i++) {
            description.append(' ').append(word(random));
        }
        Incident ticket = incidentService.createIncident(EMPLOYEE, "VPN gateway " + word(random) + " " + word(random),
                description.append(" vpn.").toString());
        created.add(ticket.getId());
        Assumptions.assumeTrue(ticket.getAssigneeId() != null, "ticket must be auto-assigned");
        return ticket;
    }

    private String tokenOf(String userId) {
        User user = userRepository.findById(userId).orElseThrow();
        return "Bearer " + tokenProvider.generateToken(user);
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String userId, String body) throws Exception {
        request = request.header("Authorization", tokenOf(userId));
        return mockMvc.perform(body == null ? request : request.contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private Set<String> violations() {
        return CapacityInvariant.check(technicianRepository, incidentRepository).stream()
                .map(v -> v.kind() + "|" + v.technicianId() + "|" + v.incidentId()).collect(Collectors.toSet());
    }

    private void assertConsistent(Set<String> baseline, String step) {
        Set<String> added = new HashSet<>(violations());
        added.removeAll(baseline);
        assertThat(added).as("capacity violations after " + step).isEmpty();
        for (String id : created) {
            Incident ticket = incidentRepository.findById(id).orElseThrow();
            if (!ticket.getStatus().holdsTechnicianSlot()) {
                continue;
            }
            List<String> holders = technicianRepository.findAll().stream()
                    .filter(t -> t.getActiveIncidentIds().contains(id)).map(Technician::getId).toList();
            assertThat(holders).as("holders of " + id + " after " + step).containsExactly(ticket.getAssigneeId());
        }
    }

    @Test
    @DisplayName("Seeded random walks of lead moves and technician steps keep every slot and assignee consistent after each step")
    void randomWalksStayConsistent() throws Exception {
        int moves = 0;
        for (long seed : SEEDS) {
            Random random = new Random(seed);
            created.clear();
            jdbcTemplate.execute("DELETE FROM technician_active_incidents");
            for (int i = 0; i < TICKETS; i++) {
                newTicket(random);
            }
            Set<String> baseline = violations();
            for (int step = 0; step < STEPS_PER_SEED; step++) {
                String ticketId = created.get(random.nextInt(created.size()));
                Incident ticket = incidentRepository.findById(ticketId).orElseThrow();
                String label = "seed " + seed + " step " + step + " on " + ticketId + " (" + ticket.getStatus() + ")";
                int action = random.nextInt(4);
                if (action == 0 && ticket.getStatus() == IncidentStatus.ASSIGNED) {
                    incidentService.acknowledge(ticketId, ticket.getAssigneeId());
                } else if (action == 1 && ticket.getStatus() == IncidentStatus.ACKNOWLEDGED) {
                    incidentService.startInvestigation(ticketId, ticket.getAssigneeId());
                } else {
                    Technician target = members.get(random.nextInt(members.size()));
                    int code = send(post("/api/technician/incidents/" + ticketId + "/reassign"), NETWORK_LEAD,
                            "{\"technicianId\":\"" + target.getId() + "\",\"reason\":\"step " + step + "\"}")
                            .andReturn().getResponse().getStatus();
                    assertThat(code).as("answer of " + label).isIn(200, 400, 409);
                    if (code == 200) {
                        moves++;
                        assertThat(incidentRepository.findById(ticketId).orElseThrow().getAssigneeId()).isEqualTo(target.getId());
                    }
                }
                assertConsistent(baseline, label);
            }
            for (String id : created) {
                incidentRepository.findById(id).ifPresent(i -> {
                    i.transitionTo(IncidentStatus.CLOSED);
                    incidentRepository.save(i);
                });
            }
            jdbcTemplate.execute("DELETE FROM technician_active_incidents");
        }
        assertThat(moves).as("the walks actually moved tickets").isGreaterThan(20);
    }

    @Test
    @DisplayName("The administrator's ticket detail shows the lead's move: kind, the lead's name, the technician's name")
    void adminSeesTheLeadsMove() throws Exception {
        Random random = new Random(48);
        Incident ticket = newTicket(random);
        Technician target = members.stream().filter(t -> !t.getId().equals(ticket.getAssigneeId())
                && !t.getId().equals(NETWORK_LEAD)).findFirst().orElseThrow();
        send(post("/api/technician/incidents/" + ticket.getId() + "/reassign"), NETWORK_LEAD,
                "{\"technicianId\":\"" + target.getId() + "\",\"reason\":\"better fit\"}").andExpect(status().isOk());

        String body = send(get("/api/admin/incidents/" + ticket.getId()), ADMIN_ID, null).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode newest = objectMapper.readTree(body).get("assignmentDecisions").get(0);

        assertThat(newest.get("poolKind").asText()).isEqualTo("MANUAL");
        assertThat(newest.get("actorId").asText()).isEqualTo(NETWORK_LEAD);
        assertThat(newest.get("actorName").asText()).isEqualTo("Sarah Jenkins");
        assertThat(newest.get("chosenTechnicianId").asText()).isEqualTo(target.getId());
        assertThat(newest.get("summary").asText()).contains("Sarah Jenkins").contains(target.getName()).contains("by hand");
    }

    @Test
    @DisplayName("A lead's move reaches the new technician: the ticket is in their queue and gone from the previous holder's")
    void queuesFollowTheMove() throws Exception {
        Random random = new Random(49);
        Incident ticket = newTicket(random);
        String from = ticket.getAssigneeId();
        Technician target = members.stream().filter(t -> !t.getId().equals(from) && !t.getId().equals(NETWORK_LEAD))
                .findFirst().orElseThrow();

        send(post("/api/technician/incidents/" + ticket.getId() + "/reassign"), NETWORK_LEAD,
                "{\"technicianId\":\"" + target.getId() + "\",\"reason\":\"better fit\"}").andExpect(status().isOk());

        assertThat(queueIds(target.getId())).contains(ticket.getId());
        assertThat(queueIds(from)).doesNotContain(ticket.getId());
    }

    private List<String> queueIds(String technicianId) throws Exception {
        String body = send(get("/api/technician/queue"), technicianId, null).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> ids = new ArrayList<>();
        objectMapper.readTree(body).forEach(n -> ids.add(n.get("id").asText()));
        return ids;
    }
}
