package com.iitsm.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * H4: model-based test of the paused clock. For thousands of random sequences of respond, pause, resume
 * and evaluate, the tracker must behave exactly like an unpaused tracker measured in working time
 * (real time minus the time spent paused). Steps are whole minutes so the 75 %, 90 % and 100 %
 * boundaries are hit often.
 */
class SlaTrackerPauseModelTest {

    private static final Instant CREATED = Instant.parse("2026-10-25T10:00:00Z");
    private static final int SEQUENCES = 5000;
    private static final int STEPS = 40;

    /** The tracker as it should behave: status as a pure function of working time and the current policy. */
    private static SlaStatus expectedStatus(Duration working, Duration respondedAfter, SlaPolicy policy) {
        // ADR-030: a late response is remembered (judged against the current policy); no response yet is judged by the clock
        Duration responseTime = respondedAfter != null ? respondedAfter : working;
        if (responseTime.compareTo(policy.getResponseTarget()) > 0) {
            return SlaStatus.BREACHED;
        }
        if (working.compareTo(policy.getResolutionTarget()) > 0) {
            return SlaStatus.BREACHED;
        }
        double ratio = (double) working.toMillis() / policy.getResolutionTarget().toMillis();
        if (ratio >= 0.90) {
            return SlaStatus.WARNING;
        }
        return ratio >= 0.75 ? SlaStatus.AT_RISK : SlaStatus.ON_TRACK;
    }

    @Test
    @DisplayName("H4: status and risk score equal the unpaused tracker's at the same working time")
    void trackerMatchesWorkingTimeModel() {
        for (int seed = 0; seed < SEQUENCES; seed++) {
            runSequence(seed, false);
        }
    }

    @Test
    @DisplayName("H4: the same holds when the policy changes mid-life (priority override, reclassify), also while paused")
    void trackerMatchesModelAcrossPolicyChanges() {
        for (int seed = 0; seed < SEQUENCES; seed++) {
            runSequence(seed, true);
        }
    }

    private void runSequence(int seed, boolean changePolicy) {
        Random random = new Random(seed);
        Priority[] priorities = {Priority.P1, Priority.P2, Priority.P3, Priority.P4};
        SlaPolicy policy = SlaPolicy.defaultFor(Priority.P1);
        SlaTracker tracker = SlaTracker.create(CREATED, policy);
        Instant now = CREATED;
        Duration pausedTotal = Duration.ZERO;
        Instant pausedAt = null;
        Duration respondedAfter = null; // working time at which the response was recorded
        StringBuilder trace = new StringBuilder("seed " + seed + ":");

        for (int step = 0; step < STEPS; step++) {
            now = now.plus(Duration.ofMinutes(random.nextInt(12)));
            int op = random.nextInt(changePolicy ? 6 : 5);
            if (op == 0 && respondedAfter == null && pausedAt == null) {
                tracker.recordResponse(now);
                respondedAfter = Duration.between(CREATED, now).minus(pausedTotal);
                trace.append(" respond@").append(minutes(now));
            } else if (op == 1 && pausedAt == null && respondedAfter != null) {
                tracker.pauseClock(now);
                pausedAt = now;
                trace.append(" pause@").append(minutes(now));
            } else if (op == 2 && pausedAt != null) {
                tracker.resumeClock(now);
                pausedTotal = pausedTotal.plus(Duration.between(pausedAt, now));
                pausedAt = null;
                trace.append(" resume@").append(minutes(now));
            } else if (op == 5) {
                policy = SlaPolicy.defaultFor(priorities[random.nextInt(priorities.length)]);
                tracker.reanchor(policy, now);
                trace.append(" policy=").append(policy.getPriority()).append('@').append(minutes(now));
            } else {
                tracker.evaluateStatus(now);
                trace.append(" eval@").append(minutes(now));
            }
            tracker.evaluateStatus(now);

            Instant clockNow = pausedAt != null ? pausedAt : now;
            Duration working = Duration.between(CREATED, clockNow).minus(pausedTotal);
            SlaStatus expected = expectedStatus(working, respondedAfter, policy);
            assertThat(tracker.getStatus()).as("%s -> status at %s (working %s)", trace, minutes(now), working)
                    .isEqualTo(expected);

            double expectedRisk = expected == SlaStatus.BREACHED
                    ? 1.0
                    : Math.min(1.0, (double) working.toMillis() / policy.getResolutionTarget().toMillis());
            assertThat(tracker.getRiskScore(now)).as("%s -> risk at %s", trace, minutes(now))
                    .isCloseTo(expectedRisk, org.assertj.core.data.Offset.offset(1e-9));
            assertThat(tracker.getPausedDuration()).as("%s -> pausedDuration", trace).isEqualTo(pausedTotal);
            assertThat(tracker.isPaused()).as("%s -> paused", trace).isEqualTo(pausedAt != null);
        }
    }

    private static long minutes(Instant at) {
        return Duration.between(CREATED, at).toMinutes();
    }
}
