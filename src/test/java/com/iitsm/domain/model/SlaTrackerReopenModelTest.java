package com.iitsm.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * H5: model-based test of resolve and reopen. For thousands of random sequences of respond, pause,
 * resume, resolve, reopen and evaluate, the tracker must match a model in which the resolution target
 * is the policy target plus every verdict wait credited so far, measured in working time (real time
 * minus paused time). A resolved ticket keeps its verdict; a reopened one is judged again.
 */
class SlaTrackerReopenModelTest {

    private static final Instant CREATED = Instant.parse("2026-11-03T10:00:00Z");
    private static final int SEQUENCES = 5000;
    private static final int STEPS = 40;

    private static boolean responseBreached(Duration working, Duration respondedAfter, SlaPolicy policy) {
        // ADR-030: a late response is remembered; no response yet is judged by the clock
        return (respondedAfter != null ? respondedAfter : working).compareTo(policy.getResponseTarget()) > 0;
    }

    private static SlaStatus runningStatus(Duration working, Duration respondedAfter, SlaPolicy policy, Duration target) {
        if (responseBreached(working, respondedAfter, policy)) {
            return SlaStatus.BREACHED;
        }
        if (working.compareTo(target) > 0) {
            return SlaStatus.BREACHED;
        }
        double ratio = (double) working.toMillis() / target.toMillis();
        if (ratio >= 0.90) {
            return SlaStatus.WARNING;
        }
        return ratio >= 0.75 ? SlaStatus.AT_RISK : SlaStatus.ON_TRACK;
    }

    @Test
    @DisplayName("H5: tracker equals the working-time model across random resolve/reject/pause sequences")
    void trackerMatchesModel() {
        for (int seed = 0; seed < SEQUENCES; seed++) {
            runSequence(seed);
        }
    }

    private void runSequence(int seed) {
        Random random = new Random(seed);
        SlaPolicy policy = SlaPolicy.defaultFor(Priority.P1);
        SlaTracker tracker = SlaTracker.create(CREATED, policy);
        Instant now = CREATED;
        Duration pausedTotal = Duration.ZERO;
        Duration credit = Duration.ZERO;
        Instant pausedAt = null;
        Instant resolvedAt = null;
        SlaStatus verdict = null;
        Duration respondedAfter = null; // working time at which the response was recorded
        StringBuilder trace = new StringBuilder("seed " + seed + ":");

        for (int step = 0; step < STEPS; step++) {
            now = now.plus(Duration.ofMinutes(random.nextInt(12)));
            int op = random.nextInt(6);
            if (op == 0 && respondedAfter == null) {
                tracker.recordResponse(now);
                respondedAfter = Duration.between(CREATED, now).minus(pausedTotal);
                trace.append(" respond@").append(minutes(now));
            } else if (op == 1 && respondedAfter != null && resolvedAt == null && pausedAt == null) {
                tracker.pauseClock(now);
                pausedAt = now;
                trace.append(" pause@").append(minutes(now));
            } else if (op == 2 && pausedAt != null) {
                tracker.resumeClock(now);
                pausedTotal = pausedTotal.plus(Duration.between(pausedAt, now));
                pausedAt = null;
                trace.append(" resume@").append(minutes(now));
            } else if (op == 3 && respondedAfter != null && resolvedAt == null) {
                if (pausedAt != null) {
                    pausedTotal = pausedTotal.plus(Duration.between(pausedAt, now));
                    pausedAt = null;
                }
                tracker.recordResolution(now);
                resolvedAt = now;
                Duration working = Duration.between(CREATED, now).minus(pausedTotal);
                verdict = working.compareTo(policy.getResolutionTarget().plus(credit)) > 0
                        || responseBreached(working, respondedAfter, policy)
                        ? SlaStatus.BREACHED : SlaStatus.MET;
                trace.append(" resolve@").append(minutes(now));
            } else if (op == 4 && resolvedAt != null) {
                tracker.reopen(now);
                credit = credit.plus(Duration.between(resolvedAt, now));
                resolvedAt = null;
                verdict = null;
                trace.append(" reopen@").append(minutes(now));
            } else {
                trace.append(" eval@").append(minutes(now));
            }
            tracker.evaluateStatus(now);

            Instant clockNow = pausedAt != null ? pausedAt : now;
            Duration working = Duration.between(CREATED, clockNow).minus(pausedTotal);
            Duration target = policy.getResolutionTarget().plus(credit);
            SlaStatus expected = resolvedAt != null ? verdict : runningStatus(working, respondedAfter, policy, target);

            assertThat(tracker.getStatus()).as("%s -> status at %s (working %s, target %s)", trace, minutes(now), working, target)
                    .isEqualTo(expected);
            assertThat(tracker.getResolvedAt()).as("%s -> resolvedAt", trace).isEqualTo(resolvedAt);
            assertThat(tracker.getPausedDuration()).as("%s -> pausedDuration", trace).isEqualTo(pausedTotal);
            assertThat(tracker.isPaused()).as("%s -> paused", trace).isEqualTo(pausedAt != null);
            assertThat(tracker.getResolutionDueAt()).as("%s -> resolutionDueAt", trace)
                    .isEqualTo(CREATED.plus(policy.getResolutionTarget()).plus(pausedTotal).plus(credit));

            double expectedRisk = expected == SlaStatus.MET ? 0.0
                    : expected == SlaStatus.BREACHED ? 1.0
                    : Math.min(1.0, (double) working.toMillis() / target.toMillis());
            assertThat(tracker.getRiskScore(now)).as("%s -> risk at %s", trace, minutes(now))
                    .isCloseTo(expectedRisk, org.assertj.core.data.Offset.offset(1e-9));
        }
    }

    private static long minutes(Instant at) {
        return Duration.between(CREATED, at).toMinutes();
    }
}
