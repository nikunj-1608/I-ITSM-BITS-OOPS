package com.iitsm.regression;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitsm.application.service.IncidentService;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.Priority;
import com.iitsm.domain.model.Resolution;
import com.iitsm.domain.model.SlaPolicy;
import com.iitsm.domain.model.SlaTracker;
import com.iitsm.domain.repository.IncidentRepository;
import com.iitsm.domain.repository.UserRepository;
import com.iitsm.infrastructure.config.AdjustableClock;
import com.iitsm.infrastructure.persistence.CompanySeeder;
import java.time.Clock;
import java.time.Duration;
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
 * H6 follow-up (ADR-024): the time a rejected fix waited for the employee's verdict is credited to the resolution
 * deadline (ADR-022). It must be stored, and a later priority override must keep it. Not @Transactional on
 * purpose: every call is its own database transaction, as in production.
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("regression")
class ReopenCreditPersistenceTest {

    private static final String EMPLOYEE = "employee@nimbusfinserve.com";
    private static final String ADMIN_EMAIL = "admin@nimbusfinserve.com";
    private static final Duration WAIT = Duration.ofMinutes(5);

    @Autowired private IncidentService incidentService;
    @Autowired private IncidentRepository incidentRepository;
    @Autowired private Clock clock;
    @Autowired private UserRepository userRepository;
    @Autowired private CompanySeeder companySeeder;
    @Autowired private JdbcTemplate jdbcTemplate;

    private AdjustableClock adjustable;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM technician_active_incidents");
        if (userRepository.count() == 0) {
            companySeeder.seedCompanyData();
        }
        Assumptions.assumeTrue(clock instanceof AdjustableClock);
        adjustable = (AdjustableClock) clock;
    }

    @AfterEach
    void tearDown() {
        if (adjustable != null) {
            adjustable.resetToRealTime();
        }
        jdbcTemplate.execute("DELETE FROM technician_active_incidents");
    }

    /** Ticket resolved, then the employee rejects the fix {@link #WAIT} later. */
    private Incident rejectedTicket() {
        Incident created = incidentService.createIncident(EMPLOYEE, "VPN gateway timeouts for the whole office",
                "AnyConnect drops every few minutes with heavy packet loss on the office gateway tunnel.");
        Assumptions.assumeTrue(created.getAssigneeId() != null, "ticket must be auto-assigned");
        String technician = created.getAssigneeId();
        incidentService.acknowledge(created.getId(), technician);
        incidentService.startInvestigation(created.getId(), technician);
        incidentService.resolve(created.getId(), technician,
                new Resolution("Root cause", "Steps", "Prevention", "SOFTWARE", technician, Instant.now()));
        adjustable.fastForward(WAIT);
        incidentService.reject(created.getId(), created.getReporterId(), "It still drops every few minutes");
        return created;
    }

    private SlaTracker reload(String id) {
        return incidentRepository.findById(id).orElseThrow().getSlaTracker();
    }

    @Test
    @DisplayName("the credit of a rejected fix is stored in whole seconds and read back")
    void creditIsPersisted() {
        Incident created = rejectedTicket();

        long stored = jdbcTemplate.queryForObject("SELECT credited_duration_seconds FROM sla_trackers "
                + "WHERE incident_id = ?", Long.class, created.getId());

        assertThat(stored).isBetween(WAIT.toSeconds() - 1, WAIT.toSeconds() + 5);
        assertThat(reload(created.getId()).getCreditedDuration().toSeconds()).isEqualTo(stored);
    }

    @Test
    @DisplayName("an override after a rejected fix keeps the credit in the new deadline")
    void overrideKeepsTheCredit() {
        Incident created = rejectedTicket();
        SlaTracker before = reload(created.getId());

        incidentService.overridePriority(created.getId(), ADMIN_EMAIL, Priority.P1, "Board escalation");

        SlaTracker after = reload(created.getId());
        assertThat(after.getResolutionDueAt()).isEqualTo(after.getCreatedAt()
                .plus(SlaPolicy.defaultFor(Priority.P1).getResolutionTarget())
                .plus(after.getPausedDuration())
                .plus(before.getCreditedDuration()));
        assertThat(after.getCreditedDuration()).isEqualTo(before.getCreditedDuration());
    }

    @Test
    @DisplayName("overriding twice does not add the credit twice")
    void creditIsNotDoubledByTwoOverrides() {
        Incident created = rejectedTicket();

        incidentService.overridePriority(created.getId(), ADMIN_EMAIL, Priority.P1, "Board escalation");
        Instant first = reload(created.getId()).getResolutionDueAt();
        incidentService.overridePriority(created.getId(), ADMIN_EMAIL, Priority.P1, "Confirmed");

        assertThat(reload(created.getId()).getResolutionDueAt()).isEqualTo(first);
    }

    @Test
    @DisplayName("a ticket that was never rejected has no credit")
    void noCreditWithoutRejection() {
        Incident created = incidentService.createIncident(EMPLOYEE, "VPN gateway timeouts for the whole office",
                "AnyConnect drops every few minutes with heavy packet loss on the office gateway tunnel.");

        assertThat(reload(created.getId()).getCreditedDuration()).isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("the credit is part of the deadline right after the rejection, before any override")
    void rejectionExtendsTheDeadlineByTheWait() {
        Incident created = rejectedTicket();
        SlaTracker tracker = reload(created.getId());

        SlaPolicy policy = SlaPolicy.defaultFor(created.getPriorityDecision().priority());

        assertThat(tracker.getCreditedDuration()).isPositive();
        // The credit is stored in whole seconds while the deadline keeps full precision: under 1 s apart.
        assertThat(tracker.getResolutionDueAt()).as("deadline = created + target + paused + credit")
                .isCloseTo(tracker.getCreatedAt().plus(policy.getResolutionTarget())
                        .plus(tracker.getPausedDuration()).plus(tracker.getCreditedDuration()),
                        org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.SECONDS));
    }
}
