package com.iitsm.regression;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitsm.application.service.IncidentService;
import com.iitsm.domain.model.Feedback;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.IncidentStatus;
import com.iitsm.domain.model.ItService;
import com.iitsm.domain.model.Priority;
import com.iitsm.domain.model.Resolution;
import com.iitsm.domain.model.SlaStatus;
import com.iitsm.domain.model.SlaTracker;
import com.iitsm.domain.model.Technician;
import com.iitsm.domain.repository.IncidentRepository;
import com.iitsm.domain.repository.ItServiceRepository;
import com.iitsm.domain.repository.TechnicianRepository;
import com.iitsm.domain.repository.UserRepository;
import com.iitsm.infrastructure.persistence.CompanySeeder;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * H7 regression (stress audit 2026-10-31): an admin reclassify rebuilt the SLA tracker from the creation
 * time and recalculated the priority from scratch. A manual override, the recorded response time, a paused
 * clock and the SLA verdict of a closed ticket were all lost, and the assignee stayed in the old team.
 * Each test below is one symptom from the audit.
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("regression")
class ReclassifyStateRegressionTest {

    private static final String EMPLOYEE = "employee@nimbusfinserve.com";
    private static final String ADMIN_EMAIL = "admin@nimbusfinserve.com";
    private static final String REASON = "Business impact is higher than first assessed";

