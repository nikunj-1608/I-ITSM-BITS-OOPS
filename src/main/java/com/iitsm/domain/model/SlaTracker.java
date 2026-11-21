package com.iitsm.domain.model;

import com.iitsm.domain.exception.ValidationException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * OOP: Value Object / Lifecycle entity tracking SLA compliance for an incident.
 * Encapsulates response/resolution deadlines, pause intervals during PENDING_INFO, and status evaluation.
 * OOP: Encapsulation. The tracker owns the paused-clock rule (time is read at the pause instant while
 * paused), so callers never check the pause state themselves.
 */
public class SlaTracker {

    private static final double AT_RISK_RATIO = 0.75;
    private static final double WARNING_RATIO = 0.90;

    private final Instant createdAt;
    private Instant responseDueAt;
    private Instant resolutionDueAt;
    private Instant respondedAt;
    private Instant resolvedAt;
    private Duration pausedDuration;
    private Duration creditedDuration;
    private Instant pausedAt;
    private SlaStatus status;

    public SlaTracker(Instant createdAt, DeadlinePair deadlines) {
        if (createdAt == null) {
            throw new ValidationException("createdAt", "Created timestamp cannot be null");
        }
        Objects.requireNonNull(deadlines, "deadlines cannot be null");
        this.createdAt = createdAt;
        this.responseDueAt = deadlines.responseDueAt();
        this.resolutionDueAt = deadlines.resolutionDueAt();
        this.pausedDuration = Duration.ZERO;
        this.creditedDuration = Duration.ZERO;
        this.pausedAt = null;
        this.status = SlaStatus.ON_TRACK;
    }

    public static SlaTracker create(Instant createdAt, SlaPolicy policy) {
        Objects.requireNonNull(policy, "policy cannot be null");
        Instant responseDue = createdAt.plus(policy.getResponseTarget());
        Instant resolutionDue = createdAt.plus(policy.getResolutionTarget());
        return new SlaTracker(createdAt, new DeadlinePair(responseDue, resolutionDue));
    }

    public void updatePolicy(SlaPolicy newPolicy, Instant now) {
        Objects.requireNonNull(newPolicy, "newPolicy cannot be null");
        Objects.requireNonNull(now, "now cannot be null");
        Duration pauseOffset = (this.pausedDuration != null) ? this.pausedDuration : Duration.ZERO;
        // Once the response is recorded, later pauses (waiting for the employee) must not move its deadline: a late
        // response would look on time after a policy change (ADR-030). Before the response a pause does move it.
        Duration responsePause = (this.respondedAt == null) ? pauseOffset : Duration.ZERO;
        this.responseDueAt = this.createdAt.plus(newPolicy.getResponseTarget()).plus(responsePause);
        this.resolutionDueAt = this.createdAt.plus(newPolicy.getResolutionTarget()).plus(pauseOffset).plus(creditedDuration);
        evaluateStatus(now);
    }

    /**
     * Re-anchors the deadlines to a new policy (priority change). A ticket that already has its
     * resolution keeps the deadlines and verdict it earned; rewriting them would rewrite history.
     */
    public void reanchor(SlaPolicy newPolicy, Instant now) {
        if (this.resolvedAt == null) {
            updatePolicy(newPolicy, now);
        }
    }

    public static SlaTracker reconstitute(
            Instant createdAt,
            Instant responseDueAt,
            Instant resolutionDueAt,
            Instant respondedAt,
            Instant resolvedAt,
            Duration pausedDuration,
            Instant pausedAt,
            SlaStatus status) {
        return reconstitute(createdAt, responseDueAt, resolutionDueAt, respondedAt, resolvedAt, pausedDuration,
                Duration.ZERO, pausedAt, status);
    }

