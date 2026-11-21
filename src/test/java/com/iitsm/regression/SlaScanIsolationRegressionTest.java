package com.iitsm.regression;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitsm.application.service.IncidentService;
import com.iitsm.application.service.SlaEvaluationResult;
import com.iitsm.application.service.SlaService;
import com.iitsm.domain.event.DomainEvent;
import com.iitsm.domain.event.DomainEventListener;
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
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Remaining-defects plan, Phase D (ADR-031, stress-audit item H10): the SLA scan used to be one transaction over every
 * active ticket, so one failing ticket rolled back the evaluation of all the others and the same failure repeated on
 * every scan. The failure is injected by a test-only listener that throws when a marked ticket is announced as
 * breached; events are published inside the ticket's own transaction, so the throw rolls that ticket back.
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("regression")
class SlaScanIsolationRegressionTest {

    private static final String EMPLOYEE = "employee@nimbusfinserve.com";
    private static final String ADMIN_EMAIL = "admin@nimbusfinserve.com";
    private static final String FAIL_MARKER = "zzfailsla";

    /** Throws for the SlaViolated of a ticket whose title carries the marker, while armed. */
    static class FailViolationOfMarkedTickets implements DomainEventListener {
        final AtomicBoolean armed = new AtomicBoolean(false);
        private final IncidentRepository incidentRepository;

        FailViolationOfMarkedTickets(IncidentRepository incidentRepository) {
            this.incidentRepository = incidentRepository;
        }

        @Override
        public void onEvent(DomainEvent event) {
            if (armed.get() && event instanceof DomainEvent.SlaViolated violated
                    && incidentRepository.findById(violated.incidentId())
                            .map(ticket -> ticket.getTitle().contains(FAIL_MARKER)).orElse(false)) {
                throw new IllegalStateException("injected failure for " + violated.incidentId());
            }
        }
    }

    @TestConfiguration
    static class FailureInjection {
        @Bean
        FailViolationOfMarkedTickets failViolationOfMarkedTickets(IncidentRepository incidentRepository) {
            return new FailViolationOfMarkedTickets(incidentRepository);
        }
    }

    @Autowired private IncidentService incidentService;
    @Autowired private IncidentRepository incidentRepository;
    @Autowired private SlaService slaService;
    @Autowired private AuditRepository auditRepository;
    @Autowired private Clock clock;
    @Autowired private UserRepository userRepository;
    @Autowired private CompanySeeder companySeeder;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private FailViolationOfMarkedTickets failure;

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
        failure.armed.set(false);
        if (adjustable != null) {
            adjustable.resetToRealTime();
        }
        jdbcTemplate.execute("DELETE FROM technician_active_incidents");
    }

    private Incident p1Ticket(String title, String description) {
        Incident created = incidentService.createIncident(EMPLOYEE, title, description);
        Assumptions.assumeTrue(created.getAssigneeId() != null, "ticket must be auto-assigned");
        incidentService.overridePriority(created.getId(), ADMIN_EMAIL, Priority.P1, "Isolation scenario");
        incidentService.acknowledge(created.getId(), created.getAssigneeId());
        return created;
    }

    private SlaStatus statusOf(String ticketId) {
        return incidentRepository.findById(ticketId).orElseThrow().getSlaTracker().getStatus();
    }

    private long violations(String ticketId) {
        return auditRepository.findByIncidentIdOrderByTimestampAsc(ticketId).stream()
                .filter(record -> "SlaViolated".equals(record.getAction())).count();
    }

    @Test
    @DisplayName("H10: one ticket that fails during the scan does not undo or block the breaches of the others")
    void oneFailingTicketDoesNotPoisonTheScan() {
        Incident vpn = p1Ticket("VPN gateway timeouts for the whole office",
                "AnyConnect drops every few minutes with heavy packet loss on the office gateway tunnel.");
        Incident marked = p1Ticket("Payroll database connection pool exhausted " + FAIL_MARKER,
                "The payroll database refuses new connections and queries time out, the application reports pool exhausted.");
        Incident cloud = p1Ticket("Kubernetes pod crash looping in the production cluster",
                "The kubernetes pod restarts every minute with OOMKilled and the container never becomes ready on the cluster.");
        adjustable.fastForward(Duration.ofHours(2));
        failure.armed.set(true);

        java.util.List<SlaEvaluationResult> results = slaService.evaluateAllActiveIncidents();

        assertThat(results).extracting(SlaEvaluationResult::incidentId)
                .contains(vpn.getId(), cloud.getId()).doesNotContain(marked.getId());
        assertThat(statusOf(vpn.getId())).isEqualTo(SlaStatus.BREACHED);
        assertThat(statusOf(cloud.getId())).isEqualTo(SlaStatus.BREACHED);
        assertThat(violations(vpn.getId())).isEqualTo(1);
        assertThat(violations(cloud.getId())).isEqualTo(1);
        assertThat(statusOf(marked.getId())).as("the failing ticket was rolled back as a whole")
                .isNotEqualTo(SlaStatus.BREACHED);
        assertThat(violations(marked.getId())).isZero();
    }

    @Test
    @DisplayName("H10: the failing ticket is evaluated again by the next scan and then breaches normally")
    void failingTicketRecoversOnTheNextScan() {
        Incident marked = p1Ticket("Payroll database connection pool exhausted " + FAIL_MARKER,
                "The payroll database refuses new connections and queries time out, the application reports pool exhausted.");
        adjustable.fastForward(Duration.ofHours(2));
        failure.armed.set(true);
        slaService.evaluateAllActiveIncidents();
        assertThat(statusOf(marked.getId())).isNotEqualTo(SlaStatus.BREACHED);

        failure.armed.set(false);
        slaService.evaluateAllActiveIncidents();

        assertThat(statusOf(marked.getId())).isEqualTo(SlaStatus.BREACHED);
        assertThat(violations(marked.getId())).isEqualTo(1);
    }
}
