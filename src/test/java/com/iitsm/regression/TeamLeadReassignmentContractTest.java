package com.iitsm.regression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iitsm.application.service.IncidentService;
import com.iitsm.domain.model.AvailabilityStatus;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.IncidentStatus;
import com.iitsm.domain.model.Resolution;
import com.iitsm.domain.model.Technician;
import com.iitsm.domain.model.User;
import com.iitsm.domain.repository.AssignmentDecisionRepository;
import com.iitsm.domain.repository.IncidentRepository;
import com.iitsm.domain.repository.TechnicianRepository;
import com.iitsm.domain.repository.UserRepository;
import com.iitsm.domain.strategy.assignment.AssignmentDecision;
import com.iitsm.domain.strategy.assignment.PoolKind;
import com.iitsm.infrastructure.persistence.CompanySeeder;
import com.iitsm.infrastructure.security.JwtTokenProvider;
import com.iitsm.testsupport.CapacityInvariant;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
 * Phase M3 (ADR-045): a team lead gives a ticket of the team to another technician of the same team. One class pins the
 * whole contract through the HTTP endpoint: every status the move may start from, every refusal (status, triage, other
 * team's ticket, other team's technician, unknown or unavailable or full technician, missing reason, wrong role), the
 * capacity slots of both technicians, the recorded decision, the reason note, a race between two moves and the server's
 * {@code reassignable} flag. Every scenario also checks the capacity invariant.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("regression")
class TeamLeadReassignmentContractTest {

