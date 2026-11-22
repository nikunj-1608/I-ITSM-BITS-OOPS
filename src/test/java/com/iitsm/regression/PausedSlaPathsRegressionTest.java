package com.iitsm.regression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;

import com.iitsm.application.service.EscalationService;
import com.iitsm.application.service.IncidentService;
import com.iitsm.application.service.SlaEvaluationResult;
import com.iitsm.application.service.SlaService;
import com.iitsm.domain.event.DomainEventPublisher;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.IncidentStatus;
import com.iitsm.domain.model.ItService;
import com.iitsm.domain.model.Priority;
import com.iitsm.domain.model.SlaStatus;
import com.iitsm.domain.model.SlaTracker;
import com.iitsm.domain.repository.IncidentRepository;
import com.iitsm.domain.repository.ItServiceRepository;
import com.iitsm.domain.repository.TechnicianRepository;
import com.iitsm.domain.repository.UserRepository;
import com.iitsm.infrastructure.persistence.CompanySeeder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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
 * H4: every admin action that touches a ticket waiting for the employee (PENDING_INFO) must leave the SLA
 * clock paused, so the scheduled scan never breaches or escalates it while the employee is the blocker.
 * Each test drives the real services against the database and scans at a time far past the old deadline.
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("regression")
class PausedSlaPathsRegressionTest {

    private static final String EMPLOYEE = "employee@nimbusfinserve.com";
    private static final String ADMIN_EMAIL = "admin@nimbusfinserve.com";
    private static final Duration LONG_AFTER = Duration.ofDays(3);

    @Autowired private IncidentService incidentService;
    @Autowired private IncidentRepository incidentRepository;
    @Autowired private TechnicianRepository technicianRepository;
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

    private Incident pendingInfoTicket() {
        Incident created = incidentService.createIncident(EMPLOYEE, "VPN gateway timeouts for the whole office",
                "AnyConnect drops every few minutes with heavy packet loss on the office gateway tunnel.");
        Assumptions.assumeTrue(created.getAssigneeId() != null, "ticket must be auto-assigned");
        String tech = created.getAssigneeId();
        incidentService.acknowledge(created.getId(), tech);
        incidentService.startInvestigation(created.getId(), tech);
        incidentService.requestInfo(created.getId(), tech, "Which office is affected?");
        Incident pending = incidentRepository.findById(created.getId()).orElseThrow();
        Assumptions.assumeTrue(pending.getStatus() == IncidentStatus.PENDING_INFO);
        return pending;
    }

    private ItService serviceOutsideTeamOf(String technicianId) {
        String team = technicianRepository.findById(technicianId).orElseThrow().getSupportTeamId();
        return itServiceRepository.findAll().stream()
                .filter(s -> s.getOwningTeamId() != null && !s.getOwningTeamId().equals(team))
                .findFirst().orElseThrow();
    }

    /** Scans the ticket as if it were far in the future and returns the result; publishes nothing real. */
    private SlaEvaluationResult scanLongAfter(String id, DomainEventPublisher publisher) {
        Instant later = incidentRepository.findById(id).orElseThrow().getCreatedAt().plus(LONG_AFTER);
        SlaService scan = new SlaService(incidentRepository, publisher, mock(EscalationService.class),
                Clock.fixed(later, ZoneOffset.UTC), org.springframework.transaction.support.TransactionOperations.withoutTransaction());
        return scan.evaluateIncident(id);
    }

    private void assertStillPausedAndQuiet(String id) {
        DomainEventPublisher publisher = mock(DomainEventPublisher.class);

        SlaEvaluationResult result = scanLongAfter(id, publisher);

        assertThat(result.currentStatus()).isNotEqualTo(SlaStatus.BREACHED);
        assertThat(incidentRepository.findById(id).orElseThrow().getSlaTracker().isPaused()).isTrue();
        verify(publisher, never()).publish(any());
    }

