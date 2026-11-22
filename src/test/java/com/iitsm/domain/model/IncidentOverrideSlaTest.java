package com.iitsm.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.iitsm.domain.exception.ValidationException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * H6, phase 2 battery: {@code Incident.overridePriority} re-anchors the SLA tracker to the overridden
 * priority's policy and leaves history (resolved verdict, recorded response, paused time) alone.
 */
class IncidentOverrideSlaTest {

    private Instant t0;

    @BeforeEach
    void setUp() {
        t0 = Instant.parse("2026-11-03T10:00:00Z");
    }

    private Instant at(long minutes) {
        return t0.plus(Duration.ofMinutes(minutes));
    }

    private Incident classifiedAs(Priority priority) {
        Incident incident = new Incident("INC-1", "Valid Title", "Valid description that is long enough.", "EMP-1", t0);
        PriorityDecision decision =
                new PriorityDecision(priority, 0.40, Collections.emptyList(), Collections.emptyList(), t0, null, null);
        incident.classify(IncidentCategory.SOFTWARE, "SVC-1", decision, SlaPolicy.defaultFor(priority), t0);
        return incident;
    }

    private static void override(Incident incident, Priority to, Instant now) {
        incident.overridePriority(to, "ADMIN-1", "Business impact", SlaPolicy.defaultFor(to), now);
    }

    static Stream<Arguments> everyPriorityPair() {
        return Stream.of(Priority.values()).flatMap(from ->
                Stream.of(Priority.values()).map(to -> Arguments.of(from, to)));
    }

    @ParameterizedTest(name = "[{index}] {0} -> {1}: deadlines come from the {1} policy, measured from creation")
    @MethodSource("everyPriorityPair")
    void deadlinesFollowTheNewPolicy(Priority from, Priority to) {
        Incident incident = classifiedAs(from);

        override(incident, to, at(1));

        SlaPolicy policy = SlaPolicy.defaultFor(to);
        assertThat(incident.getSlaTracker().getResponseDueAt()).isEqualTo(t0.plus(policy.getResponseTarget()));
        assertThat(incident.getSlaTracker().getResolutionDueAt()).isEqualTo(t0.plus(policy.getResolutionTarget()));
        assertThat(incident.getPriorityDecision().priority()).isEqualTo(to);
    }

    @ParameterizedTest(name = "[{index}] an override to {0} marks the decision as overridden with admin and reason")
    @EnumSource(Priority.class)
    void decisionRecordsTheOverride(Priority to) {
        Incident incident = classifiedAs(Priority.P3);

        override(incident, to, at(1));

        PriorityDecision decision = incident.getPriorityDecision();
        assertThat(decision.isOverridden()).isTrue();
        assertThat(decision.overriddenBy()).isEqualTo("ADMIN-1");
        assertThat(decision.overrideReason()).isEqualTo("Business impact");
    }

    @ParameterizedTest(name = "[{index}] a mismatched policy for target {0} is rejected and nothing changes")
    @EnumSource(Priority.class)
    void mismatchedPolicyIsRejectedAtomically(Priority to) {
        Incident incident = classifiedAs(Priority.P3);
        Priority wrong = to == Priority.P1 ? Priority.P2 : Priority.P1;
        Instant due = incident.getSlaTracker().getResolutionDueAt();

        assertThatThrownBy(() -> incident.overridePriority(to, "ADMIN-1", "Reason", SlaPolicy.defaultFor(wrong), at(1)))
                .isInstanceOf(ValidationException.class);

        assertThat(incident.getPriorityDecision().priority()).isEqualTo(Priority.P3);
        assertThat(incident.getPriorityDecision().isOverridden()).isFalse();
        assertThat(incident.getSlaTracker().getResolutionDueAt()).isEqualTo(due);
    }

    @ParameterizedTest(name = "[{index}] reason \"{0}\" is rejected and nothing changes")
    @org.junit.jupiter.params.provider.NullSource
    @org.junit.jupiter.params.provider.ValueSource(strings = {"", " ", "\t", "\n  "})
    void invalidReasonIsRejectedAtomically(String reason) {
        Incident incident = classifiedAs(Priority.P4);
        Instant due = incident.getSlaTracker().getResolutionDueAt();

        assertThatThrownBy(() -> incident.overridePriority(
                Priority.P1, "ADMIN-1", reason, SlaPolicy.defaultFor(Priority.P1), at(1)))
                .isInstanceOf(ValidationException.class);

        assertThat(incident.getPriorityDecision().priority()).isEqualTo(Priority.P4);
        assertThat(incident.getSlaTracker().getResolutionDueAt()).isEqualTo(due);
    }

