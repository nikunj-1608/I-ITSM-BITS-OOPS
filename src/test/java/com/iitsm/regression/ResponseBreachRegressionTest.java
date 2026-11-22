package com.iitsm.regression;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitsm.application.service.IncidentService;
import com.iitsm.application.service.SlaService;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.Priority;
import com.iitsm.domain.model.Resolution;
import com.iitsm.domain.model.SlaStatus;
import com.iitsm.domain.repository.AuditRepository;
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
 * Remaining-defects plan, Phase D (ADR-030, stress-audit item M1): a response breach is remembered. Before the fix a
 * ticket the scan had marked BREACHED for a missing response went back to ON_TRACK when it was acknowledged late, a
 * late response with a timely resolution ended MET, and every violation was announced as RESOLUTION_TARGET.
 *
 * <p>The tickets are raised through the real intake and made P1 by an administrator (5 minute response, 30 minute
 * resolution), the clock is moved with {@link AdjustableClock} and always reset, and events are counted in the
 * persisted audit trail.
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("regression")
class ResponseBreachRegressionTest {

    private static final String EMPLOYEE = "employee@nimbusfinserve.com";
    private static final String ADMIN_EMAIL = "admin@nimbusfinserve.com";

    @Autowired private IncidentService incidentService;
    @Autowired private IncidentRepository incidentRepository;
    @Autowired private SlaService slaService;
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

    private Incident p1Ticket() {
        Incident created = incidentService.createIncident(EMPLOYEE, "VPN gateway timeouts for the whole office",
                "AnyConnect drops every few minutes with heavy packet loss on the office gateway tunnel.");
        Assumptions.assumeTrue(created.getAssigneeId() != null, "ticket must be auto-assigned");
        incidentService.overridePriority(created.getId(), ADMIN_EMAIL, Priority.P1, "Whole office is offline");
        return incidentRepository.findById(created.getId()).orElseThrow();
    }

    private SlaStatus statusOf(String ticketId) {
        return incidentRepository.findById(ticketId).orElseThrow().getSlaTracker().getStatus();
    }

    private long violations(String ticketId, String target) {
        return auditRepository.findByIncidentIdOrderByTimestampAsc(ticketId).stream()
                .filter(record -> "SlaViolated".equals(record.getAction()))
                .filter(record -> record.getDescription().contains(target))
                .count();
    }

    private long allViolations(String ticketId) {
        return violations(ticketId, "");
    }

    private static Resolution resolution(String technician) {
        return new Resolution("Stale tunnel", "Restarted the gateway", "Monitoring", "NETWORK", technician,
                Instant.now());
    }

    @Test
    @DisplayName("M1: a ticket breached for a missing response stays BREACHED when it is acknowledged late")
    void lateAcknowledgeAfterTheScan() {
        Incident ticket = p1Ticket();
        adjustable.fastForward(Duration.ofMinutes(10));
        slaService.evaluateIncident(ticket.getId());
        assertThat(statusOf(ticket.getId())).as("precondition").isEqualTo(SlaStatus.BREACHED);

        incidentService.acknowledge(ticket.getId(), ticket.getAssigneeId());
        slaService.evaluateIncident(ticket.getId());

        assertThat(statusOf(ticket.getId())).isEqualTo(SlaStatus.BREACHED);
        assertThat(violations(ticket.getId(), "RESPONSE_TARGET")).isEqualTo(1);
        assertThat(allViolations(ticket.getId())).as("announced once, not again by the acknowledge or the scan")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("M1: a late acknowledge that no scan saw first is announced at the acknowledge, once")
    void lateAcknowledgeWithoutAScan() {
        Incident ticket = p1Ticket();
        adjustable.fastForward(Duration.ofMinutes(10));

        incidentService.acknowledge(ticket.getId(), ticket.getAssigneeId());
        slaService.evaluateIncident(ticket.getId());
        slaService.evaluateIncident(ticket.getId());

        assertThat(statusOf(ticket.getId())).isEqualTo(SlaStatus.BREACHED);
        assertThat(violations(ticket.getId(), "RESPONSE_TARGET")).isEqualTo(1);
        assertThat(allViolations(ticket.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("M1: a late response followed by a timely resolution ends BREACHED, not MET")
    void lateResponseThenTimelyResolution() {
        Incident ticket = p1Ticket();
        adjustable.fastForward(Duration.ofMinutes(7));
        incidentService.acknowledge(ticket.getId(), ticket.getAssigneeId());
        incidentService.startInvestigation(ticket.getId(), ticket.getAssigneeId());

        incidentService.resolve(ticket.getId(), ticket.getAssigneeId(), resolution(ticket.getAssigneeId()));
        slaService.evaluateIncident(ticket.getId());

        assertThat(statusOf(ticket.getId())).isEqualTo(SlaStatus.BREACHED);
        assertThat(allViolations(ticket.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("Control: an on-time response and an on-time resolution are MET and announce nothing")
    void onTimeEverything() {
        Incident ticket = p1Ticket();
        adjustable.fastForward(Duration.ofMinutes(2));
        incidentService.acknowledge(ticket.getId(), ticket.getAssigneeId());
        incidentService.startInvestigation(ticket.getId(), ticket.getAssigneeId());
        adjustable.fastForward(Duration.ofMinutes(10));

        incidentService.resolve(ticket.getId(), ticket.getAssigneeId(), resolution(ticket.getAssigneeId()));
        slaService.evaluateIncident(ticket.getId());

        assertThat(statusOf(ticket.getId())).isEqualTo(SlaStatus.MET);
        assertThat(allViolations(ticket.getId())).isZero();
    }

    @Test
    @DisplayName("M1: a missed resolution target is still announced as RESOLUTION_TARGET")
    void resolutionBreachKeepsItsLabel() {
        Incident ticket = p1Ticket();
        incidentService.acknowledge(ticket.getId(), ticket.getAssigneeId());
        adjustable.fastForward(Duration.ofHours(2));

        slaService.evaluateIncident(ticket.getId());

        assertThat(violations(ticket.getId(), "RESOLUTION_TARGET")).isEqualTo(1);
        assertThat(violations(ticket.getId(), "RESPONSE_TARGET")).isZero();
    }

    @Test
    @DisplayName("M1: a reopened ticket that responded late is still BREACHED after the verdict is rejected")
    void reopenedLateTicketStaysBreached() {
        Incident ticket = p1Ticket();
        adjustable.fastForward(Duration.ofMinutes(7));
        incidentService.acknowledge(ticket.getId(), ticket.getAssigneeId());
        incidentService.startInvestigation(ticket.getId(), ticket.getAssigneeId());
        incidentService.resolve(ticket.getId(), ticket.getAssigneeId(), resolution(ticket.getAssigneeId()));

        incidentService.reject(ticket.getId(), EMPLOYEE, "Still broken");
        slaService.evaluateIncident(ticket.getId());

        assertThat(statusOf(ticket.getId())).isEqualTo(SlaStatus.BREACHED);
    }
}
