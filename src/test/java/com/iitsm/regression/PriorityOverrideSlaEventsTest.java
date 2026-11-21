package com.iitsm.regression;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitsm.application.service.EscalationService;
import com.iitsm.application.service.IncidentService;
import com.iitsm.application.service.SlaService;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.Priority;
import com.iitsm.domain.model.SlaStatus;
import com.iitsm.domain.repository.AuditRepository;
import com.iitsm.domain.repository.IncidentRepository;
import com.iitsm.domain.repository.UserRepository;
import com.iitsm.infrastructure.config.AdjustableClock;
import com.iitsm.infrastructure.persistence.CompanySeeder;
import java.time.Clock;
import java.time.Duration;
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
 * H6, phase 3 integration: the events an override produces, counted in the persisted audit trail, with the
 * real SLA scan running after it. The clock is moved with {@link AdjustableClock} and always reset.
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("regression")
class PriorityOverrideSlaEventsTest {

    private static final String EMPLOYEE = "employee@nimbusfinserve.com";
    private static final String ADMIN_EMAIL = "admin@nimbusfinserve.com";
    private static final String REASON = "Business impact is higher than first assessed";

    @Autowired private IncidentService incidentService;
    @Autowired private IncidentRepository incidentRepository;
    @Autowired private SlaService slaService;
    @Autowired private EscalationService escalationService;
    @Autowired private AuditRepository auditRepository;
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

    private Incident acknowledgedTicket() {
        Incident created = incidentService.createIncident(EMPLOYEE, "VPN gateway timeouts for the whole office",
                "AnyConnect drops every few minutes with heavy packet loss on the office gateway tunnel.");
        Assumptions.assumeTrue(created.getAssigneeId() != null, "ticket must be auto-assigned");
        incidentService.acknowledge(created.getId(), created.getAssigneeId());
        return created;
    }

    private long auditCount(String ticketId, String action) {
        return auditRepository.findByIncidentIdOrderByTimestampAsc(ticketId).stream()
                .filter(record -> action.equals(record.getAction())).count();
    }

    private SlaStatus statusOf(String ticketId) {
        return incidentRepository.findById(ticketId).orElseThrow().getSlaTracker().getStatus();
    }

    @Test
    @DisplayName("H6: a second override to the same priority after the breach announces nothing more")
    void secondOverrideDoesNotRepeatTheViolation() {
        Incident created = acknowledgedTicket();
        adjustable.fastForward(Duration.ofHours(2));

        incidentService.overridePriority(created.getId(), ADMIN_EMAIL, Priority.P1, REASON);
        incidentService.overridePriority(created.getId(), ADMIN_EMAIL, Priority.P1, REASON);
        slaService.evaluateIncident(created.getId());

        assertThat(auditCount(created.getId(), "SlaViolated")).isEqualTo(1);
        assertThat(auditCount(created.getId(), "PriorityOverridden")).isEqualTo(2);
    }

    @Test
    @DisplayName("H6: lowering a breached ticket recovers its status and announces no new violation")
    void loweringRecoversWithoutAnotherViolation() {
        Incident created = acknowledgedTicket();
        adjustable.fastForward(Duration.ofHours(2));
        incidentService.overridePriority(created.getId(), ADMIN_EMAIL, Priority.P1, REASON);
        assertThat(statusOf(created.getId())).isEqualTo(SlaStatus.BREACHED);

        incidentService.overridePriority(created.getId(), ADMIN_EMAIL, Priority.P4, REASON);
        slaService.evaluateIncident(created.getId());

        assertThat(statusOf(created.getId())).isEqualTo(SlaStatus.ON_TRACK);
        assertThat(auditCount(created.getId(), "SlaViolated")).isEqualTo(1);
    }

    @Test
    @DisplayName("H6: raising a ticket to 80% of its new window announces one SlaWarning and no violation")
    void raiseIntoTheWarningBand() {
        Incident created = acknowledgedTicket();
        adjustable.fastForward(Duration.ofMinutes(24)); // 80% of the 30 minute P1 resolution target

        incidentService.overridePriority(created.getId(), ADMIN_EMAIL, Priority.P1, REASON);
        slaService.evaluateIncident(created.getId());

        assertThat(statusOf(created.getId())).isIn(SlaStatus.AT_RISK, SlaStatus.WARNING);
        assertThat(auditCount(created.getId(), "SlaWarning")).isEqualTo(1);
        assertThat(auditCount(created.getId(), "SlaViolated")).isZero();
    }

    @Test
    @DisplayName("H6: raising a fresh ticket inside its new window announces nothing")
    void raiseInsideTheWindowIsSilent() {
        Incident created = acknowledgedTicket();
        adjustable.fastForward(Duration.ofMinutes(3));

        incidentService.overridePriority(created.getId(), ADMIN_EMAIL, Priority.P1, REASON);
        slaService.evaluateIncident(created.getId());

        assertThat(statusOf(created.getId())).isEqualTo(SlaStatus.ON_TRACK);
        assertThat(auditCount(created.getId(), "SlaWarning")).isZero();
        assertThat(auditCount(created.getId(), "SlaViolated")).isZero();
    }

    @Test
    @DisplayName("H6: the scan after an override into breach escalates the ticket exactly once")
    void escalatesOnceAcrossRepeatedScans() {
        Incident created = acknowledgedTicket();
        adjustable.fastForward(Duration.ofHours(2));
        incidentService.overridePriority(created.getId(), ADMIN_EMAIL, Priority.P1, REASON);

        slaService.evaluateIncident(created.getId());
        slaService.evaluateIncident(created.getId());
        slaService.evaluateIncident(created.getId());

        assertThat(escalationService.getEscalationHistory(created.getId())).isNotEmpty();
        assertThat(auditCount(created.getId(), "SlaViolated")).isEqualTo(1);
    }

    @Test
    @DisplayName("H6: every override writes its own PriorityOverridden audit entry with the reason")
    void overrideIsAudited() {
        Incident created = acknowledgedTicket();

        incidentService.overridePriority(created.getId(), ADMIN_EMAIL, Priority.P2, "First reason");
        incidentService.overridePriority(created.getId(), ADMIN_EMAIL, Priority.P1, "Second reason");

        assertThat(auditRepository.findByIncidentIdOrderByTimestampAsc(created.getId()).stream()
                .filter(r -> "PriorityOverridden".equals(r.getAction()))
                .map(r -> r.getDescription()))
                .anyMatch(d -> d.contains("First reason"))
                .anyMatch(d -> d.contains("Second reason"));
    }

    @Test
    @DisplayName("H6, ADR-028: a ticket resolved on time stays MET and silent because an override of it is refused")
    void resolvedTicketStaysMetAndSilent() {
        Incident created = acknowledgedTicket();
        String technician = created.getAssigneeId();
        incidentService.startInvestigation(created.getId(), technician);
        incidentService.resolve(created.getId(), technician, new com.iitsm.domain.model.Resolution(
                "Root cause", "Steps", "Prevention", "SOFTWARE", technician, java.time.Instant.now()));
        adjustable.fastForward(Duration.ofHours(3));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> incidentService.overridePriority(created.getId(), ADMIN_EMAIL, Priority.P1, REASON))
                .isInstanceOf(com.iitsm.domain.exception.InvalidStateTransitionException.class);

        assertThat(statusOf(created.getId())).isEqualTo(SlaStatus.MET);
        assertThat(auditCount(created.getId(), "SlaViolated")).isZero();
        assertThat(auditCount(created.getId(), "PriorityOverridden")).as("a refused override is not recorded").isZero();
    }
}
