package com.iitsm.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Remaining-defects plan, Phase D (ADR-030, stress-audit item M1, decision D9-A): a response breach is remembered. The
 * tracker's status is the worse of its response verdict and its resolution verdict, both derived from stored fields:
 * the response verdict compares {@code respondedAt} with {@code responseDueAt} (or the clock while no response exists).
 *
 * <p>Times use the default policies: P1 responds within 5 minutes and resolves within 30; P3 within 1 hour and 8 hours.
 */
class SlaTrackerResponseBreachTest {

    private static final Instant T0 = Instant.parse("2026-11-06T10:00:00Z");

    private static Instant at(long minutes) {
        return T0.plus(Duration.ofMinutes(minutes));
    }

    private static SlaTracker p1Tracker() {
        return SlaTracker.create(T0, SlaPolicy.defaultFor(Priority.P1));
    }

    @Test
    @DisplayName("M1: a ticket the scan marked BREACHED for a missing response stays BREACHED when it is acknowledged late")
    void lateAcknowledgeDoesNotEraseTheBreach() {
        SlaTracker tracker = p1Tracker();
        tracker.evaluateStatus(at(6));
        assertThat(tracker.getStatus()).as("precondition: the scan saw the breach").isEqualTo(SlaStatus.BREACHED);

        tracker.recordResponse(at(7));

        assertThat(tracker.getStatus()).isEqualTo(SlaStatus.BREACHED);
        tracker.evaluateStatus(at(8));
        assertThat(tracker.getStatus()).isEqualTo(SlaStatus.BREACHED);
        assertThat(tracker.getRiskScore(at(8))).isEqualTo(1.0);
    }

    @Test
    @DisplayName("M1: a late response is a breach even when no scan saw it before the response")
    void lateResponseWithoutAnEarlierScan() {
        SlaTracker tracker = p1Tracker();

        tracker.recordResponse(at(7));

        assertThat(tracker.getStatus()).isEqualTo(SlaStatus.BREACHED);
    }

    @Test
    @DisplayName("A response exactly at the deadline is on time, and an on-time response never breaches by itself")
    void onTimeResponseIsNotABreach() {
        SlaTracker tracker = p1Tracker();

        tracker.recordResponse(at(5));
        tracker.evaluateStatus(at(10));

        assertThat(tracker.getStatus()).isEqualTo(SlaStatus.ON_TRACK);
    }

    @Test
    @DisplayName("M1: a late response followed by an on-time resolution is still BREACHED, not MET")
    void lateResponseThenOnTimeResolutionIsBreached() {
        SlaTracker tracker = p1Tracker();
        tracker.recordResponse(at(7));

        tracker.recordResolution(at(20));

        assertThat(tracker.getStatus()).isEqualTo(SlaStatus.BREACHED);
        tracker.evaluateStatus(at(21));
        assertThat(tracker.getStatus()).isEqualTo(SlaStatus.BREACHED);
    }

    @Test
    @DisplayName("Control: an on-time response and an on-time resolution are MET")
    void onTimeEverythingIsMet() {
        SlaTracker tracker = p1Tracker();
        tracker.recordResponse(at(3));

        tracker.recordResolution(at(20));

        assertThat(tracker.getStatus()).isEqualTo(SlaStatus.MET);
    }

    @Test
    @DisplayName("M1: reopening a ticket that responded late keeps the response breach")
    void reopenKeepsTheResponseBreach() {
        SlaTracker tracker = p1Tracker();
        tracker.recordResponse(at(7));
        tracker.recordResolution(at(10));

        tracker.reopen(at(12));

        assertThat(tracker.getStatus()).isEqualTo(SlaStatus.BREACHED);
    }

    @Test
    @DisplayName("ADR-024 kept: a policy change moves the response deadline, so it can clear and recreate the breach")
    void policyChangeMovesTheResponseVerdict() {
        SlaTracker tracker = p1Tracker();
        tracker.recordResponse(at(7));
        assertThat(tracker.getStatus()).isEqualTo(SlaStatus.BREACHED);

        tracker.reanchor(SlaPolicy.defaultFor(Priority.P3), at(8)); // 1 hour to respond: 7 minutes is fine
        assertThat(tracker.getStatus()).isEqualTo(SlaStatus.ON_TRACK);

        tracker.reanchor(SlaPolicy.defaultFor(Priority.P1), at(9)); // raised again: 7 minutes is late
        assertThat(tracker.getStatus()).isEqualTo(SlaStatus.BREACHED);
    }

    @Test
    @DisplayName("A pause after the response does not move the response verdict when the policy changes")
    void pauseAfterTheResponseDoesNotChangeTheVerdict() {
        SlaTracker tracker = p1Tracker();
        tracker.recordResponse(at(7));
        tracker.pauseClock(at(8));
        tracker.resumeClock(at(38)); // waited 30 minutes for the employee

        tracker.reanchor(SlaPolicy.defaultFor(Priority.P1), at(40));

        assertThat(tracker.getStatus())
                .as("the response was late by working time; the later wait must not make it on time")
                .isEqualTo(SlaStatus.BREACHED);
    }

    @Test
    @DisplayName("A response that was on time under the old policy and the new one stays unbreached across a pause")
    void onTimeResponseAcrossAPauseAndPolicyChange() {
        SlaTracker tracker = p1Tracker();
        tracker.recordResponse(at(3));
        tracker.pauseClock(at(4));
        tracker.resumeClock(at(34));

        tracker.reanchor(SlaPolicy.defaultFor(Priority.P2), at(40));

        assertThat(tracker.getStatus()).isNotEqualTo(SlaStatus.BREACHED);
    }
}