    private static final String EMPLOYEE = "employee@nimbusfinserve.com";
    private static final String EMPLOYEE_ID = "EMP-DEMO-01";
    private static final String ADMIN_ID = "ADMIN-SYS-01";
    private static final String NETWORK_LEAD = "LEAD-NET";
    private static final String DATABASE_LEAD = "LEAD-DB";
    private static final String BAD_STATE = "ERR_INVALID_STATE_TRANSITION";
    private static final String REASON = "Marcus knows this gateway better";
    private static final Random RANDOM = new Random(45);

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtTokenProvider tokenProvider;
    @Autowired private IncidentService incidentService;
    @Autowired private IncidentRepository incidentRepository;
    @Autowired private TechnicianRepository technicianRepository;
    @Autowired private AssignmentDecisionRepository decisionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private CompanySeeder companySeeder;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final List<String> created = new ArrayList<>();
    private Technician networkLead;
    private List<Technician> members;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM technician_active_incidents");
        if (userRepository.count() == 0) {
            companySeeder.seedCompanyData();
        }
        networkLead = technicianRepository.findById(NETWORK_LEAD).orElseThrow();
        members = technicianRepository.findBySupportTeamId(networkLead.getSupportTeamId()).stream()
                .filter(t -> !t.getId().equals(NETWORK_LEAD)).sorted(java.util.Comparator.comparing(Technician::getId)).toList();
        assertThat(members).hasSizeGreaterThanOrEqualTo(3);
    }

    @AfterEach
    void tearDown() {
        for (String id : created) {
            incidentRepository.findById(id).ifPresent(i -> {
                i.setNeedsTriage(false);
                i.transitionTo(IncidentStatus.CLOSED);
                incidentRepository.save(i);
            });
        }
        created.clear();
        technicianRepository.findAll().forEach(t -> {
            if (t.getAvailabilityStatus() == AvailabilityStatus.OFFLINE) {
                t.updateAvailability(AvailabilityStatus.ONLINE);
                technicianRepository.save(t);
            }
        });
        jdbcTemplate.execute("DELETE FROM technician_active_incidents");
    }

    private static String word() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 9; i++) {
            sb.append((char) ('a' + RANDOM.nextInt(26)));
        }
        return sb.toString();
    }

    /** A network ticket held by {@code holder} (given to him through the administrator's assignment when needed). */
    private Incident ticketHeldBy(Technician holder) throws Exception {
        StringBuilder description = new StringBuilder("The vpn and the gateway");
        for (int i = 0; i < 14; i++) {
            description.append(' ').append(word());
        }
        Incident ticket = incidentService.createIncident(EMPLOYEE, "VPN gateway " + word() + " " + word(),
                description.append(" vpn.").toString());
        created.add(ticket.getId());
        Assumptions.assumeTrue(ticket.getAssigneeId() != null, "ticket must be auto-assigned");
        if (!holder.getId().equals(ticket.getAssigneeId())) {
            send(post("/api/admin/incidents/" + ticket.getId() + "/assign"), ADMIN_ID,
                    "{\"technicianId\":\"" + holder.getId() + "\"}").andExpect(status().isOk());
        }
        return reload(ticket.getId());
    }

    private Incident reload(String id) {
        return incidentRepository.findById(id).orElseThrow();
    }

    private Technician tech(String id) {
        return technicianRepository.findById(id).orElseThrow();
    }

    private String tokenOf(String userId) {
        User user = userRepository.findById(userId).orElseThrow();
        return "Bearer " + tokenProvider.generateToken(user);
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String userId, String body) throws Exception {
        request = request.header("Authorization", tokenOf(userId));
        return mockMvc.perform(body == null ? request : request.contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions reassign(String incidentId, String userId, String technicianId, String reason) throws Exception {
        String body = "{" + (technicianId == null ? "" : "\"technicianId\":\"" + technicianId + "\"")
                + (technicianId != null && reason != null ? "," : "")
                + (reason == null ? "" : "\"reason\":\"" + reason + "\"") + "}";
        return send(post("/api/technician/incidents/" + incidentId + "/reassign"), userId, body);
    }

    private Set<String> violations() {
        return CapacityInvariant.check(technicianRepository, incidentRepository).stream()
                .map(v -> v.kind() + "|" + v.technicianId() + "|" + v.incidentId()).collect(Collectors.toSet());
    }

    private void assertNoNewViolations(Set<String> before) {
        Set<String> added = new HashSet<>(violations());
        added.removeAll(before);
        assertThat(added).as("capacity violations introduced by the scenario").isEmpty();
    }

    private void assertMovedTo(String ticketId, Technician from, Technician to) {
        Incident stored = reload(ticketId);
        assertThat(stored.getAssigneeId()).isEqualTo(to.getId());
        assertThat(stored.getStatus()).isEqualTo(IncidentStatus.ASSIGNED);
        assertThat(stored.isNeedsTriage()).isFalse();
        assertThat(tech(from.getId()).getActiveIncidentIds()).doesNotContain(ticketId);
        assertThat(tech(to.getId()).getActiveIncidentIds()).contains(ticketId);
    }

    // ---- allowed moves -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("A lead moves an ASSIGNED ticket: slots swap, one MANUAL decision names the lead, the reason is an internal note")
    void movesAnAssignedTicket() throws Exception {
        Technician from = members.get(0);
        Technician to = members.get(1);
        Incident ticket = ticketHeldBy(from);
        Set<String> before = violations();

        reassign(ticket.getId(), NETWORK_LEAD, to.getId(), REASON).andExpect(status().isOk())
                .andExpect(jsonPath("$.assigneeId").value(to.getId()))
                .andExpect(jsonPath("$.status").value("ASSIGNED"));

        assertMovedTo(ticket.getId(), from, to);
        List<AssignmentDecision> decisions = decisionRepository.findNewestByIncidentId(ticket.getId(), 10);
        AssignmentDecision newest = decisions.get(0);
        assertThat(newest.poolKind()).isEqualTo(PoolKind.MANUAL);
        assertThat(newest.chosenTechnicianId()).isEqualTo(to.getId());
        assertThat(newest.actorId()).isEqualTo(NETWORK_LEAD);
        assertThat(reload(ticket.getId()).getNotes())
                .anyMatch(n -> n.internalOnly() && n.authorId().equals(NETWORK_LEAD) && n.noteText().contains(REASON));
        assertNoNewViolations(before);
    }

    @Test
    @DisplayName("A lead moves an ACKNOWLEDGED ticket; the new technician has to acknowledge it again")
    void movesAnAcknowledgedTicket() throws Exception {
        Technician from = members.get(0);
        Technician to = members.get(2);
        Incident ticket = ticketHeldBy(from);
        incidentService.acknowledge(ticket.getId(), from.getId());
        Set<String> before = violations();

        reassign(ticket.getId(), NETWORK_LEAD, to.getId(), REASON).andExpect(status().isOk());

        assertMovedTo(ticket.getId(), from, to);
        assertNoNewViolations(before);
    }

    @Test
    @DisplayName("A lead moves an IN_PROGRESS ticket; notes and the recorded first response stay with the ticket")
    void movesAnInProgressTicket() throws Exception {
        Technician from = members.get(1);
        Technician to = members.get(0);
        Incident ticket = ticketHeldBy(from);
        incidentService.acknowledge(ticket.getId(), from.getId());
        incidentService.startInvestigation(ticket.getId(), from.getId());
        incidentService.addNote(ticket.getId(), from.getId(), "Checked the tunnel config", false);
        Set<String> before = violations();

        reassign(ticket.getId(), NETWORK_LEAD, to.getId(), REASON).andExpect(status().isOk());

        assertMovedTo(ticket.getId(), from, to);
        Incident stored = reload(ticket.getId());
        assertThat(stored.getNotes()).anyMatch(n -> n.noteText().contains("tunnel"));
        assertThat(stored.getSlaTracker().getRespondedAt()).isNotNull();
        assertNoNewViolations(before);
    }

    @Test
    @DisplayName("A lead assigns a waiting ticket (CLASSIFIED, not in triage) and can take it himself")
    void assignsAWaitingTicketToTheLead() throws Exception {
        Technician from = members.get(0);
        Incident ticket = ticketHeldBy(from);
        Incident waiting = reload(ticket.getId());
        waiting.flagWrongDomain(from.getId(), "setup: free the ticket", Instant.now());
        waiting.setNeedsTriage(false);
        waiting.clearExcludedTechnicians();
        incidentRepository.save(waiting);
        Technician holder = tech(from.getId());
        holder.releaseIncident(ticket.getId());
        technicianRepository.save(holder);
        assertThat(reload(ticket.getId()).getStatus()).isEqualTo(IncidentStatus.CLASSIFIED);
        Set<String> before = violations();

        reassign(ticket.getId(), NETWORK_LEAD, NETWORK_LEAD, REASON).andExpect(status().isOk())
                .andExpect(jsonPath("$.assigneeId").value(NETWORK_LEAD));

        assertThat(reload(ticket.getId()).getStatus()).isEqualTo(IncidentStatus.ASSIGNED);
        assertThat(tech(NETWORK_LEAD).getActiveIncidentIds()).contains(ticket.getId());
        assertNoNewViolations(before);
    }

    // ---- refusals ------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("A PENDING_INFO ticket cannot be reassigned by a lead: 409 and nothing changes")
    void pendingInfoIsRefused() throws Exception {
        Technician from = members.get(0);
        Incident ticket = ticketHeldBy(from);
        incidentService.acknowledge(ticket.getId(), from.getId());
        incidentService.startInvestigation(ticket.getId(), from.getId());
        incidentService.requestInfo(ticket.getId(), from.getId(), "Which office?");

        reassign(ticket.getId(), NETWORK_LEAD, members.get(1).getId(), REASON)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(BAD_STATE));

        assertThat(reload(ticket.getId()).getStatus()).isEqualTo(IncidentStatus.PENDING_INFO);
        assertThat(reload(ticket.getId()).getAssigneeId()).isEqualTo(from.getId());
    }

    @Test
    @DisplayName("A resolved ticket cannot be reassigned by a lead: 409")
    void resolvedIsRefused() throws Exception {
        Technician from = members.get(0);
        Incident ticket = ticketHeldBy(from);
        incidentService.acknowledge(ticket.getId(), from.getId());
        incidentService.startInvestigation(ticket.getId(), from.getId());
        incidentService.resolve(ticket.getId(), from.getId(), new Resolution("bad peer", "reset the session",
                "monitor", ticket.getCategory().name(), from.getId(), Instant.now()));

        reassign(ticket.getId(), NETWORK_LEAD, members.get(1).getId(), REASON)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(BAD_STATE));

        assertThat(reload(ticket.getId()).getAssigneeId()).isEqualTo(from.getId());
    }

    @Test
    @DisplayName("A ticket in triage stays with the administrator: 403 and nothing changes")
    void triageTicketIsRefused() throws Exception {
        Technician from = members.get(0);
        Incident ticket = ticketHeldBy(from);
        Incident flagged = reload(ticket.getId());
        flagged.setNeedsTriage(true);
        incidentRepository.save(flagged);

        reassign(ticket.getId(), NETWORK_LEAD, members.get(1).getId(), REASON).andExpect(status().isForbidden());

        assertThat(reload(ticket.getId()).getAssigneeId()).isEqualTo(from.getId());
        assertThat(tech(from.getId()).getActiveIncidentIds()).contains(ticket.getId());
    }

    @Test
    @DisplayName("The lead of another team cannot move this team's ticket: 403")
    void otherTeamsLeadIsRefused() throws Exception {
        Technician from = members.get(0);
        Incident ticket = ticketHeldBy(from);
        Technician databaseMember = technicianRepository.findById(DATABASE_LEAD).orElseThrow();

        reassign(ticket.getId(), DATABASE_LEAD, databaseMember.getId(), REASON).andExpect(status().isForbidden());

        assertThat(reload(ticket.getId()).getAssigneeId()).isEqualTo(from.getId());
    }

    @Test
    @DisplayName("A technician of another team cannot be given the ticket: 403 and nothing changes")
    void technicianOfAnotherTeamIsRefused() throws Exception {
        Technician from = members.get(0);
        Incident ticket = ticketHeldBy(from);

        reassign(ticket.getId(), NETWORK_LEAD, "TECH-DB-1", REASON).andExpect(status().isForbidden());

        assertThat(reload(ticket.getId()).getAssigneeId()).isEqualTo(from.getId());
        assertThat(tech("TECH-DB-1").getActiveIncidentIds()).doesNotContain(ticket.getId());
    }

    @Test
    @DisplayName("An unknown technician is 404; the same technician, a missing reason or a missing technician is 400")
    void badRequestsAreRefused() throws Exception {
        Technician from = members.get(0);
        Incident ticket = ticketHeldBy(from);

        reassign(ticket.getId(), NETWORK_LEAD, "TECH-NOPE-9", REASON).andExpect(status().isNotFound());
        reassign(ticket.getId(), NETWORK_LEAD, from.getId(), REASON).andExpect(status().isBadRequest());
        reassign(ticket.getId(), NETWORK_LEAD, members.get(1).getId(), null).andExpect(status().isBadRequest());
        reassign(ticket.getId(), NETWORK_LEAD, members.get(1).getId(), "   ").andExpect(status().isBadRequest());
        reassign(ticket.getId(), NETWORK_LEAD, null, REASON).andExpect(status().isBadRequest());
        reassign("ENG-1999-999999", NETWORK_LEAD, members.get(1).getId(), REASON).andExpect(status().isNotFound());

        assertThat(reload(ticket.getId()).getAssigneeId()).isEqualTo(from.getId());
    }

    @Test
    @DisplayName("An offline technician is refused with 409 and the ticket stays where it was")
    void offlineTargetIsRefused() throws Exception {
        Technician from = members.get(0);
        Technician to = members.get(1);
        Incident ticket = ticketHeldBy(from);
        to = tech(to.getId());
        to.updateAvailability(AvailabilityStatus.OFFLINE);
        technicianRepository.save(to);

        reassign(ticket.getId(), NETWORK_LEAD, to.getId(), REASON).andExpect(status().isConflict());

        assertThat(reload(ticket.getId()).getAssigneeId()).isEqualTo(from.getId());
        assertThat(tech(from.getId()).getActiveIncidentIds()).contains(ticket.getId());
    }

    @Test
    @DisplayName("A technician with no free slot is refused with 409 and the whole move is undone (the holder keeps the ticket)")
    void fullTargetIsRefusedAndNothingChanges() throws Exception {
        Technician from = members.get(0);
        Technician to = members.get(1);
        Incident ticket = ticketHeldBy(from);
        Technician full = tech(to.getId());
        for (int i = 0; full.hasCapacity(); i++) {
            full.assignIncident("FILLER-" + i);
        }
        technicianRepository.save(full);

        reassign(ticket.getId(), NETWORK_LEAD, to.getId(), REASON).andExpect(status().isConflict());

        Incident stored = reload(ticket.getId());
        assertThat(stored.getAssigneeId()).isEqualTo(from.getId());
        assertThat(stored.getStatus()).isEqualTo(IncidentStatus.ASSIGNED);
        assertThat(tech(from.getId()).getActiveIncidentIds()).contains(ticket.getId());
        assertThat(tech(to.getId()).getActiveIncidentIds()).doesNotContain(ticket.getId());
        assertThat(decisionRepository.findNewestByIncidentId(ticket.getId(), 10))
                .noneMatch(d -> d.chosenTechnicianId().equals(to.getId()));
    }

    @Test
    @DisplayName("Only a team lead may call the endpoint: technician, employee and administrator get 403")
    void otherRolesAreRefused() throws Exception {
        Technician from = members.get(0);
        Incident ticket = ticketHeldBy(from);
        String target = members.get(1).getId();

        reassign(ticket.getId(), from.getId(), target, REASON).andExpect(status().isForbidden());
        reassign(ticket.getId(), EMPLOYEE_ID, target, REASON).andExpect(status().isForbidden());
        reassign(ticket.getId(), ADMIN_ID, target, REASON).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/technician/incidents/" + ticket.getId() + "/reassign")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"technicianId\":\"" + target + "\",\"reason\":\"x\"}"))
                .andExpect(status().is4xxClientError());

        assertThat(reload(ticket.getId()).getAssigneeId()).isEqualTo(from.getId());
    }

    // ---- races and the server flag -----------------------------------------------------------------------------

    @Test
    @DisplayName("Two simultaneous moves of one ticket never end in a 500 or a broken slot count")
    void simultaneousMovesStayConsistent() throws Exception {
        Technician from = members.get(0);
        Incident ticket = ticketHeldBy(from);
        Set<String> before = violations();
        List<String> targets = List.of(members.get(1).getId(), members.get(2).getId());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (String target : targets) {
            Callable<Integer> call = () -> {
                start.await();
                return reassign(ticket.getId(), NETWORK_LEAD, target, REASON).andReturn().getResponse().getStatus();
            };
            results.add(pool.submit(call));
        }
        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : results) {
            statuses.add(f.get());
        }
        pool.shutdown();

        assertThat(statuses).allMatch(s -> s == 200 || s == 409);
        assertThat(statuses).contains(200);
        Incident stored = reload(ticket.getId());
        assertThat(targets).contains(stored.getAssigneeId());
        long holders = technicianRepository.findAll().stream()
                .filter(t -> t.getActiveIncidentIds().contains(ticket.getId())).count();
        assertThat(holders).as("exactly one technician holds the ticket's slot").isEqualTo(1);
        assertNoNewViolations(before);
    }

    @Test
    @DisplayName("The lead's cards carry reassignable=true for open team tickets and false for finished ones; technicians never get it")
    void serverFlagFollowsTheRule() throws Exception {
        Technician from = members.get(0);
        Incident open = ticketHeldBy(from);
        Incident finished = ticketHeldBy(from);
        incidentService.acknowledge(finished.getId(), from.getId());
        incidentService.startInvestigation(finished.getId(), from.getId());
        incidentService.resolve(finished.getId(), from.getId(), new Resolution("bad peer", "reset the session",
                "monitor", finished.getCategory().name(), from.getId(), Instant.now()));

        JsonNode leadQueue = objectMapper.readTree(send(get("/api/technician/team-queue"), NETWORK_LEAD, null)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(flagOf(leadQueue, open.getId())).isTrue();
        assertThat(flagOf(leadQueue, finished.getId())).isFalse();

        JsonNode techQueue = objectMapper.readTree(send(get("/api/technician/queue"), from.getId(), null)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(flagOf(techQueue, open.getId())).isFalse();
    }

    @Test
    @DisplayName("The employee's own view of the ticket has no reassignment field")
    void employeeViewHasNoSuchField() throws Exception {
        Incident ticket = ticketHeldBy(members.get(0));

        String body = send(get("/api/employee/incidents/" + ticket.getId()), EMPLOYEE_ID, null)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("reassignable").doesNotContain("assigneeId");
    }

    private static boolean flagOf(JsonNode queue, String ticketId) {
        for (JsonNode item : queue) {
            if (ticketId.equals(item.get("id").asText())) {
                return item.get("reassignable").asBoolean();
            }
        }
        throw new AssertionError("ticket " + ticketId + " is not in the queue");
    }
}