    @Test
    @DisplayName("H4 P1: reclassifying a PENDING_INFO ticket into another team keeps it paused, assigned and quiet")
    void reclassifyKeepsAPendingInfoTicketPaused() {
        Incident ticket = pendingInfoTicket();
        SlaTracker before = ticket.getSlaTracker();
        ItService target = serviceOutsideTeamOf(ticket.getAssigneeId());

        incidentService.reclassify(ticket.getId(), ADMIN_EMAIL, IncidentCategory.SECURITY, target.getId(), "Wrong domain");

        Incident after = incidentRepository.findById(ticket.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(IncidentStatus.PENDING_INFO);
        assertThat(after.getAssigneeId()).isEqualTo(ticket.getAssigneeId());
        assertThat(after.getSlaTracker().getPausedAt()).isEqualTo(before.getPausedAt());
        assertThat(after.getSlaTracker().getRespondedAt()).isEqualTo(before.getRespondedAt());
        assertStillPausedAndQuiet(ticket.getId());
    }

    @Test
    @DisplayName("H4 P2: a priority override on a PENDING_INFO ticket keeps it paused and quiet, even to P1")
    void overrideKeepsAPendingInfoTicketPaused() {
        Incident ticket = pendingInfoTicket();
        Assumptions.assumeTrue(ticket.getPriority() != Priority.P1);

        incidentService.overridePriority(ticket.getId(), ADMIN_EMAIL, Priority.P1, "Business impact is higher");

        assertStillPausedAndQuiet(ticket.getId());
    }

    @Test
    @DisplayName("H4 P3: override then reclassify of a PENDING_INFO ticket keeps the override and the pause")
    void overrideThenReclassifyKeepsBoth() {
        Incident ticket = pendingInfoTicket();
        Assumptions.assumeTrue(ticket.getPriority() != Priority.P1);
        incidentService.overridePriority(ticket.getId(), ADMIN_EMAIL, Priority.P1, "Business impact is higher");
        ItService target = serviceOutsideTeamOf(ticket.getAssigneeId());

        incidentService.reclassify(ticket.getId(), ADMIN_EMAIL, IncidentCategory.SECURITY, target.getId(), "Wrong domain");

        Incident after = incidentRepository.findById(ticket.getId()).orElseThrow();
        assertThat(after.getPriority()).isEqualTo(Priority.P1);
        assertStillPausedAndQuiet(ticket.getId());
    }

    @Test
    @DisplayName("H4 P4: the employee's reply resumes the clock and credits the whole wait to the deadline")
    void replyResumesAndCreditsTheWait() {
        Incident ticket = pendingInfoTicket();
        Instant dueBefore = ticket.getSlaTracker().getResolutionDueAt();

        incidentService.provideInfo(ticket.getId(), EMPLOYEE, "The Pune office.");

        SlaTracker after = incidentRepository.findById(ticket.getId()).orElseThrow().getSlaTracker();
        assertThat(after.isPaused()).isFalse();
        // the paused duration is stored in whole seconds, the deadline at full precision: allow 1 s of drift
        assertThat(after.getResolutionDueAt()).isCloseTo(dueBefore.plus(after.getPausedDuration()),
                within(1, ChronoUnit.SECONDS));
        assertThat(after.getStatus()).isNotEqualTo(SlaStatus.BREACHED);
    }

    @Test
    @DisplayName("H4 P5: reclassifying an IN_PROGRESS ticket into another team keeps its recorded response and a running clock")
    void reclassifyInProgressKeepsTheRecordedResponse() {
        Incident created = incidentService.createIncident(EMPLOYEE, "VPN gateway timeouts for the whole office",
                "AnyConnect drops every few minutes with heavy packet loss on the office gateway tunnel.");
        Assumptions.assumeTrue(created.getAssigneeId() != null);
        incidentService.acknowledge(created.getId(), created.getAssigneeId());
        incidentService.startInvestigation(created.getId(), created.getAssigneeId());
        Instant responded = incidentRepository.findById(created.getId()).orElseThrow().getSlaTracker().getRespondedAt();
        ItService target = serviceOutsideTeamOf(created.getAssigneeId());

        incidentService.reclassify(created.getId(), ADMIN_EMAIL, IncidentCategory.SECURITY, target.getId(), "Wrong domain");

        Incident after = incidentRepository.findById(created.getId()).orElseThrow();
        assertThat(after.getSlaTracker().getRespondedAt()).isEqualTo(responded);
        assertThat(after.getSlaTracker().isPaused()).isFalse();
        assertThat(after.getAssigneeId()).isNotEqualTo(created.getAssigneeId());
    }
}