    public static SlaTracker reconstitute(
            Instant createdAt,
            Instant responseDueAt,
            Instant resolutionDueAt,
            Instant respondedAt,
            Instant resolvedAt,
            Duration pausedDuration,
            Duration creditedDuration,
            Instant pausedAt,
            SlaStatus status) {
        SlaTracker tracker = new SlaTracker(createdAt, new DeadlinePair(responseDueAt, resolutionDueAt));
        tracker.respondedAt = respondedAt;
        tracker.resolvedAt = resolvedAt;
        tracker.pausedDuration = (pausedDuration != null) ? pausedDuration : Duration.ZERO;
        tracker.creditedDuration = (creditedDuration != null) ? creditedDuration : Duration.ZERO;
        tracker.pausedAt = pausedAt;
        tracker.status = (status != null) ? status : SlaStatus.ON_TRACK;
        return tracker;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getResponseDueAt() {
        return responseDueAt;
    }

    public Instant getResolutionDueAt() {
        return resolutionDueAt;
    }

    public Instant getRespondedAt() {
        return respondedAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public Duration getPausedDuration() {
        return pausedDuration;
    }

    /** Time credited to the resolution deadline for waiting on an employee's verdict (rejected fixes). */
    public Duration getCreditedDuration() {
        return creditedDuration;
    }

    public Instant getPausedAt() {
        return pausedAt;
    }

    public boolean isPaused() {
        return pausedAt != null;
    }

    public SlaStatus getStatus() {
        return status;
    }

    public void recordResponse(Instant now) {
        Objects.requireNonNull(now, "response timestamp cannot be null");
        if (this.respondedAt == null) {
            this.respondedAt = now;
            evaluateStatus(now);
        }
    }

    public void recordResolution(Instant now) {
        Objects.requireNonNull(now, "resolution timestamp cannot be null");
        if (isPaused()) {
            resumeClock(now);
        }
        this.resolvedAt = now;
        // MET needs both targets met: a late response is not forgotten by a timely resolution (ADR-030).
        boolean breached = now.isAfter(resolutionDueAt) || responseBreached(now);
        this.status = breached ? SlaStatus.BREACHED : SlaStatus.MET;
    }

    /**
     * Undoes a resolution that the employee rejected, so the clock works on the ticket again.
     * The time the ticket waited for the verdict is credited to the resolution deadline.
     */
    public void reopen(Instant now) {
        Objects.requireNonNull(now, "reopen timestamp cannot be null");
        if (resolvedAt == null) {
            return;
        }
        Duration verdictWait = Duration.between(resolvedAt, now);
        if (!verdictWait.isNegative()) {
            this.resolutionDueAt = this.resolutionDueAt.plus(verdictWait);
            this.creditedDuration = this.creditedDuration.plus(verdictWait);
        }
        this.resolvedAt = null;
        this.status = SlaStatus.ON_TRACK;
        evaluateStatus(now);
    }

    public void pauseClock(Instant now) {
        Objects.requireNonNull(now, "pause timestamp cannot be null");
        if (!isPaused() && resolvedAt == null) {
            this.pausedAt = now;
        }
    }

    public void resumeClock(Instant now) {
        Objects.requireNonNull(now, "resume timestamp cannot be null");
        if (isPaused()) {
            Duration pausedWindow = Duration.between(pausedAt, now);
            if (!pausedWindow.isNegative()) {
                this.pausedDuration = this.pausedDuration.plus(pausedWindow);
                if (respondedAt == null) {
                    this.responseDueAt = this.responseDueAt.plus(pausedWindow);
                }
                if (resolvedAt == null) {
                    this.resolutionDueAt = this.resolutionDueAt.plus(pausedWindow);
                }
            }
            this.pausedAt = null;
            evaluateStatus(now);
        }
    }

    /**
     * The instant the SLA clock should be read at: frozen at the moment the clock was paused
     * (ticket waiting for the employee), the real time otherwise.
     */
    private Instant effectiveNow(Instant now) {
        return isPaused() ? pausedAt : now;
    }

    /**
     * True when no response has been recorded and the response target has passed on the SLA clock.
     * A paused clock never counts as overdue past its pause instant.
     */
    public boolean isResponseOverdue(Instant now) {
        Objects.requireNonNull(now, "current timestamp cannot be null");
        return respondedAt == null && effectiveNow(now).isAfter(responseDueAt);
    }

    /**
     * The response target was missed: the response came after its deadline, or none has come and the deadline has passed
     * on the SLA clock. Derived from stored fields only, so it survives a reload and needs no extra column.
     */
    private boolean responseBreached(Instant clockNow) {
        return respondedAt == null ? clockNow.isAfter(responseDueAt) : respondedAt.isAfter(responseDueAt);
    }

    /** Which target a BREACHED ticket missed first: the response target comes before the resolution target. */
    public enum BreachedTarget {
        RESPONSE("RESPONSE_TARGET"),
        RESOLUTION("RESOLUTION_TARGET");

        private final String label;

        BreachedTarget(String label) {
            this.label = label;
        }

        /** The name carried by the SlaViolated event. */
        public String label() {
            return label;
        }
    }

    /** The target the ticket breached, empty while it is not BREACHED. */
    public Optional<BreachedTarget> breachedTarget(Instant now) {
        Objects.requireNonNull(now, "current timestamp cannot be null");
        if (status != SlaStatus.BREACHED) {
            return Optional.empty();
        }
        return Optional.of(responseBreached(effectiveNow(now)) ? BreachedTarget.RESPONSE : BreachedTarget.RESOLUTION);
    }

    public void evaluateStatus(Instant now) {
        Objects.requireNonNull(now, "current timestamp cannot be null");
        if (status == SlaStatus.MET) {
            return;
        }
        Instant clockNow = effectiveNow(now);

        // A response breach is remembered: it stays after the response arrives and after a timely resolution (ADR-030)
        if (responseBreached(clockNow)) {
            this.status = SlaStatus.BREACHED;
            return;
        }

        // Check if resolution target breached
        if (resolvedAt != null) {
            this.status = resolvedAt.isAfter(resolutionDueAt) ? SlaStatus.BREACHED : SlaStatus.MET;
            return;
        } else if (clockNow.isAfter(resolutionDueAt)) {
            this.status = SlaStatus.BREACHED;
            return;
        }

        // Calculate progress percentage of resolution target
        Duration totalWindow = Duration.between(createdAt, resolutionDueAt).minus(pausedDuration);
        Duration elapsed = Duration.between(createdAt, clockNow).minus(pausedDuration);

        if (totalWindow.toMillis() <= 0) {
            this.status = SlaStatus.BREACHED;
            return;
        }

        double ratio = (double) elapsed.toMillis() / (double) totalWindow.toMillis();
        if (ratio >= WARNING_RATIO) {
            this.status = SlaStatus.WARNING;
        } else if (ratio >= AT_RISK_RATIO) {
            this.status = SlaStatus.AT_RISK;
        } else {
            this.status = SlaStatus.ON_TRACK;
        }
    }

    /**
     * Calculates a continuous risk score between 0.0 (fresh) and 1.0 (breached).
     */
    public double getRiskScore(Instant now) {
        if (status == SlaStatus.MET) {
            return 0.0;
        }
        Instant effectiveNow = effectiveNow(now);
        if (status == SlaStatus.BREACHED || effectiveNow.isAfter(resolutionDueAt)) {
            return 1.0;
        }
        Duration totalWindow = Duration.between(createdAt, resolutionDueAt).minus(pausedDuration);
        if (totalWindow.toMillis() <= 0) {
            return 1.0;
        }
        Duration elapsed = Duration.between(createdAt, effectiveNow).minus(pausedDuration);
        double ratio = (double) Math.max(0, elapsed.toMillis()) / (double) totalWindow.toMillis();
        return Math.min(1.0, Math.max(0.0, ratio));
    }
}

