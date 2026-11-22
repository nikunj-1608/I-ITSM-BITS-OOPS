package com.iitsm.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * H7, second battery: edge cases of {@link Incident#reclassify} beyond the five headline symptoms in
 * {@link IncidentReclassifyTest}. Each test states one rule of the contract.
 */
class IncidentReclassifyContractTest {

    private Instant now;

    @BeforeEach
    void setUp() {
        now = Instant.parse("2026-10-25T12:00:00Z");
    }

    private PriorityDecision decision(Priority priority) {
        return new PriorityDecision(priority, 0.40, Collections.emptyList(), Collections.emptyList(), now, null, null);
    }

    private Incident classifiedAs(Priority priority) {
        Incident incident = new Incident("INC-1", "Valid Title", "Valid description that is long enough.", "EMP-1", now);
        incident.classify(IncidentCategory.APPLICATION, "SVC-1", decision(priority), SlaPolicy.defaultFor(priority), now);
        return incident;
    }

    private void reclassify(Incident incident, Priority priority, Instant at) {
        incident.reclassify(IncidentCategory.SECURITY, "SVC-2", decision(priority), SlaPolicy.defaultFor(priority), at);
    }

    @Test
    @DisplayName("a null category is rejected and changes nothing")
    void nullCategoryIsRejected() {
        Incident incident = classifiedAs(Priority.P3);

        assertThatThrownBy(() -> incident.reclassify(null, "SVC-2", decision(Priority.P1), SlaPolicy.defaultFor(Priority.P1), now))
                .isInstanceOf(NullPointerException.class);

        assertThat(incident.getCategory()).isEqualTo(IncidentCategory.APPLICATION);
        assertThat(incident.getPriority()).isEqualTo(Priority.P3);
    }

    @Test
    @DisplayName("a null service id keeps the current service")
    void nullServiceKeepsCurrentService() {
        Incident incident = classifiedAs(Priority.P3);

        incident.reclassify(IncidentCategory.SECURITY, null, decision(Priority.P3), SlaPolicy.defaultFor(Priority.P3), now);

        assertThat(incident.getServiceId()).isEqualTo("SVC-1");
        assertThat(incident.getCategory()).isEqualTo(IncidentCategory.SECURITY);
    }

    @Test
    @DisplayName("a null decision and policy change the labels only")
    void nullDecisionAndPolicyChangeLabelsOnly() {
        Incident incident = classifiedAs(Priority.P3);
        Instant due = incident.getSlaTracker().getResolutionDueAt();

        incident.reclassify(IncidentCategory.SECURITY, "SVC-2", null, null, now);

        assertThat(incident.getPriority()).isEqualTo(Priority.P3);
        assertThat(incident.getSlaTracker().getResolutionDueAt()).isEqualTo(due);
        assertThat(incident.getServiceId()).isEqualTo("SVC-2");
    }

    @Test
    @DisplayName("a ticket without a tracker gets one anchored at its creation time")
    void missingTrackerIsCreatedFromCreationTime() {
        Incident incident = classifiedAs(Priority.P3);
        incident.setSlaTracker(null);

        reclassify(incident, Priority.P2, now.plus(Duration.ofMinutes(30)));

        assertThat(incident.getSlaTracker().getCreatedAt()).isEqualTo(now);
        assertThat(incident.getSlaTracker().getResolutionDueAt())
                .isEqualTo(now.plus(SlaPolicy.defaultFor(Priority.P2).getResolutionTarget()));
    }

    @Test
    @DisplayName("the tracker object is kept, not replaced")
    void trackerIdentityIsKept() {
        Incident incident = classifiedAs(Priority.P3);
        SlaTracker before = incident.getSlaTracker();

        reclassify(incident, Priority.P2, now);

        assertThat(incident.getSlaTracker()).isSameAs(before);
    }

    @Test
    @DisplayName("reclassifying twice with the same inputs gives the same deadlines")
    void reclassifyIsIdempotent() {
        Incident incident = classifiedAs(Priority.P4);

        reclassify(incident, Priority.P2, now);
        Instant resolutionOnce = incident.getSlaTracker().getResolutionDueAt();
        Instant responseOnce = incident.getSlaTracker().getResponseDueAt();
        reclassify(incident, Priority.P2, now);

        assertThat(incident.getSlaTracker().getResolutionDueAt()).isEqualTo(resolutionOnce);
        assertThat(incident.getSlaTracker().getResponseDueAt()).isEqualTo(responseOnce);
    }

    @Test
    @DisplayName("a paused ticket keeps its pause and the deadline moves by the time already paused")
    void pausedTicketKeepsPauseAndShiftsDeadline() {
        Incident incident = classifiedAs(Priority.P4);
        SlaTracker tracker = incident.getSlaTracker();
        tracker.recordResponse(now.plus(Duration.ofMinutes(2)));
        tracker.pauseClock(now.plus(Duration.ofMinutes(5)));
        tracker.resumeClock(now.plus(Duration.ofMinutes(25)));
        Instant secondPause = now.plus(Duration.ofMinutes(40));
        tracker.pauseClock(secondPause);

        reclassify(incident, Priority.P2, now.plus(Duration.ofMinutes(50)));

        assertThat(tracker.isPaused()).isTrue();
        assertThat(tracker.getPausedAt()).isEqualTo(secondPause);
        assertThat(tracker.getResolutionDueAt()).isEqualTo(
                now.plus(SlaPolicy.defaultFor(Priority.P2).getResolutionTarget()).plus(Duration.ofMinutes(20)));
        assertThat(tracker.getStatus()).isNotEqualTo(SlaStatus.BREACHED);
    }

    @Test
    @DisplayName("an override on a ticket that is also resolved is kept")
    void overrideOnResolvedTicketIsKept() {
        Incident incident = classifiedAs(Priority.P4);
        incident.overridePriority(Priority.P2, "ADMIN-1", "Outage", SlaPolicy.defaultFor(Priority.P2), now);
        // A resolved ticket always has a response (acknowledge comes first); without one the 15 minute P2 response
        // target would be missed and the verdict BREACHED (ADR-030).
        incident.getSlaTracker().recordResponse(now.plus(Duration.ofMinutes(2)));
        incident.getSlaTracker().recordResolution(now.plus(Duration.ofMinutes(20)));

        reclassify(incident, Priority.P1, now.plus(Duration.ofHours(1)));

        assertThat(incident.getPriority()).isEqualTo(Priority.P2);
        assertThat(incident.getPriorityDecision().overriddenBy()).isEqualTo("ADMIN-1");
        assertThat(incident.getSlaTracker().getStatus()).isEqualTo(SlaStatus.MET);
    }

    @Test
    @DisplayName("a breached resolved ticket keeps its BREACHED verdict")
    void breachedVerdictIsKept() {
        Incident incident = classifiedAs(Priority.P1);
        incident.getSlaTracker().recordResolution(now.plus(Duration.ofHours(3)));
        assertThat(incident.getSlaTracker().getStatus()).isEqualTo(SlaStatus.BREACHED);

        reclassify(incident, Priority.P4, now.plus(Duration.ofHours(4)));

        assertThat(incident.getSlaTracker().getStatus()).isEqualTo(SlaStatus.BREACHED);
        assertThat(incident.getPriority()).isEqualTo(Priority.P1);
    }

    @Test
    @DisplayName("a ticket whose resolution was rejected is open again and adopts the new decision")
    void reopenedTicketAdoptsTheNewDecision() {
        Incident incident = classifiedAs(Priority.P4);
        incident.getSlaTracker().recordResolution(now.plus(Duration.ofMinutes(10)));
        incident.getSlaTracker().reopen(now.plus(Duration.ofMinutes(20)));

        reclassify(incident, Priority.P2, now.plus(Duration.ofMinutes(30)));

        assertThat(incident.isPrioritySettled()).isFalse();
        assertThat(incident.getPriority()).isEqualTo(Priority.P2);
    }

    @Test
    @DisplayName("an open breached ticket moved to a longer policy is re-evaluated, not left breached by the old target")
    void openBreachedTicketIsReEvaluated() {
        Incident incident = classifiedAs(Priority.P1);
        Instant late = now.plus(Duration.ofHours(1));
        incident.updateSlaStatus(late);
        assertThat(incident.getSlaTracker().getStatus()).isEqualTo(SlaStatus.BREACHED);

        reclassify(incident, Priority.P4, late);

        assertThat(incident.getSlaTracker().getStatus()).isNotEqualTo(SlaStatus.BREACHED);
    }

    @Test
    @DisplayName("reclassify leaves status, assignee, notes and links alone")
    void reclassifyLeavesLifecycleStateAlone() {
        Incident incident = classifiedAs(Priority.P3);
        incident.assign("TECH-1", now);
        incident.acknowledge("TECH-1", now);
        incident.linkIncident("INC-2");

        reclassify(incident, Priority.P2, now);

        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.ACKNOWLEDGED);
        assertThat(incident.getAssigneeId()).isEqualTo("TECH-1");
        assertThat(incident.getLinkedIncidentIds()).containsExactly("INC-2");
    }

    @Test
    @DisplayName("isPrioritySettled is false for a plain open ticket")
    void plainTicketIsNotSettled() {
        assertThat(classifiedAs(Priority.P3).isPrioritySettled()).isFalse();
    }

    @Test
    @DisplayName("isPrioritySettled is true after an override")
    void overriddenTicketIsSettled() {
        Incident incident = classifiedAs(Priority.P3);
        incident.overridePriority(Priority.P1, "ADMIN-1", "Outage", SlaPolicy.defaultFor(Priority.P1), now);

        assertThat(incident.isPrioritySettled()).isTrue();
    }

    @Test
    @DisplayName("isPrioritySettled is true once the ticket is resolved")
    void resolvedTicketIsSettled() {
        Incident incident = classifiedAs(Priority.P3);
        incident.getSlaTracker().recordResolution(now.plus(Duration.ofMinutes(5)));

        assertThat(incident.isPrioritySettled()).isTrue();
    }

    @ParameterizedTest(name = "reclassify into {0} stores the category")
    @EnumSource(IncidentCategory.class)
    @DisplayName("every category can be the target of a reclassify")
    void everyCategoryIsAccepted(IncidentCategory category) {
        Incident incident = classifiedAs(Priority.P3);

        incident.reclassify(category, "SVC-2", decision(Priority.P3), SlaPolicy.defaultFor(Priority.P3), now);

        assertThat(incident.getCategory()).isEqualTo(category);
        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.CLASSIFIED);
    }

    @ParameterizedTest(name = "an override to {0} is kept whatever the new decision says")
    @EnumSource(Priority.class)
    @DisplayName("an override survives a recalculation to any priority")
    void overrideSurvivesEveryRecalculation(Priority recalculated) {
        Incident incident = classifiedAs(Priority.P3);
        Priority overridden = recalculated == Priority.P1 ? Priority.P4 : Priority.P1;
        incident.overridePriority(overridden, "ADMIN-1", "Outage", SlaPolicy.defaultFor(overridden), now);

        reclassify(incident, recalculated, now);

        assertThat(incident.getPriority()).isEqualTo(overridden);
        assertThat(incident.getPriorityDecision().isOverridden()).isTrue();
        assertThat(incident.getSlaTracker().getResolutionDueAt())
                .isEqualTo(now.plus(SlaPolicy.defaultFor(overridden).getResolutionTarget()));
    }

    @ParameterizedTest(name = "an open ticket moves to {0}'s deadlines")
    @EnumSource(Priority.class)
    @DisplayName("an open ticket takes the deadlines of the recalculated priority")
    void openTicketTakesEveryNewPolicy(Priority recalculated) {
        Incident incident = classifiedAs(Priority.P3);

        reclassify(incident, recalculated, now);

        assertThat(incident.getPriority()).isEqualTo(recalculated);
        assertThat(incident.getSlaTracker().getResolutionDueAt())
                .isEqualTo(now.plus(SlaPolicy.defaultFor(recalculated).getResolutionTarget()));
        assertThat(incident.getSlaTracker().getResponseDueAt())
                .isEqualTo(now.plus(SlaPolicy.defaultFor(recalculated).getResponseTarget()));
    }

    @ParameterizedTest(name = "a resolved ticket keeps its priority when the recalculation says {0}")
    @EnumSource(Priority.class)
    @DisplayName("a resolved ticket ignores every recalculation")
    void resolvedTicketIgnoresEveryRecalculation(Priority recalculated) {
        Incident incident = classifiedAs(Priority.P3);
        Instant due = incident.getSlaTracker().getResolutionDueAt();
        incident.getSlaTracker().recordResolution(now.plus(Duration.ofMinutes(5)));

        reclassify(incident, recalculated, now.plus(Duration.ofDays(2)));

        assertThat(incident.getPriority()).isEqualTo(Priority.P3);
        assertThat(incident.getSlaTracker().getResolutionDueAt()).isEqualTo(due);
        assertThat(incident.getSlaTracker().getStatus()).isEqualTo(SlaStatus.MET);
    }
}