    @Test
    @DisplayName("an unclassified ticket cannot be overridden")
    void unclassifiedTicketIsRejected() {
        Incident incident = new Incident("INC-2", "Valid Title", "Valid description that is long enough.", "EMP-1", t0);

        assertThatThrownBy(() -> incident.overridePriority(
                Priority.P1, "ADMIN-1", "Reason", SlaPolicy.defaultFor(Priority.P1), at(1)))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("a null policy changes the decision only")
    void nullPolicyChangesDecisionOnly() {
        Incident incident = classifiedAs(Priority.P4);
        Instant due = incident.getSlaTracker().getResolutionDueAt();

        incident.overridePriority(Priority.P1, "ADMIN-1", "Reason", null, at(1));

        assertThat(incident.getPriorityDecision().priority()).isEqualTo(Priority.P1);
        assertThat(incident.getSlaTracker().getResolutionDueAt()).isEqualTo(due);
    }

    // ---- status after the override -------------------------------------------------------------------

    @Test
    @DisplayName("raising after the new target has passed is BREACHED at once")
    void raisedPastTheNewTargetIsBreached() {
        Incident incident = classifiedAs(Priority.P4);

        override(incident, Priority.P1, at(120));

        assertThat(incident.getSlaTracker().getStatus()).isEqualTo(SlaStatus.BREACHED);
    }

    @Test
    @DisplayName("raising after only the response target has passed is BREACHED (no response recorded)")
    void raisedPastTheResponseTargetIsBreached() {
        Incident incident = classifiedAs(Priority.P4);

        override(incident, Priority.P1, at(10));

        assertThat(incident.getSlaTracker().getStatus()).isEqualTo(SlaStatus.BREACHED);
    }

    @Test
    @DisplayName("raising after a recorded response only breaches once the resolution target has passed")
    void respondedTicketIgnoresResponseTarget() {
        Incident incident = classifiedAs(Priority.P4);
        incident.getSlaTracker().recordResponse(at(2));

        override(incident, Priority.P1, at(10));

        assertThat(incident.getSlaTracker().getStatus()).isEqualTo(SlaStatus.ON_TRACK);
    }

    @Test
    @DisplayName("lowering a breached P1 ticket gives it back the longer window and clears the breach")
    void loweredBreachedTicketRecovers() {
        Incident incident = classifiedAs(Priority.P1);
        incident.getSlaTracker().recordResponse(at(1));
        incident.updateSlaStatus(at(45));
        assertThat(incident.getSlaTracker().getStatus()).isEqualTo(SlaStatus.BREACHED);

        override(incident, Priority.P4, at(46));

        assertThat(incident.getSlaTracker().getStatus()).isEqualTo(SlaStatus.ON_TRACK);
    }

    @ParameterizedTest(name = "[{index}] at {0}% of the P1 window the ticket is {1}")
    @MethodSource("p1WindowPoints")
    void statusAtPointsOfTheNewWindow(int percent, SlaStatus expected) {
        Incident incident = classifiedAs(Priority.P4);
        incident.getSlaTracker().recordResponse(at(1));
        Instant when = t0.plusSeconds(1800L * percent / 100);

        override(incident, Priority.P1, when);

        assertThat(incident.getSlaTracker().getStatus()).isEqualTo(expected);
    }

    static Stream<Arguments> p1WindowPoints() {
        return Stream.of(
                Arguments.of(10, SlaStatus.ON_TRACK),
                Arguments.of(50, SlaStatus.ON_TRACK),
                Arguments.of(74, SlaStatus.ON_TRACK),
                Arguments.of(75, SlaStatus.AT_RISK),
                Arguments.of(89, SlaStatus.AT_RISK),
                Arguments.of(90, SlaStatus.WARNING),
                Arguments.of(99, SlaStatus.WARNING),
                Arguments.of(101, SlaStatus.BREACHED),
                Arguments.of(400, SlaStatus.BREACHED));
    }

    @Test
    @DisplayName("exactly at the deadline is not yet breached (breach is strictly after)")
    void exactlyAtDeadlineIsNotBreached() {
        Incident incident = classifiedAs(Priority.P4);
        incident.getSlaTracker().recordResponse(at(1));

        override(incident, Priority.P1, at(30));

        assertThat(incident.getSlaTracker().getStatus()).isNotEqualTo(SlaStatus.BREACHED);
    }

    // ---- history that must survive ---------------------------------------------------------------------

    @Test
    @DisplayName("a recorded response time is not touched by an override")
    void respondedAtIsKept() {
        Incident incident = classifiedAs(Priority.P4);
        incident.getSlaTracker().recordResponse(at(3));

        override(incident, Priority.P1, at(4));

        assertThat(incident.getSlaTracker().getRespondedAt()).isEqualTo(at(3));
    }

    @Test
    @DisplayName("time already spent paused is added to the new deadlines")
    void completedPauseIsKept() {
        Incident incident = classifiedAs(Priority.P4);
        incident.getSlaTracker().pauseClock(at(5));
        incident.getSlaTracker().resumeClock(at(25));

        override(incident, Priority.P1, at(26));

        assertThat(incident.getSlaTracker().getResolutionDueAt()).isEqualTo(at(30 + 20));
        assertThat(incident.getSlaTracker().getPausedDuration()).isEqualTo(Duration.ofMinutes(20));
    }

    @Test
    @DisplayName("a currently paused ticket stays paused and is judged at its pause instant")
    void ongoingPauseIsKept() {
        Incident incident = classifiedAs(Priority.P4);
        incident.getSlaTracker().recordResponse(at(1));
        incident.getSlaTracker().pauseClock(at(10));

        override(incident, Priority.P1, at(500));

        assertThat(incident.getSlaTracker().isPaused()).isTrue();
        assertThat(incident.getSlaTracker().getStatus()).isNotEqualTo(SlaStatus.BREACHED);
    }

    @Test
    @DisplayName("after an override of a paused ticket, resuming adds the waiting time to the new deadline")
    void resumeAfterOverrideAddsTheWait() {
        Incident incident = classifiedAs(Priority.P4);
        incident.getSlaTracker().recordResponse(at(1));
        incident.getSlaTracker().pauseClock(at(10));
        override(incident, Priority.P1, at(20));

        incident.getSlaTracker().resumeClock(at(40));

        assertThat(incident.getSlaTracker().getResolutionDueAt()).isEqualTo(at(30 + 30));
    }

    @Test
    @DisplayName("a resolved ticket keeps its deadlines and MET verdict whatever it is overridden to")
    void resolvedVerdictIsFrozen() {
        Incident incident = classifiedAs(Priority.P4);
        SlaTracker tracker = incident.getSlaTracker();
        Instant due = tracker.getResolutionDueAt();
        Instant responseDue = tracker.getResponseDueAt();
        tracker.recordResponse(at(1));
        tracker.recordResolution(at(10));

        override(incident, Priority.P1, at(600));

        assertThat(tracker.getResolutionDueAt()).isEqualTo(due);
        assertThat(tracker.getResponseDueAt()).isEqualTo(responseDue);
        assertThat(tracker.getStatus()).isEqualTo(SlaStatus.MET);
        assertThat(incident.getPriorityDecision().priority()).isEqualTo(Priority.P1);
    }

    @Test
    @DisplayName("a resolved ticket that was late keeps its BREACHED verdict when lowered")
    void resolvedLateStaysBreached() {
        Incident incident = classifiedAs(Priority.P1);
        incident.getSlaTracker().recordResponse(at(1));
        incident.getSlaTracker().recordResolution(at(90));
        assertThat(incident.getSlaTracker().getStatus()).isEqualTo(SlaStatus.BREACHED);

        override(incident, Priority.P4, at(91));

        assertThat(incident.getSlaTracker().getStatus()).isEqualTo(SlaStatus.BREACHED);
    }

    @Test
    @DisplayName("a rejected (reopened) ticket is open again, so an override moves its deadlines")
    void reopenedTicketIsOverridable() {
        Incident incident = classifiedAs(Priority.P4);
        SlaTracker tracker = incident.getSlaTracker();
        tracker.recordResponse(at(1));
        tracker.recordResolution(at(10));
        tracker.reopen(at(20));

        override(incident, Priority.P1, at(21));

        assertThat(tracker.getResolvedAt()).isNull();
        assertThat(tracker.getResolutionDueAt()).isNotEqualTo(t0.plus(Duration.ofHours(24)));
    }

    @Test
    @DisplayName("the credit a reopened ticket got for waiting on the verdict survives an override")
    void reopenCreditIsCarriedIntoAnOverride() {
        Incident incident = classifiedAs(Priority.P4);
        SlaTracker tracker = incident.getSlaTracker();
        tracker.recordResponse(at(1));
        tracker.recordResolution(at(10));
        tracker.reopen(at(20)); // 10 minutes of credit on the resolution deadline

        override(incident, Priority.P1, at(21));

        assertThat(tracker.getResolutionDueAt()).isEqualTo(at(30 + 10));
        assertThat(tracker.getCreditedDuration()).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("credit never touches the response deadline")
    void creditDoesNotMoveTheResponseDeadline() {
        Incident incident = classifiedAs(Priority.P4);
        SlaTracker tracker = incident.getSlaTracker();
        tracker.recordResolution(at(10));
        tracker.reopen(at(20));

        override(incident, Priority.P1, at(21));

        assertThat(tracker.getResponseDueAt()).isEqualTo(at(5));
    }

    @Test
    @DisplayName("credit from two rejected fixes adds up and survives an override")
    void creditAccumulatesAcrossReopens() {
        Incident incident = classifiedAs(Priority.P4);
        SlaTracker tracker = incident.getSlaTracker();
        tracker.recordResponse(at(1));
        tracker.recordResolution(at(10));
        tracker.reopen(at(15)); // 5 minutes
        tracker.recordResolution(at(20));
        tracker.reopen(at(28)); // 8 minutes

        override(incident, Priority.P2, at(29));

        assertThat(tracker.getCreditedDuration()).isEqualTo(Duration.ofMinutes(13));
        assertThat(tracker.getResolutionDueAt()).isEqualTo(at(120 + 13));
    }

    @Test
    @DisplayName("credit, paused time and an override together: created + target + paused + credit")
    void creditAndPauseCombine() {
        Incident incident = classifiedAs(Priority.P4);
        SlaTracker tracker = incident.getSlaTracker();
        tracker.recordResponse(at(1));
        tracker.pauseClock(at(2));
        tracker.resumeClock(at(7)); // 5 minutes paused
        tracker.recordResolution(at(10));
        tracker.reopen(at(14)); // 4 minutes credit

        override(incident, Priority.P1, at(15));

        assertThat(tracker.getResolutionDueAt()).isEqualTo(at(30 + 5 + 4));
    }

    @Test
    @DisplayName("overriding twice after a reopen keeps the credit once, not twice")
    void creditIsNotCountedTwice() {
        Incident incident = classifiedAs(Priority.P4);
        SlaTracker tracker = incident.getSlaTracker();
        tracker.recordResolution(at(10));
        tracker.reopen(at(20));

        override(incident, Priority.P1, at(21));
        override(incident, Priority.P2, at(22));
        override(incident, Priority.P1, at(23));

        assertThat(tracker.getResolutionDueAt()).isEqualTo(at(30 + 10));
    }

    @Test
    @DisplayName("a ticket that was never reopened has no credit")
    void noCreditWithoutReopen() {
        Incident incident = classifiedAs(Priority.P4);

        override(incident, Priority.P1, at(5));

        assertThat(incident.getSlaTracker().getCreditedDuration()).isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("reopening an unresolved tracker earns no credit")
    void reopenWithoutResolutionIsNoCredit() {
        SlaTracker tracker = SlaTracker.create(t0, SlaPolicy.defaultFor(Priority.P3));

        tracker.reopen(at(30));

        assertThat(tracker.getCreditedDuration()).isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("reconstitute keeps the credit, and the old signature means no credit")
    void reconstituteKeepsCredit() {
        SlaTracker withCredit = SlaTracker.reconstitute(t0, at(5), at(40), null, null, Duration.ZERO,
                Duration.ofMinutes(10), null, SlaStatus.ON_TRACK);
        SlaTracker withoutCredit = SlaTracker.reconstitute(t0, at(5), at(30), null, null, Duration.ZERO, null,
                SlaStatus.ON_TRACK);

        withCredit.reanchor(SlaPolicy.defaultFor(Priority.P1), at(1));
        withoutCredit.reanchor(SlaPolicy.defaultFor(Priority.P1), at(1));

        assertThat(withCredit.getResolutionDueAt()).isEqualTo(at(40));
        assertThat(withoutCredit.getResolutionDueAt()).isEqualTo(at(30));
        assertThat(withoutCredit.getCreditedDuration()).isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("a null credit in reconstitute is treated as none")
    void nullCreditMeansNone() {
        SlaTracker tracker = SlaTracker.reconstitute(t0, at(5), at(30), null, null, Duration.ZERO, null, null,
                SlaStatus.ON_TRACK);

        assertThat(tracker.getCreditedDuration()).isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("a reclassify (same re-anchor) keeps the credit as well")
    void reclassifyKeepsCredit() {
        Incident incident = classifiedAs(Priority.P4);
        SlaTracker tracker = incident.getSlaTracker();
        tracker.recordResolution(at(10));
        tracker.reopen(at(20));

        tracker.reanchor(SlaPolicy.defaultFor(Priority.P3), at(21));

        assertThat(tracker.getResolutionDueAt()).isEqualTo(at(480 + 10));
    }

    // ---- repeated and sequential overrides ---------------------------------------------------------------

    @Test
    @DisplayName("overriding twice to the same priority leaves identical deadlines")
    void idempotentForSamePriority() {
        Incident incident = classifiedAs(Priority.P4);

        override(incident, Priority.P2, at(1));
        Instant first = incident.getSlaTracker().getResolutionDueAt();
        override(incident, Priority.P2, at(2));

        assertThat(incident.getSlaTracker().getResolutionDueAt()).isEqualTo(first);
    }

    @Test
    @DisplayName("a chain of overrides ends with the last priority's deadlines")
    void chainEndsWithLastPolicy() {
        Incident incident = classifiedAs(Priority.P3);

        override(incident, Priority.P1, at(1));
        override(incident, Priority.P4, at(2));
        override(incident, Priority.P2, at(3));

        assertThat(incident.getSlaTracker().getResolutionDueAt())
                .isEqualTo(t0.plus(SlaPolicy.defaultFor(Priority.P2).getResolutionTarget()));
    }

    @Test
    @DisplayName("the override keeps the original factor breakdown and records the new calculation time")
    void overrideKeepsFactorList() {
        Incident incident = classifiedAs(Priority.P3);
        int factors = incident.getPriorityDecision().factorContributions().size();

        override(incident, Priority.P1, at(7));

        assertThat(incident.getPriorityDecision().factorContributions()).hasSize(factors);
        assertThat(incident.getPriorityDecision().calculatedAt()).isEqualTo(at(7));
    }

    @Test
    @DisplayName("createdAt of the tracker never changes")
    void createdAtNeverMoves() {
        Incident incident = classifiedAs(Priority.P3);

        override(incident, Priority.P1, at(5));
        override(incident, Priority.P4, at(6));

        assertThat(incident.getSlaTracker().getCreatedAt()).isEqualTo(t0);
    }

    // ---- SlaTracker.reanchor directly ----------------------------------------------------------------------

    @Test
    @DisplayName("reanchor on an unresolved tracker equals updatePolicy")
    void reanchorMatchesUpdatePolicyWhenOpen() {
        SlaTracker a = SlaTracker.create(t0, SlaPolicy.defaultFor(Priority.P4));
        SlaTracker b = SlaTracker.create(t0, SlaPolicy.defaultFor(Priority.P4));

        a.reanchor(SlaPolicy.defaultFor(Priority.P2), at(5));
        b.updatePolicy(SlaPolicy.defaultFor(Priority.P2), at(5));

        assertThat(a.getResolutionDueAt()).isEqualTo(b.getResolutionDueAt());
        assertThat(a.getResponseDueAt()).isEqualTo(b.getResponseDueAt());
        assertThat(a.getStatus()).isEqualTo(b.getStatus());
    }

    @Test
    @DisplayName("reanchor on a resolved tracker changes nothing")
    void reanchorIgnoresResolvedTracker() {
        SlaTracker tracker = SlaTracker.create(t0, SlaPolicy.defaultFor(Priority.P4));
        tracker.recordResolution(at(10));
        Instant due = tracker.getResolutionDueAt();

        tracker.reanchor(SlaPolicy.defaultFor(Priority.P1), at(500));

        assertThat(tracker.getResolutionDueAt()).isEqualTo(due);
        assertThat(tracker.getStatus()).isEqualTo(SlaStatus.MET);
    }

    @Test
    @DisplayName("reanchor rejects a null policy on an open tracker")
    void reanchorRejectsNullPolicy() {
        SlaTracker tracker = SlaTracker.create(t0, SlaPolicy.defaultFor(Priority.P4));

        assertThatThrownBy(() -> tracker.reanchor(null, at(1))).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("reanchor rejects a null clock reading on an open tracker")
    void reanchorRejectsNullNow() {
        SlaTracker tracker = SlaTracker.create(t0, SlaPolicy.defaultFor(Priority.P4));

        assertThatThrownBy(() -> tracker.reanchor(SlaPolicy.defaultFor(Priority.P1), null))
                .isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest(name = "[{index}] risk score after an override to {0} is between 0 and 1")
    @EnumSource(Priority.class)
    void riskScoreStaysInRange(Priority to) {
        Incident incident = classifiedAs(Priority.P3);
        override(incident, to, at(5));

        double score = incident.getSlaTracker().getRiskScore(at(15));

        assertThat(score).isBetween(0.0, 1.0);
    }
}
