package com.iitsm.regression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.iitsm.application.service.IncidentService;
import com.iitsm.domain.exception.ServiceNotFoundException;
import com.iitsm.domain.exception.UnauthorizedOperationException;
import com.iitsm.domain.model.AuditRecord;
import com.iitsm.domain.model.Feedback;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.IncidentStatus;
import com.iitsm.domain.model.ItService;
import com.iitsm.domain.model.ReassignmentRequest;
import com.iitsm.domain.model.Resolution;
import com.iitsm.domain.model.Technician;
import com.iitsm.domain.repository.AuditRepository;
import com.iitsm.domain.repository.IncidentRepository;
import com.iitsm.domain.repository.ItServiceRepository;
import com.iitsm.domain.repository.ReassignmentRequestRepository;
import com.iitsm.domain.repository.TechnicianRepository;
import com.iitsm.domain.repository.UserRepository;
import com.iitsm.infrastructure.persistence.CompanySeeder;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * H7 routing matrix: what a reclassify does with the technician, for every lifecycle state and for a move
 * inside or across teams (ADR-021). Open tickets that the state machine can carry move to the owning team;
 * tickets waiting for the employee and finished tickets keep their technician.
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("regression")
class ReclassifyRoutingRegressionTest {

    private static final String EMPLOYEE = "employee@nimbusfinserve.com";
    private static final String ADMIN_EMAIL = "admin@nimbusfinserve.com";

