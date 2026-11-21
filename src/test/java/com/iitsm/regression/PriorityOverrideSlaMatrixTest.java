package com.iitsm.regression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.iitsm.application.service.EscalationService;
import com.iitsm.application.service.IncidentService;
import com.iitsm.application.service.SlaService;
import com.iitsm.domain.event.DomainEventPublisher;
import com.iitsm.domain.exception.IitsmException;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.Priority;
import com.iitsm.domain.model.SlaPolicy;
import com.iitsm.domain.model.SlaStatus;
import com.iitsm.domain.model.SlaTracker;
import com.iitsm.domain.repository.IncidentRepository;
import com.iitsm.domain.repository.SlaPolicyRepository;
import com.iitsm.domain.repository.UserRepository;
import com.iitsm.infrastructure.persistence.CompanySeeder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * H6, phase 1 battery: an admin priority override must give the ticket the deadlines of the new priority's
 * policy, for every priority pair, and the SLA scan must read those deadlines (not the old ones).
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("regression")
class PriorityOverrideSlaMatrixTest {

    private static final String EMPLOYEE = "employee@nimbusfinserve.com";
    private static final String ADMIN_EMAIL = "admin@nimbusfinserve.com";
    private static final String REASON = "Business impact is higher than first assessed";

    @Autowired private IncidentService incidentService;
    @Autowired private IncidentRepository incidentRepository;
    @Autowired private SlaPolicyRepository slaPolicyRepository;
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

    private Incident acknowledgedTicket() {
        Incident created = newTicket();
        Assumptions.assumeTrue(created.getAssigneeId() != null, "ticket must be auto-assigned");
        incidentService.acknowledge(created.getId(), created.getAssigneeId());
        return created;
    }

    private SlaPolicy policyFor(Priority priority) {
        return slaPolicyRepository.findDefaultByPriority(priority).orElseGet(() -> SlaPolicy.defaultFor(priority));
    }

    private SlaTracker reload(String id) {
        return incidentRepository.findById(id).orElseThrow().getSlaTracker();
    }

    private SlaService scanAt(Instant now) {
        return new SlaService(incidentRepository, mock(DomainEventPublisher.class),
                mock(EscalationService.class), Clock.fixed(now, ZoneOffset.UTC), org.springframework.transaction.support.TransactionOperations.withoutTransaction());
    }

    static Stream<Arguments> everyPriorityPair() {
        return Stream.of(Priority.values()).flatMap(from ->
                Stream.of(Priority.values()).map(to -> Arguments.of(from, to)));
    }

    @ParameterizedTest(name = "[{index}] override {0} -> {1} gives the {1} deadlines")
    @MethodSource("everyPriorityPair")
    void everyTransitionUsesTheTargetPolicy(Priority from, Priority to) {
        Incident created = newTicket();
        incidentService.overridePriority(created.getId(), ADMIN_EMAIL, from, REASON);

        incidentService.overridePriority(created.getId(), ADMIN_EMAIL, to, REASON);

        SlaTracker tracker = reload(created.getId());
        SlaPolicy policy = policyFor(to);
        assertThat(tracker.getResponseDueAt()).isEqualTo(tracker.getCreatedAt().plus(policy.getResponseTarget()));
        assertThat(tracker.getResolutionDueAt()).isEqualTo(tracker.getCreatedAt().plus(policy.getResolutionTarget()));
        assertThat(incidentRepository.findById(created.getId()).orElseThrow().getPriorityDecision().priority())
                .isEqualTo(to);
    }

    @ParameterizedTest(name = "[{index}] a ticket overridden to {0} is ON_TRACK at 50% of its resolution target")
    @EnumSource(Priority.class)
    void halfwayThroughTheWindowIsOnTrack(Priority target) {
        Incident created = acknowledgedTicket();
        incidentService.overridePriority(created.getId(), ADMIN_EMAIL, target, REASON);
        SlaTracker tracker = reload(created.getId());
        Instant at = tracker.getCreatedAt().plus(policyFor(target).getResolutionTarget().dividedBy(2));

        assertThat(scanAt(at).evaluateIncident(created.getId()).currentStatus()).isEqualTo(SlaStatus.ON_TRACK);
    }

