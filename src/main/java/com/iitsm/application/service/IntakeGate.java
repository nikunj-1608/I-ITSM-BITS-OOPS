package com.iitsm.application.service;

import com.iitsm.domain.exception.ServiceBusyException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Pattern: Guard (mutual exclusion).
 * Lets one ticket intake run at a time inside this application instance, in first-come order (ADR-018).
 *
 * <p>Intake is the one write path where simultaneous requests collide by design: every report of the same outage
 * updates the same cluster root, so all but one lose the optimistic-lock race; and each intake holds a database
 * connection while it opens a nested transaction for technician assignment, so as many simultaneous intakes as pool
 * connections starve the pool. Intake takes tens of milliseconds, so queueing reporters one behind the other is
 * cheaper than failing them. A reporter who cannot get a turn within the limit gets a clear "busy" answer.
 *
 * <p>The gate protects a single application instance. With several instances the optimistic lock stays the safety
 * net and losers still receive HTTP 409.
 */
@Component
public class IntakeGate {

    private final ReentrantLock turn = new ReentrantLock(true);
    private final boolean serialized;
    private final Duration waitLimit;

    public IntakeGate(
            @Value("${iitsm.intake.serialized:true}") boolean serialized,
            @Value("${iitsm.intake.gate-timeout-ms:30000}") long waitLimitMillis) {
        if (waitLimitMillis <= 0) {
            throw new IllegalArgumentException("Intake gate timeout must be positive: " + waitLimitMillis);
        }
        this.serialized = serialized;
        this.waitLimit = Duration.ofMillis(waitLimitMillis);
    }

    /** Runs the work alone; the gate is held until it returns or throws (so until the intake transaction committed). */
    public <T> T runExclusive(Supplier<T> work) {
        Objects.requireNonNull(work, "work cannot be null");
        if (!serialized) {
            return work.get();
        }
        try {
            if (!turn.tryLock(waitLimit.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new ServiceBusyException();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ServiceBusyException(ex);
        }
        try {
            return work.get();
        } finally {
            turn.unlock();
        }
    }

    /** Reports waiting for their turn right now (a verification and monitoring aid). */
    public int getWaitingCount() {
        return turn.getQueueLength();
    }
}
