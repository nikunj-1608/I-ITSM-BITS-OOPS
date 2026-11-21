package com.iitsm.regression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.iitsm.application.service.EscalationService;
import com.iitsm.application.service.IncidentService;
import com.iitsm.application.service.SlaEvaluationResult;
import com.iitsm.application.service.SlaService;
import com.iitsm.domain.event.DomainEventPublisher;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.Priority;
import com.iitsm.domain.model.Resolution;
import com.iitsm.domain.model.SlaPolicy;
import com.iitsm.domain.model.SlaStatus;
import com.iitsm.domain.model.SlaTracker;
import com.iitsm.domain.repository.AuditRepository;
import com.iitsm.domain.repository.IncidentRepository;
import com.iitsm.infrastructure.config.AdjustableClock;
import com.iitsm.domain.repository.SlaPolicyRepository;
import com.iitsm.domain.repository.UserRepository;
import com.iitsm.infrastructure.persistence.CompanySeeder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
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
 * H6 regression: an admin priority override must also move the SLA deadlines. Before the fix the
 * decision changed (and the staff display name with it) while the tracker kept the deadlines of the
 * original priority, so a ticket raised to P1 stayed ON_TRACK long after a P1's 30 minute target.
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("regression")
class PriorityOverrideSlaRegressionTest {

    private static final String EMPLOYEE = "employee@nimbusfinserve.com";
    private static final String ADMIN_EMAIL = "admin@nimbusfinserve.com";
    private static final String REASON = "Business impact is higher than first assessed";

    @Autowired private IncidentService incidentService;
    @Autowired private IncidentRepository incidentRepository;
    @Autowired private SlaPolicyRepository slaPolicyRepository;
    @Autowired private SlaService slaService;
    @Autowired private EscalationService escalationService;
    @Autowired private AuditRepository auditRepository;
    @Autowired private Clock clock;
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

    private Incident newTicket() {
        return incidentService.createIncident(EMPLOYEE, "VPN gateway timeouts for the whole office",
                "AnyConnect drops every few minutes with heavy packet loss on the office gateway tunnel.");
    }

    private SlaPolicy policyFor(Priority priority) {
        return slaPolicyRepository.findDefaultByPriority(priority).orElseGet(() -> SlaPolicy.defaultFor(priority));
    }

    private SlaTracker reload(String id) {
        return incidentRepository.findById(id).orElseThrow().getSlaTracker();
    }

    @Test
    @DisplayName("H6: raising a ticket to P1 gives it the P1 response and resolution deadlines")
    void raisingPriorityShortensDeadlines() {
        Incident created = newTicket();
        Assumptions.assumeTrue(created.getPriorityDecision().priority() != Priority.P1);
        SlaPolicy p1 = policyFor(Priority.P1);

        incidentService.overridePriority(created.getId(), ADMIN_EMAIL, Priority.P1, REASON);

        SlaTracker tracker = reload(created.getId());
        assertThat(tracker.getResolutionDueAt()).isEqualTo(tracker.getCreatedAt().plus(p1.getResolutionTarget()));
        assertThat(tracker.getResponseDueAt()).isEqualTo(tracker.getCreatedAt().plus(p1.getResponseTarget()));
    }

    @Test
    @DisplayName("H6: lowering a ticket to P4 gives it the longer P4 deadlines")
    void loweringPriorityLengthensDeadlines() {
        Incident created = newTicket();
        Assumptions.assumeTrue(created.getPriorityDecision().priority() != Priority.P4);
        SlaPolicy p4 = policyFor(Priority.P4);

        incidentService.overridePriority(created.getId(), ADMIN_EMAIL, Priority.P4, REASON);

        SlaTracker tracker = reload(created.getId());
        assertThat(tracker.getResolutionDueAt()).isEqualTo(tracker.getCreatedAt().plus(p4.getResolutionTarget()));
        assertThat(tracker.getResponseDueAt()).isEqualTo(tracker.getCreatedAt().plus(p4.getResponseTarget()));
    }