    @ParameterizedTest(name = "[{index}] a ticket overridden to {0} is AT_RISK at 80% of its resolution target")
    @EnumSource(Priority.class)
    void eightyPercentIsAtRisk(Priority target) {
        Incident created = acknowledgedTicket();
        incidentService.overridePriority(created.getId(), ADMIN_EMAIL, target, REASON);
        SlaTracker tracker = reload(created.getId());
        Instant at = tracker.getCreatedAt().plus(policyFor(target).getResolutionTarget().multipliedBy(80).dividedBy(100));

        assertThat(scanAt(at).evaluateIncident(created.getId()).currentStatus()).isEqualTo(SlaStatus.AT_RISK);
    }

    @ParameterizedTest(name = "[{index}] a ticket overridden to {0} is WARNING at 95% of its resolution target")
    @EnumSource(Priority.class)
    void ninetyFivePercentIsWarning(Priority target) {
        Incident created = acknowledgedTicket();
        incidentService.overridePriority(created.getId(), ADMIN_EMAIL, target, REASON);
        SlaTracker tracker = reload(created.getId());
        Instant at = tracker.getCreatedAt().plus(policyFor(target).getResolutionTarget().multipliedBy(95).dividedBy(100));

        assertThat(scanAt(at).evaluateIncident(created.getId()).currentStatus()).isEqualTo(SlaStatus.WARNING);
    }

    @ParameterizedTest(name = "[{index}] a ticket overridden to {0} is BREACHED just after its resolution target")
    @EnumSource(Priority.class)
    void justAfterTheTargetIsBreached(Priority target) {
        Incident created = acknowledgedTicket();
        incidentService.overridePriority(created.getId(), ADMIN_EMAIL, target, REASON);
        SlaTracker tracker = reload(created.getId());
        Instant at = tracker.getCreatedAt().plus(policyFor(target).getResolutionTarget()).plusSeconds(1);

        assertThat(scanAt(at).evaluateIncident(created.getId()).currentStatus()).isEqualTo(SlaStatus.BREACHED);
    }

    @Test
    @DisplayName("H6: a ticket lowered from P1 to P4 is not breached at a time that only breaches the P1 target")
    void loweredFromP1IsNotBreachedEarly() {
        Incident created = acknowledgedTicket();
        incidentService.overridePriority(created.getId(), ADMIN_EMAIL, Priority.P1, REASON);
        incidentService.overridePriority(created.getId(), ADMIN_EMAIL, Priority.P4, REASON);
        Instant at = reload(created.getId()).getCreatedAt()
                .plus(policyFor(Priority.P1).getResolutionTarget()).plus(Duration.ofMinutes(15));

        assertThat(scanAt(at).evaluateIncident(created.getId()).currentStatus()).isEqualTo(SlaStatus.ON_TRACK);
    }

    @Test
    @DisplayName("H6: raising then lowering back restores the original deadlines exactly")
    void roundTripRestoresDeadlines() {
        Incident created = newTicket();
        Priority original = created.getPriorityDecision().priority();
        SlaTracker before = reload(created.getId());

        incidentService.overridePriority(created.getId(), ADMIN_EMAIL, Priority.P1, REASON);
        incidentService.overridePriority(created.getId(), ADMIN_EMAIL, original, REASON);

        SlaTracker after = reload(created.getId());
        assertThat(after.getResolutionDueAt()).isEqualTo(before.getResolutionDueAt());
        assertThat(after.getResponseDueAt()).isEqualTo(before.getResponseDueAt());
    }

    @Test
    @DisplayName("H6: overriding to the same priority twice is idempotent for the deadlines")
    void repeatedOverrideIsIdempotent() {
        Incident created = newTicket();
        incidentService.overridePriority(created.getId(), ADMIN_EMAIL, Priority.P2, REASON);
        Instant first = reload(created.getId()).getResolutionDueAt();

        incidentService.overridePriority(created.getId(), ADMIN_EMAIL, Priority.P2, REASON);

        assertThat(reload(created.getId()).getResolutionDueAt()).isEqualTo(first);
    }

    @Test
    @DisplayName("H6: the creation time of the tracker never moves, only the targets do")
    void createdAtIsStable() {
        Incident created = newTicket();
        Instant createdAt = reload(created.getId()).getCreatedAt();

        incidentService.overridePriority(created.getId(), ADMIN_EMAIL, Priority.P1, REASON);
        incidentService.overridePriority(created.getId(), ADMIN_EMAIL, Priority.P4, REASON);

        assertThat(reload(created.getId()).getCreatedAt()).isEqualTo(createdAt);
    }

