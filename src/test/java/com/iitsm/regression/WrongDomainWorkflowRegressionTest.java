package com.iitsm.regression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iitsm.application.service.IncidentService;
import com.iitsm.domain.model.AuditRecord;
import com.iitsm.domain.model.FactorContribution;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.IncidentStatus;
import com.iitsm.domain.model.Priority;
import com.iitsm.domain.model.PriorityDecision;
import com.iitsm.domain.model.ReassignmentRequest;
import com.iitsm.domain.model.SlaPolicy;
import com.iitsm.domain.model.Technician;
import com.iitsm.domain.model.User;
import com.iitsm.domain.model.UserRole;
import com.iitsm.domain.repository.IncidentRepository;
import com.iitsm.domain.repository.ReassignmentRequestRepository;
import com.iitsm.domain.repository.SupportTeamRepository;
import com.iitsm.domain.repository.TechnicianRepository;
import com.iitsm.domain.repository.UserRepository;
import com.iitsm.application.service.AuditService;
import com.iitsm.infrastructure.persistence.CompanySeeder;
import com.iitsm.infrastructure.security.JwtTokenProvider;
import com.iitsm.testsupport.CapacityInvariant;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
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
 * Remaining-defects plan, Phase C2 (ADR-029, decision D8): the wrong-domain workflow end to end. A technician may flag a
 * ticket he is already investigating; the administrator sees one triage entry per ticket (the technician's reason and the
 * classifier's suggestion together) and can reject the request, which gives the ticket back to the technician with the
 * reason recorded. Every scenario also checks the capacity invariant, because a flag releases a technician's slot.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("regression")
class WrongDomainWorkflowRegressionTest {

    private static final String EMPLOYEE = "employee@nimbusfinserve.com";
    private static final String ADMIN_ID = "ADMIN-SYS-01";
    private static final String BAD_STATE = "ERR_INVALID_STATE_TRANSITION";
    private static final String FLAG_REASON = "This is a database fault, not a network one";
    private static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");
    private static final Random RANDOM = new Random(43);

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtTokenProvider tokenProvider;
    @Autowired private IncidentService incidentService;
    @Autowired private IncidentRepository incidentRepository;
    @Autowired private TechnicianRepository technicianRepository;
    @Autowired private ReassignmentRequestRepository reassignmentRequestRepository;
    @Autowired private SupportTeamRepository supportTeamRepository;
    @Autowired private AuditService auditService;
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

    /** An auto-assigned ticket that the technician is investigating (IN_PROGRESS). */
    private Incident inProgressTicket() {
        Incident ticket = assignedTicket();
        incidentService.acknowledge(ticket.getId(), ticket.getAssigneeId());
        incidentService.startInvestigation(ticket.getId(), ticket.getAssigneeId());
        return reload(ticket.getId());
    }

    private Incident reload(String id) {
        return incidentRepository.findById(id).orElseThrow();
    }

    private String tokenOf(String userId) {
        User user = userRepository.findById(userId).orElseThrow();
        return "Bearer " + tokenProvider.generateToken(user);
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String userId, String body) throws Exception {
        request = request.header("Authorization", tokenOf(userId));
        return mockMvc.perform(body == null ? request : request.contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions flag(Incident ticket, String userId) throws Exception {
        return send(post("/api/technician/incidents/" + ticket.getId() + "/flag-wrong-domain"), userId,
                "{\"reason\":\"" + FLAG_REASON + "\"}");
    }

    private ReassignmentRequest pendingRequestOf(String incidentId) {
        List<ReassignmentRequest> pending = reassignmentRequestRepository.findByIncidentId(incidentId).stream()
                .filter(r -> r.getStatus() == ReassignmentRequest.Status.PENDING).collect(Collectors.toList());
        assertThat(pending).as("exactly one pending request for " + incidentId).hasSize(1);
        return pending.get(0);
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

    private JsonNode triageQueueEntriesOf(String incidentId) throws Exception {
        String body = send(get("/api/admin/triage-queue"), ADMIN_ID, null).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<JsonNode> entries = new java.util.ArrayList<>();
        objectMapper.readTree(body).forEach(item -> {
            if (incidentId.equals(item.get("incidentId").asText())) {
                entries.add(item);
            }
        });
        return objectMapper.valueToTree(entries);
    }

    // ---- (a) a technician may flag a ticket he is investigating --------------------------------------------------

    @Test
    @DisplayName("D8a: a technician can flag a ticket from IN_PROGRESS; it returns to the pool, notes and the recorded response are kept, the slot is freed")
    void technicianCanFlagFromInProgress() throws Exception {
        Incident ticket = inProgressTicket();
        String technicianId = ticket.getAssigneeId();
        incidentService.addNote(ticket.getId(), technicianId, "Checked the tunnel config", false);
        Set<String> before = violations();

        flag(ticket, technicianId).andExpect(status().isNoContent());

        Incident stored = reload(ticket.getId());
        assertThat(stored.getStatus()).isEqualTo(IncidentStatus.CLASSIFIED);
        assertThat(stored.getAssigneeId()).isNull();
        assertThat(stored.isNeedsTriage()).isTrue();
        assertThat(stored.getExcludedTechnicianIds()).contains(technicianId);
        assertThat(stored.getNotes()).as("the investigation notes stay with the ticket").anyMatch(n -> n.noteText().contains("tunnel"));
        assertThat(stored.getSlaTracker().getRespondedAt()).as("the recorded first response stays").isNotNull();
        assertThat(technicianRepository.findById(technicianId).orElseThrow().getActiveIncidentIds()).doesNotContain(ticket.getId());
        assertThat(pendingRequestOf(ticket.getId()).getRequestingTechnicianId()).isEqualTo(technicianId);
        assertNoNewViolations(before);
    }

    @Test
    @DisplayName("D8a: a ticket waiting for the employee (PENDING_INFO) still cannot be flagged")
    void pendingInfoTicketCannotBeFlagged() throws Exception {
        Incident ticket = inProgressTicket();
        incidentService.requestInfo(ticket.getId(), ticket.getAssigneeId(), "Which office?");

        flag(ticket, ticket.getAssigneeId()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(BAD_STATE));

        assertThat(reload(ticket.getId()).getStatus()).isEqualTo(IncidentStatus.PENDING_INFO);
    }

    @Test
    @DisplayName("A team lead flagging a ticket held by a technician frees the technician's slot, not the lead's")
    void teamLeadFlagFreesTheAssigneesSlot() throws Exception {
        Incident ticket = assignedTicket();
        String teamId = incidentService.resolveOwningTeamId(ticket);
        String leadId = supportTeamRepository.findById(teamId).orElseThrow().getTeamLeadId();
        // The automatic assignment may pick the lead himself, so give the ticket to a regular member of the team.
        Technician member = technicianRepository.findBySupportTeamId(teamId).stream()
                .filter(t -> t.getRole() == UserRole.ROLE_TECHNICIAN && t.hasCapacity()).findFirst().orElseThrow();
        if (!member.getId().equals(ticket.getAssigneeId())) {
            send(post("/api/admin/incidents/" + ticket.getId() + "/assign"), ADMIN_ID,
                    "{\"technicianId\":\"" + member.getId() + "\"}").andExpect(status().isOk());
        }
        Assumptions.assumeTrue(leadId != null && !leadId.equals(member.getId()), "a lead other than the member");
        Set<String> before = violations();

        flag(reload(ticket.getId()), leadId).andExpect(status().isNoContent());

        assertThat(reload(ticket.getId()).getAssigneeId()).isNull();
        assertThat(technicianRepository.findById(member.getId()).orElseThrow().getActiveIncidentIds())
                .as("the technician who held the ticket no longer holds its slot").doesNotContain(ticket.getId());
        assertNoNewViolations(before);
    }

    // ---- (b) one triage entry per ticket -------------------------------------------------------------------------

    @Test
    @DisplayName("D8b: a flagged ticket is listed once in the triage queue, with the technician's reason, the request id and the classifier's suggestion")
    void flaggedTicketIsListedOnce() throws Exception {
        Incident ticket = inProgressTicket();
        flag(ticket, ticket.getAssigneeId()).andExpect(status().isNoContent());

        JsonNode entries = triageQueueEntriesOf(ticket.getId());

        assertThat(entries).as("one entry per ticket").hasSize(1);
        JsonNode entry = entries.get(0);
        assertThat(entry.get("triageType").asText()).isEqualTo("WRONG_DOMAIN");
        assertThat(entry.get("reason").asText()).isEqualTo(FLAG_REASON);
        assertThat(entry.get("requestingTechnicianId").asText()).isEqualTo(ticket.getAssigneeId());
        assertThat(entry.get("requestId").asText()).isEqualTo(pendingRequestOf(ticket.getId()).getId());
        assertThat(entry.get("suggestions")).as("the classifier's suggestion is shown with the flag").isNotEmpty();
    }

    @Test
    @DisplayName("D8b control: a low-confidence ticket is still listed once, as LOW_CONFIDENCE, without a technician or a request")
    void lowConfidenceTicketKeepsItsEntry() throws Exception {
        Incident incident = new Incident("INC-WD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                "Something odd " + word(), "Things behave strangely since yesterday " + word() + " " + word() + ".", "EMP-DEMO-01", NOW);
        incident.classify(IncidentCategory.APPLICATION, "SVC-VPN", new PriorityDecision(Priority.P3, 0.4,
                List.of(new FactorContribution("Crit", 0.4, 1.0, 0.4, "test")), List.of(), NOW, null, null),
                SlaPolicy.defaultFor(Priority.P3), NOW);
        incident.setNeedsTriage(true);
        incidentRepository.save(incident);

        JsonNode entries = triageQueueEntriesOf(incident.getId());

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).get("triageType").asText()).isEqualTo("LOW_CONFIDENCE");
        assertThat(entries.get(0).hasNonNull("requestingTechnicianId")).isFalse();
        assertThat(entries.get(0).hasNonNull("requestId")).isFalse();
    }

    @Test
    @DisplayName("At most one pending request per ticket: a request is closed when the administrator reassigns, so a second flag leaves one pending")
    void atMostOnePendingRequestPerTicket() throws Exception {
        Incident ticket = inProgressTicket();
        String first = ticket.getAssigneeId();
        flag(ticket, first).andExpect(status().isNoContent());
        Technician other = technicianRepository.findAll().stream()
                .filter(t -> !t.getId().equals(first) && t.hasCapacity()).findFirst().orElseThrow();
        send(post("/api/admin/incidents/" + ticket.getId() + "/assign"), ADMIN_ID,
                "{\"technicianId\":\"" + other.getId() + "\"}").andExpect(status().isOk());
        incidentService.acknowledge(ticket.getId(), other.getId());

        flag(reload(ticket.getId()), other.getId()).andExpect(status().isNoContent());

        assertThat(pendingRequestOf(ticket.getId()).getRequestingTechnicianId()).isEqualTo(other.getId());
        assertThat(reassignmentRequestRepository.findByIncidentId(ticket.getId())).hasSize(2);
    }

    // ---- (c) the administrator can reject a request ---------------------------------------------------------------

    @Test
    @DisplayName("D8c: rejecting a request gives the ticket back to the technician, closes the request as REJECTED and records the reason")
    void rejectGivesTheTicketBack() throws Exception {
        Incident ticket = inProgressTicket();
        String technicianId = ticket.getAssigneeId();
        flag(ticket, technicianId).andExpect(status().isNoContent());
        ReassignmentRequest request = pendingRequestOf(ticket.getId());
        Set<String> before = violations();

        send(post("/api/admin/reassignments/" + request.getId() + "/reject"), ADMIN_ID,
                "{\"reason\":\"It really is a network issue, please carry on\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ASSIGNED"))
                .andExpect(jsonPath("$.assigneeId").value(technicianId));

        Incident stored = reload(ticket.getId());
        assertThat(stored.getStatus()).isEqualTo(IncidentStatus.ASSIGNED);
        assertThat(stored.getAssigneeId()).isEqualTo(technicianId);
        assertThat(stored.isNeedsTriage()).isFalse();
        assertThat(stored.getExcludedTechnicianIds()).as("the technician may work on it again").doesNotContain(technicianId);
        assertThat(technicianRepository.findById(technicianId).orElseThrow().getActiveIncidentIds()).contains(ticket.getId());
        ReassignmentRequest closed = reassignmentRequestRepository.findById(request.getId()).orElseThrow();
        assertThat(closed.getStatus()).isEqualTo(ReassignmentRequest.Status.REJECTED);
        assertThat(closed.getReviewedByAdminId()).isEqualTo(ADMIN_ID);
        assertThat(stored.getNotes()).as("the reason is on the ticket for the technician")
                .anyMatch(n -> n.noteText().contains("please carry on") && ADMIN_ID.equals(n.authorId()));
        List<AuditRecord> trail = auditService.getAuditTrailForIncident(ticket.getId());
        assertThat(trail).anyMatch(r -> "IncidentAssigned".equals(r.getAction()) && ADMIN_ID.equals(r.getActorId()));
        assertThat(triageQueueEntriesOf(ticket.getId())).as("no longer in the triage queue").isEmpty();
        assertNoNewViolations(before);
    }

    @Test
    @DisplayName("D8c: a request that is not pending, an unknown request, a blank reason and a non-administrator are refused")
    void rejectIsRefusedWhenItDoesNotApply() throws Exception {
        Incident ticket = inProgressTicket();
        flag(ticket, ticket.getAssigneeId()).andExpect(status().isNoContent());
        String requestId = pendingRequestOf(ticket.getId()).getId();
        String reject = "/api/admin/reassignments/" + requestId + "/reject";

        send(post(reject), ADMIN_ID, "{\"reason\":\"\"}").andExpect(status().isBadRequest());
        send(post(reject), ticket.getAssigneeId(), "{\"reason\":\"carry on\"}").andExpect(status().isForbidden());
        send(post("/api/admin/reassignments/REASSIGN-NOSUCH/reject"), ADMIN_ID, "{\"reason\":\"carry on\"}")
                .andExpect(status().isNotFound());
        assertThat(reload(ticket.getId()).getStatus()).as("nothing happened yet").isEqualTo(IncidentStatus.CLASSIFIED);

        send(post(reject), ADMIN_ID, "{\"reason\":\"carry on\"}").andExpect(status().isOk());
        send(post(reject), ADMIN_ID, "{\"reason\":\"carry on\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ERR_REQUEST_NOT_PENDING"));
    }

    @Test
    @DisplayName("D8c: when the technician has no free capacity the reject is refused (409) and nothing changes")
    void rejectIsRefusedWhenTheTechnicianIsFull() throws Exception {
        Incident ticket = inProgressTicket();
        String technicianId = ticket.getAssigneeId();
        flag(ticket, technicianId).andExpect(status().isNoContent());
        int capacity = technicianRepository.findById(technicianId).orElseThrow().getMaxCapacity();
        for (int slot = 0; slot < capacity; slot++) {
            jdbcTemplate.update("INSERT INTO technician_active_incidents (technician_id, incident_id) VALUES (?, ?)",
                    technicianId, "INC-FILL-" + slot);
        }

        send(post("/api/admin/reassignments/" + pendingRequestOf(ticket.getId()).getId() + "/reject"), ADMIN_ID,
                "{\"reason\":\"carry on\"}").andExpect(status().isConflict());

        Incident stored = reload(ticket.getId());
        assertThat(stored.getStatus()).isEqualTo(IncidentStatus.CLASSIFIED);
        assertThat(stored.isNeedsTriage()).isTrue();
        assertThat(pendingRequestOf(ticket.getId())).isNotNull();
    }

    // ---- control: the administrator's own reassignment of a ticket in progress -------------------------------------

    @Test
    @DisplayName("Control: an administrator's reassignment of an IN_PROGRESS ticket moves it and its slot to the new technician")
    void administratorReassignsAnInProgressTicket() throws Exception {
        Incident ticket = inProgressTicket();
        String first = ticket.getAssigneeId();
        Technician other = technicianRepository.findAll().stream()
                .filter(t -> !t.getId().equals(first) && t.hasCapacity()).findFirst().orElseThrow();
        Set<String> before = violations();

        send(post("/api/admin/incidents/" + ticket.getId() + "/assign"), ADMIN_ID,
                "{\"technicianId\":\"" + other.getId() + "\"}").andExpect(status().isOk());

        Incident stored = reload(ticket.getId());
        assertThat(stored.getStatus()).isEqualTo(IncidentStatus.ASSIGNED);
        assertThat(stored.getAssigneeId()).isEqualTo(other.getId());
        assertThat(technicianRepository.findById(first).orElseThrow().getActiveIncidentIds()).doesNotContain(ticket.getId());
        assertThat(technicianRepository.findById(other.getId()).orElseThrow().getActiveIncidentIds()).contains(ticket.getId());
        assertNoNewViolations(before);
    }
}