    @Test
    @DisplayName("H6: a ticket raised to P1 is breached by the SLA scan once its P1 target has passed")
    void raisedTicketBreachesAtTheP1Target() {
        Incident created = newTicket();
        Assumptions.assumeTrue(created.getPriorityDecision().priority() != Priority.P1);
        incidentService.overridePriority(created.getId(), ADMIN_EMAIL, Priority.P1, REASON);
        Instant afterP1Target = reload(created.getId()).getCreatedAt()
                .plus(policyFor(Priority.P1).getResolutionTarget()).plus(Duration.ofMinutes(15));
        SlaService scan = new SlaService(incidentRepository, mock(DomainEventPublisher.class),
                mock(EscalationService.class), Clock.fixed(afterP1Target, ZoneOffset.UTC), org.springframework.transaction.support.TransactionOperations.withoutTransaction());

        SlaEvaluationResult result = scan.evaluateIncident(created.getId());

        assertThat(result.currentStatus()).isEqualTo(SlaStatus.BREACHED);
    }

    @Test
    @DisplayName("H6: a lowered ticket is not breached by the scan at a time that only breaches the old target")
    void loweredTicketIsNotBreachedEarly() {
        Incident created = newTicket();
        incidentService.overridePriority(created.getId(), ADMIN_EMAIL, Priority.P1, REASON);
        incidentService.overridePriority(created.getId(), ADMIN_EMAIL, Priority.P4, REASON);
        Instant afterOldTarget = reload(created.getId()).getCreatedAt()
                .plus(policyFor(Priority.P1).getResolutionTarget()).plus(Duration.ofMinutes(15));
        SlaService scan = new SlaService(incidentRepository, mock(DomainEventPublisher.class),
                mock(EscalationService.class), Clock.fixed(afterOldTarget, ZoneOffset.UTC), org.springframework.transaction.support.TransactionOperations.withoutTransaction());

        SlaEvaluationResult result = scan.evaluateIncident(created.getId());

        assertThat(result.currentStatus()).isEqualTo(SlaStatus.ON_TRACK);
    }

    @Test
    @DisplayName("H6: an override that breaches the new target announces SlaViolated once and escalates on the scan")
    void overrideIntoBreachPublishesViolationAndEscalates() {
        Incident created = newTicket();
        Assumptions.assumeTrue(created.getPriorityDecision().priority() != Priority.P1);
        Assumptions.assumeTrue(clock instanceof AdjustableClock);
        AdjustableClock adjustable = (AdjustableClock) clock;
        adjustable.fastForward(Duration.ofHours(2));
        try {
            incidentService.overridePriority(created.getId(), ADMIN_EMAIL, Priority.P1, REASON);
            slaService.evaluateIncident(created.getId());
            slaService.evaluateIncident(created.getId());
        } finally {
            adjustable.resetToRealTime();
        }

        long violations = auditRepository.findByIncidentIdOrderByTimestampAsc(created.getId()).stream()
                .filter(record -> "SlaViolated".equals(record.getAction())).count();
        assertThat(violations).isEqualTo(1);
        assertThat(escalationService.getEscalationHistory(created.getId())).isNotEmpty();
    }

    @Test
    @DisplayName("H6, ADR-028: a resolved ticket keeps its deadlines and MET verdict because an override of it is refused")
    void resolvedTicketKeepsItsSlaVerdict() {
        Incident created = newTicket();
        String technician = created.getAssigneeId();
        Assumptions.assumeTrue(technician != null);
        incidentService.acknowledge(created.getId(), technician);
        incidentService.startInvestigation(created.getId(), technician);
        incidentService.resolve(created.getId(), technician,
                new Resolution("Root cause", "Steps", "Prevention", "SOFTWARE", technician, Instant.now()));
        SlaTracker before = reload(created.getId());
        Assumptions.assumeTrue(before.getStatus() == SlaStatus.MET);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> incidentService.overridePriority(created.getId(), ADMIN_EMAIL, Priority.P1, REASON))
                .isInstanceOf(com.iitsm.domain.exception.InvalidStateTransitionException.class);

        SlaTracker after = reload(created.getId());
        assertThat(after.getResolutionDueAt()).isEqualTo(before.getResolutionDueAt());
        assertThat(after.getResponseDueAt()).isEqualTo(before.getResponseDueAt());
        assertThat(after.getStatus()).isEqualTo(SlaStatus.MET);
    }
}