    @Autowired private IncidentService incidentService;
    @Autowired private IncidentRepository incidentRepository;
    @Autowired private TechnicianRepository technicianRepository;
    @Autowired private ItServiceRepository itServiceRepository;
    @Autowired private ReassignmentRequestRepository reassignmentRequestRepository;
    @Autowired private AuditRepository auditRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private CompanySeeder companySeeder;
    @Autowired private com.iitsm.domain.repository.SupportTeamRepository supportTeamRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM technician_active_incidents");
        if (userRepository.count() == 0) {
            companySeeder.seedCompanyData();
        }
        technicianRepository.findAll().forEach(t -> {
            if (t.getActiveIncidentCount() > 0) {
                new HashSet<>(t.getActiveIncidentIds()).forEach(t::releaseIncident);
                technicianRepository.save(t);
            }
        });
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("DELETE FROM technician_active_incidents");
    }

    private Incident assignedTicket() {
        Incident created = incidentService.createIncident(EMPLOYEE, "VPN gateway timeouts for the whole office",
                "AnyConnect drops every few minutes with heavy packet loss on the office gateway tunnel.");
        Assumptions.assumeTrue(created.getAssigneeId() != null, "ticket must be auto-assigned");
        return created;
    }

    /** The team that handles the category (ADR-036: the category decides the team, not the service). */
    private String handlingTeam(IncidentCategory category) {
        return supportTeamRepository.findByHandledCategory(category).stream()
                .map(com.iitsm.domain.model.SupportTeam::getId).findFirst().orElseThrow();
    }

    private String teamOf(String technicianId) {
        return technicianRepository.findById(technicianId).orElseThrow().getSupportTeamId();
    }

    private ItService serviceOutsideTeamOf(String technicianId) {
        String team = teamOf(technicianId);
        return itServiceRepository.findAll().stream()
                .filter(s -> s.getOwningTeamId() != null && !s.getOwningTeamId().equals(team))
                .findFirst().orElseThrow();
    }

    /** Carries a freshly assigned ticket through the lifecycle up to {@code target}. */
    private void advanceTo(Incident ticket, IncidentStatus target) {
        String tech = ticket.getAssigneeId();
        String id = ticket.getId();
        if (target == IncidentStatus.ASSIGNED) {
            return;
        }
        incidentService.acknowledge(id, tech);
        if (target == IncidentStatus.ACKNOWLEDGED) {
            return;
        }
        incidentService.startInvestigation(id, tech);
        if (target == IncidentStatus.IN_PROGRESS) {
            return;
        }
        if (target == IncidentStatus.PENDING_INFO) {
            incidentService.requestInfo(id, tech, "Which office is affected?");
            return;
        }
        incidentService.resolve(id, tech, new Resolution("Root cause", "Steps", "Prevention", "SOFTWARE", tech, Instant.now()));
        if (target == IncidentStatus.RESOLVED) {
            return;
        }
        incidentService.verify(id, EMPLOYEE);
        if (target == IncidentStatus.CLOSED) {
            incidentService.close(id, EMPLOYEE, new Feedback(5, "Thanks", Instant.now()));
        }
    }

    private Incident load(String id) {
        return incidentRepository.findById(id).orElseThrow();
    }

    private void fillTeam(String teamId) {
        for (Technician t : technicianRepository.findBySupportTeamId(teamId)) {
            for (int slot = 0; slot < t.getMaxCapacity(); slot++) {
                t.assignIncident("DUMMY-SLOT-" + slot);
            }
            technicianRepository.save(t);
        }
    }

    private List<AuditRecord> records(String incidentId, String action) {
        return auditRepository.findByIncidentIdOrderByTimestampAsc(incidentId).stream()
                .filter(r -> action.equals(r.action())).toList();
    }

    @ParameterizedTest(name = "{0} ticket moves to the owning team")
    @EnumSource(value = IncidentStatus.class, names = {"ASSIGNED", "ACKNOWLEDGED", "IN_PROGRESS"})
    @DisplayName("H7 T1: an open ticket held by the wrong team is handed to a technician of the team that handles the new category")
    void openTicketMovesToOwningTeam(IncidentStatus state) {
        Incident ticket = assignedTicket();
        String oldTech = ticket.getAssigneeId();
        advanceTo(ticket, state);
        ItService target = serviceOutsideTeamOf(oldTech);

        incidentService.reclassify(ticket.getId(), ADMIN_EMAIL, IncidentCategory.SECURITY, target.getId(), "Wrong domain");

        Incident after = load(ticket.getId());
        assertThat(after.getAssigneeId()).isNotNull().isNotEqualTo(oldTech);
        assertThat(teamOf(after.getAssigneeId())).isEqualTo(handlingTeam(IncidentCategory.SECURITY));
        assertThat(after.getStatus()).isEqualTo(IncidentStatus.ASSIGNED);
        assertThat(technicianRepository.findById(oldTech).orElseThrow().getActiveIncidentIds())
                .doesNotContain(ticket.getId());
        assertThat(technicianRepository.findById(after.getAssigneeId()).orElseThrow().getActiveIncidentIds())
                .contains(ticket.getId());
    }

    @Test
    @DisplayName("H7 T2: a PENDING_INFO ticket keeps its technician, state and paused clock (ADR-021)")
    void pendingInfoTicketIsNotMoved() {
        Incident ticket = assignedTicket();
        String oldTech = ticket.getAssigneeId();
        advanceTo(ticket, IncidentStatus.PENDING_INFO);
        Instant pausedAt = load(ticket.getId()).getSlaTracker().getPausedAt();
        Assumptions.assumeTrue(pausedAt != null);

        incidentService.reclassify(ticket.getId(), ADMIN_EMAIL, IncidentCategory.SECURITY,
                serviceOutsideTeamOf(oldTech).getId(), "Wrong domain");

        Incident after = load(ticket.getId());
        assertThat(after.getStatus()).isEqualTo(IncidentStatus.PENDING_INFO);
        assertThat(after.getAssigneeId()).isEqualTo(oldTech);
        assertThat(after.getSlaTracker().getPausedAt()).isEqualTo(pausedAt);
    }

    @ParameterizedTest(name = "{0} ticket stays with its technician")
    @EnumSource(value = IncidentStatus.class, names = {"RESOLVED", "VERIFIED", "CLOSED"})
    @DisplayName("H7 T3: a finished ticket keeps its technician and status")
    void finishedTicketIsNotMoved(IncidentStatus state) {
        Incident ticket = assignedTicket();
        String oldTech = ticket.getAssigneeId();
        advanceTo(ticket, state);
        Assumptions.assumeTrue(load(ticket.getId()).getStatus() == state);

        incidentService.reclassify(ticket.getId(), ADMIN_EMAIL, IncidentCategory.SECURITY,
                serviceOutsideTeamOf(oldTech).getId(), "Label correction");

        Incident after = load(ticket.getId());
        assertThat(after.getStatus()).isEqualTo(state);
        assertThat(after.getAssigneeId()).isEqualTo(oldTech);
        assertThat(after.getCategory()).isEqualTo(IncidentCategory.SECURITY);
    }

    @ParameterizedTest(name = "{0} ticket reclassified inside its own team is not moved")
    @EnumSource(value = IncidentStatus.class, names = {"ASSIGNED", "ACKNOWLEDGED", "IN_PROGRESS"})
    @DisplayName("H7 T4: a reclassify inside the technician's own team changes nothing about the assignment")
    void sameTeamReclassifyKeepsAssignment(IncidentStatus state) {
        Incident ticket = assignedTicket();
        String tech = ticket.getAssigneeId();
        advanceTo(ticket, state);
        IncidentStatus before = load(ticket.getId()).getStatus();

        incidentService.reclassify(ticket.getId(), ADMIN_EMAIL, IncidentCategory.NETWORK, ticket.getServiceId(), "Label correction");

        Incident after = load(ticket.getId());
        assertThat(after.getAssigneeId()).isEqualTo(tech);
        assertThat(after.getStatus()).isEqualTo(before);
    }

    @Test
    @DisplayName("H7 T5: with no free technician in the owning team the ticket waits in CLASSIFIED and the old one is released")
    void fullOwningTeamLeavesTicketWaiting() {
        Incident ticket = assignedTicket();
        String oldTech = ticket.getAssigneeId();
        incidentService.acknowledge(ticket.getId(), oldTech);
        ItService target = serviceOutsideTeamOf(oldTech);
        fillTeam(handlingTeam(IncidentCategory.SECURITY));

        incidentService.reclassify(ticket.getId(), ADMIN_EMAIL, IncidentCategory.SECURITY, target.getId(), "Wrong domain");

        Incident after = load(ticket.getId());
        assertThat(after.getStatus()).isEqualTo(IncidentStatus.CLASSIFIED);
        assertThat(after.getAssigneeId()).isNull();
        assertThat(technicianRepository.findById(oldTech).orElseThrow().getActiveIncidentIds())
                .doesNotContain(ticket.getId());
    }

    @Test
    @DisplayName("H7 T6: the technician the ticket was handed to gets an IncidentAssigned entry (and so a notification)")
    void rerouteRecordsTheAssignment() {
        Incident ticket = assignedTicket();
        String oldTech = ticket.getAssigneeId();
        int before = records(ticket.getId(), "IncidentAssigned").size();

        incidentService.reclassify(ticket.getId(), ADMIN_EMAIL, IncidentCategory.SECURITY,
                serviceOutsideTeamOf(oldTech).getId(), "Wrong domain");

        List<AuditRecord> assigned = records(ticket.getId(), "IncidentAssigned");
        String newTech = load(ticket.getId()).getAssigneeId();
        assertThat(assigned).hasSize(before + 1);
        assertThat(assigned.get(assigned.size() - 1).description()).contains(newTech);
    }

    @Test
    @DisplayName("H7 T7: a reclassify closes the pending wrong-domain request for the ticket")
    void reclassifyApprovesPendingReassignmentRequest() {
        Incident ticket = assignedTicket();
        String oldTech = ticket.getAssigneeId();
        incidentService.flagWrongDomain(ticket.getId(), oldTech, "Not my area");
        assertThat(reassignmentRequestRepository.findByIncidentId(ticket.getId()))
                .anyMatch(r -> r.getStatus() == ReassignmentRequest.Status.PENDING);

        incidentService.reclassify(ticket.getId(), ADMIN_EMAIL, IncidentCategory.SECURITY,
                serviceOutsideTeamOf(oldTech).getId(), "Wrong domain");

        assertThat(reassignmentRequestRepository.findByIncidentId(ticket.getId()))
                .noneMatch(r -> r.getStatus() == ReassignmentRequest.Status.PENDING);
    }

    @Test
    @DisplayName("H7 T8: reclassifying A to B and back to A ends with a technician of team A")
    void roundTripEndsInTheOriginalTeam() {
        Incident ticket = assignedTicket();
        String oldTech = ticket.getAssigneeId();
        String teamA = teamOf(oldTech);
        ItService serviceB = serviceOutsideTeamOf(oldTech);
        ItService serviceA = itServiceRepository.findById(ticket.getServiceId()).orElseThrow();
        Assumptions.assumeTrue(teamA.equals(serviceA.getOwningTeamId()));

        incidentService.reclassify(ticket.getId(), ADMIN_EMAIL, IncidentCategory.SECURITY, serviceB.getId(), "To B");
        incidentService.reclassify(ticket.getId(), ADMIN_EMAIL, IncidentCategory.NETWORK, serviceA.getId(), "Back to A");

        Incident after = load(ticket.getId());
        assertThat(teamOf(after.getAssigneeId())).isEqualTo(teamA);
        long holders = technicianRepository.findAll().stream()
                .filter(t -> t.getActiveIncidentIds().contains(ticket.getId())).count();
        assertThat(holders).isEqualTo(1);
    }

    @Test
    @DisplayName("H7 T9: a reclassify clears the low-confidence triage flag")
    void reclassifyClearsTriageFlag() {
        Incident ticket = assignedTicket();
        Incident flagged = load(ticket.getId());
        flagged.setNeedsTriage(true);
        incidentRepository.save(flagged);

        incidentService.reclassify(ticket.getId(), ADMIN_EMAIL, IncidentCategory.SECURITY, ticket.getServiceId(), "Triaged");

        assertThat(load(ticket.getId()).isNeedsTriage()).isFalse();
    }

    @Test
    @DisplayName("H7 T10: an employee cannot reclassify a ticket")
    void employeeCannotReclassify() {
        Incident ticket = assignedTicket();

        assertThatThrownBy(() -> incidentService.reclassify(ticket.getId(), EMPLOYEE, IncidentCategory.SECURITY,
                ticket.getServiceId(), "Nope"))
                .isInstanceOf(UnauthorizedOperationException.class);
        assertThat(load(ticket.getId()).getCategory()).isEqualTo(ticket.getCategory());
    }

    @Test
    @DisplayName("H7 T11: reclassifying a ticket that does not exist is a not-found error")
    void unknownTicketIsNotFound() {
        assertThatThrownBy(() -> incidentService.reclassify("NOPE-2026-000001", ADMIN_EMAIL, IncidentCategory.SECURITY,
                null, "Nope"))
                .isInstanceOf(ServiceNotFoundException.class);
    }
}