    @Autowired private IncidentService incidentService;
    @Autowired private IncidentRepository incidentRepository;
    @Autowired private TechnicianRepository technicianRepository;
    @Autowired private ItServiceRepository itServiceRepository;
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
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("DELETE FROM technician_active_incidents");
    }

    /** A VPN ticket: classified as a network incident and auto-assigned to a Network technician. */
    private Incident assignedTicket() {
        Incident created = incidentService.createIncident(EMPLOYEE, "VPN gateway timeouts for the whole office",
                "AnyConnect drops every few minutes with heavy packet loss on the office gateway tunnel.");
        Assumptions.assumeTrue(created.getAssigneeId() != null, "ticket must be auto-assigned");
        return created;
    }

    private String teamOf(String technicianId) {
        return technicianRepository.findById(technicianId).orElseThrow().getSupportTeamId();
    }

    /** An IT service owned by a different team than the technician's, so a reclassify crosses teams. */
    private ItService serviceOutsideTeamOf(String technicianId) {
        String team = teamOf(technicianId);
        return itServiceRepository.findAll().stream()
                .filter(s -> s.getOwningTeamId() != null && !s.getOwningTeamId().equals(team))
                .findFirst().orElseThrow();
    }

    private Incident reclassifyToOtherTeam(Incident ticket) {
        ItService target = serviceOutsideTeamOf(ticket.getAssigneeId());
        return incidentService.reclassify(ticket.getId(), ADMIN_EMAIL, IncidentCategory.SECURITY, target.getId(),
                "Wrong domain, belongs to another team");
    }

    private SlaTracker reload(String id) {
        return incidentRepository.findById(id).orElseThrow().getSlaTracker();
    }

    private void resolve(Incident ticket) {
        String tech = ticket.getAssigneeId();
        incidentService.acknowledge(ticket.getId(), tech);
        incidentService.startInvestigation(ticket.getId(), tech);
        incidentService.resolve(ticket.getId(), tech,
                new Resolution("Root cause", "Steps", "Prevention", "SOFTWARE", tech, Instant.now()));
    }

    @Test
    @DisplayName("H7 R1: an acknowledged ticket with a P1 override stays P1 with its override author and reason")
    void overrideSurvivesReclassify() {
        Incident ticket = assignedTicket();
        Assumptions.assumeTrue(ticket.getPriority() != Priority.P1);
        incidentService.acknowledge(ticket.getId(), ticket.getAssigneeId());
        incidentService.overridePriority(ticket.getId(), ADMIN_EMAIL, Priority.P1, REASON);

        reclassifyToOtherTeam(ticket);

        Incident after = incidentRepository.findById(ticket.getId()).orElseThrow();
        assertThat(after.getPriorityDecision().isOverridden()).isTrue();
        assertThat(after.getPriorityDecision().overriddenBy()).isNotBlank();
        assertThat(after.getPriorityDecision().overrideReason()).isEqualTo(REASON);
        assertThat(after.getPriority()).isEqualTo(Priority.P1);
    }

    @Test
    @DisplayName("H7 R2: the recorded response time survives reclassify")
    void respondedAtSurvivesReclassify() {
        Incident ticket = assignedTicket();
        incidentService.acknowledge(ticket.getId(), ticket.getAssigneeId());
        Instant responded = reload(ticket.getId()).getRespondedAt();
        Assumptions.assumeTrue(responded != null, "acknowledge must record the response");

        reclassifyToOtherTeam(ticket);

        assertThat(reload(ticket.getId()).getRespondedAt()).isEqualTo(responded);
    }

    @Test
    @DisplayName("H7 R3: a PENDING_INFO ticket keeps its paused SLA clock after reclassify")
    void pausedClockSurvivesReclassify() {
        Incident ticket = assignedTicket();
        String tech = ticket.getAssigneeId();
        incidentService.acknowledge(ticket.getId(), tech);
        incidentService.startInvestigation(ticket.getId(), tech);
        incidentService.requestInfo(ticket.getId(), tech, "Which office is affected?");
        SlaTracker before = reload(ticket.getId());
        Assumptions.assumeTrue(before.isPaused(), "PENDING_INFO must pause the clock");

        reclassifyToOtherTeam(ticket);

        SlaTracker after = reload(ticket.getId());
        assertThat(after.isPaused()).isTrue();
        assertThat(after.getPausedAt()).isEqualTo(before.getPausedAt());
    }

    @Test
    @DisplayName("H7 R4: a CLOSED ticket keeps resolvedAt, the MET verdict and its priority")
    void closedTicketKeepsItsSlaVerdict() {
        Incident ticket = assignedTicket();
        resolve(ticket);
        incidentService.verify(ticket.getId(), EMPLOYEE);
        incidentService.close(ticket.getId(), EMPLOYEE, new Feedback(5, "Thanks", Instant.now()));
        Incident closed = incidentRepository.findById(ticket.getId()).orElseThrow();
        Assumptions.assumeTrue(closed.getStatus() == IncidentStatus.CLOSED);
        SlaTracker before = closed.getSlaTracker();
        Assumptions.assumeTrue(before.getStatus() == SlaStatus.MET);

        incidentService.reclassify(ticket.getId(), ADMIN_EMAIL, IncidentCategory.SECURITY,
                serviceOutsideTeamOf(ticket.getAssigneeId()).getId(), "Label correction");

        Incident after = incidentRepository.findById(ticket.getId()).orElseThrow();
        assertThat(after.getSlaTracker().getStatus()).isEqualTo(SlaStatus.MET);
        assertThat(after.getSlaTracker().getResolvedAt()).isEqualTo(before.getResolvedAt());
        assertThat(after.getSlaTracker().getResolutionDueAt()).isEqualTo(before.getResolutionDueAt());
        assertThat(after.getPriority()).isEqualTo(closed.getPriority());
        assertThat(after.getStatus()).isEqualTo(IncidentStatus.CLOSED);
    }

    @Test
    @DisplayName("H7 R5: an open ticket reclassified into another team is no longer held by the old team's technician")
    void assigneeIsRoutedToTheNewTeam() {
        Incident ticket = assignedTicket();
        String oldTech = ticket.getAssigneeId();
        incidentService.acknowledge(ticket.getId(), oldTech);
        ItService target = serviceOutsideTeamOf(oldTech);

        incidentService.reclassify(ticket.getId(), ADMIN_EMAIL, IncidentCategory.SECURITY, target.getId(), "Wrong domain");

        Incident after = incidentRepository.findById(ticket.getId()).orElseThrow();
        assertThat(after.getAssigneeId()).isNotEqualTo(oldTech);
        if (after.getAssigneeId() != null) {
            assertThat(teamOf(after.getAssigneeId())).isEqualTo(
                    supportTeamRepository.findByHandledCategory(IncidentCategory.SECURITY).get(0).getId());
        } else {
            assertThat(after.getStatus()).isEqualTo(IncidentStatus.CLASSIFIED);
        }
        Technician released = technicianRepository.findById(oldTech).orElseThrow();
        assertThat(released.getActiveIncidentIds()).doesNotContain(ticket.getId());
    }

    @Test
    @DisplayName("H7 R6 (guard): an untouched ticket still takes the recalculated priority and SLA on reclassify")
    void untouchedTicketBehavesAsBefore() {
        Incident ticket = assignedTicket();
        ItService target = serviceOutsideTeamOf(ticket.getAssigneeId());
        Instant createdAt = reload(ticket.getId()).getCreatedAt();

        incidentService.reclassify(ticket.getId(), ADMIN_EMAIL, IncidentCategory.SECURITY, target.getId(), "Wrong domain");

        Incident after = incidentRepository.findById(ticket.getId()).orElseThrow();
        assertThat(after.getCategory()).isEqualTo(IncidentCategory.SECURITY);
        assertThat(after.getServiceId()).isEqualTo(target.getId());
        assertThat(after.getPriorityDecision().isOverridden()).isFalse();
        assertThat(after.getSlaTracker().getCreatedAt()).isEqualTo(createdAt);
    }
}