    @Test
    @DisplayName("H6: the response deadline of an acknowledged ticket survives a later override")
    void recordedResponseIsKept() {
        Incident created = acknowledgedTicket();
        Instant respondedAt = reload(created.getId()).getRespondedAt();
        Assumptions.assumeTrue(respondedAt != null);

        incidentService.overridePriority(created.getId(), ADMIN_EMAIL, Priority.P1, REASON);

        assertThat(reload(created.getId()).getRespondedAt()).isEqualTo(respondedAt);
    }

    @Test
    @DisplayName("H6: a ticket in PENDING_INFO gets the new deadlines and stays paused")
    void pendingInfoTicketKeepsItsPause() {
        Incident created = acknowledgedTicket();
        incidentService.startInvestigation(created.getId(), created.getAssigneeId());
        incidentService.requestInfo(created.getId(), created.getAssigneeId(), "Which office are you in?");
        Assumptions.assumeTrue(reload(created.getId()).isPaused());

        incidentService.overridePriority(created.getId(), ADMIN_EMAIL, Priority.P1, REASON);

        SlaTracker tracker = reload(created.getId());
        assertThat(tracker.isPaused()).isTrue();
        assertThat(tracker.getResolutionDueAt())
                .isEqualTo(tracker.getCreatedAt().plus(policyFor(Priority.P1).getResolutionTarget())
                        .plus(tracker.getPausedDuration()));
    }

    @Test
    @DisplayName("H6: a blank reason is rejected and the deadlines stay as they were")
    void blankReasonChangesNothing() {
        Incident created = newTicket();
        SlaTracker before = reload(created.getId());

        assertThatThrownBy(() -> incidentService.overridePriority(created.getId(), ADMIN_EMAIL, Priority.P1, "  "))
                .isInstanceOf(IitsmException.class);

        SlaTracker after = reload(created.getId());
        assertThat(after.getResolutionDueAt()).isEqualTo(before.getResolutionDueAt());
        assertThat(incidentRepository.findById(created.getId()).orElseThrow().getPriorityDecision().isOverridden())
                .isFalse();
    }

    @Test
    @DisplayName("H6: an employee cannot override, and the deadlines stay as they were")
    void employeeCannotOverride() {
        Incident created = newTicket();
        SlaTracker before = reload(created.getId());

        assertThatThrownBy(() -> incidentService.overridePriority(created.getId(), EMPLOYEE, Priority.P1, REASON))
                .isInstanceOf(IitsmException.class);

        assertThat(reload(created.getId()).getResolutionDueAt()).isEqualTo(before.getResolutionDueAt());
    }

    @Test
    @DisplayName("H6: a technician cannot override, and the deadlines stay as they were")
    void technicianCannotOverride() {
        Incident created = newTicket();
        Assumptions.assumeTrue(created.getAssigneeId() != null);
        SlaTracker before = reload(created.getId());

        assertThatThrownBy(() -> incidentService.overridePriority(
                created.getId(), created.getAssigneeId(), Priority.P1, REASON)).isInstanceOf(IitsmException.class);

        assertThat(reload(created.getId()).getResolutionDueAt()).isEqualTo(before.getResolutionDueAt());
    }

    @Test
    @DisplayName("H6: an unknown ticket is rejected")
    void unknownTicketIsRejected() {
        assertThatThrownBy(() -> incidentService.overridePriority("ZZZ-2026-999999", ADMIN_EMAIL, Priority.P1, REASON))
                .isInstanceOf(IitsmException.class);
    }

    @Test
    @DisplayName("H6: the staff display name (P1-...) is accepted as the ticket reference and moves the deadlines")
    void displayNameReferenceWorks() {
        Incident created = newTicket();
        String displayName = created.getPriorityDecision().priority().name() + "-" + created.getId();

        incidentService.overridePriority(displayName, ADMIN_EMAIL, Priority.P1, REASON);

        SlaTracker tracker = reload(created.getId());
        assertThat(tracker.getResolutionDueAt())
                .isEqualTo(tracker.getCreatedAt().plus(policyFor(Priority.P1).getResolutionTarget()));
    }
}
