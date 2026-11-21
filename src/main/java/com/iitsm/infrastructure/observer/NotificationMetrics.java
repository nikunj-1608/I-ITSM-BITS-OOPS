package com.iitsm.infrastructure.observer;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.Objects;

// OOP (Interface realization): implements MeterBinder so the framework can register the counters.
/**
 * Pattern: Observer (metrics binder).
 * Publishes the delivery state of the notification worker as Actuator metrics (ADR-038, item R-N):
 * {@value #PENDING} is the number of events waiting for the worker, {@value #DROPPED} the number discarded because
 * the queue was full or the application was shutting down. A growing backlog or a non-zero loss shows up without
 * reading logs. Read them at {@code /actuator/metrics/<name>} as an administrator.
 */
public class NotificationMetrics implements MeterBinder {

    public static final String PENDING = "iitsm.notifications.pending";
    public static final String DROPPED = "iitsm.notifications.dropped";

    private final TransactionAwareDomainEventPublisher publisher;

    public NotificationMetrics(TransactionAwareDomainEventPublisher publisher) {
        this.publisher = Objects.requireNonNull(publisher, "publisher cannot be null");
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder(PENDING, publisher, TransactionAwareDomainEventPublisher::getPendingCount)
                .description("Events handed to the notification worker and not yet delivered")
                .register(registry);
        FunctionCounter.builder(DROPPED, publisher, TransactionAwareDomainEventPublisher::getDroppedCount)
                .description("Events discarded because the delivery queue was full or shutting down")
                .register(registry);
    }
}
