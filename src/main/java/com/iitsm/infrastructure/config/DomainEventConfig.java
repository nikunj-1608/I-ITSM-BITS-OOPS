package com.iitsm.infrastructure.config;

import com.iitsm.domain.event.DomainEventListener;
import com.iitsm.infrastructure.observer.NotificationMetrics;
import com.iitsm.infrastructure.observer.TransactionAwareDomainEventPublisher;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Spring configuration wiring the event spine.
 * Automatically discovers all registered DomainEventListener beans (e.g. AuditTrailObserver, NotificationObserver)
 * and subscribes them to the singleton DomainEventPublisher. Listeners that declare deferUntilCommit() (ADR-017)
 * are delivered after the publishing transaction commits, on a dedicated worker thread (see
 * TransactionAwareDomainEventPublisher for why).
 */
@Configuration
public class DomainEventConfig {

    @Bean
    public TransactionAwareDomainEventPublisher domainEventPublisher(
            List<DomainEventListener> listeners,
            PlatformTransactionManager transactionManager,
            @Value("${iitsm.events.delivery-queue-capacity:" + TransactionAwareDomainEventPublisher.DEFAULT_QUEUE_CAPACITY + "}")
                    int deliveryQueueCapacity,
            @Value("${iitsm.events.history-limit:1000}") int historyLimit) {
        TransactionAwareDomainEventPublisher publisher =
                new TransactionAwareDomainEventPublisher(transactionManager, deliveryQueueCapacity, historyLimit);
        for (DomainEventListener listener : listeners) {
            publisher.registerListener(listener);
        }
        return publisher;
    }

    /** The notification backlog and loss counters as Actuator metrics (ADR-038). */
    @Bean
    public MeterBinder notificationMetrics(TransactionAwareDomainEventPublisher publisher) {
        return new NotificationMetrics(publisher);
    }
}

