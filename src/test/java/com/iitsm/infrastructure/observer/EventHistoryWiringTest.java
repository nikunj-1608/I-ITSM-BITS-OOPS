package com.iitsm.infrastructure.observer;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitsm.domain.event.DomainEvent;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Remaining-defects plan, Phase I (ADR-038): the history limit of the shipped configuration reaches the publisher of the
 * running application, and the notification metrics are registered with the application's meter registry.
 */
@SpringBootTest(properties = "iitsm.events.history-limit=3")
@ActiveProfiles("test")
@Tag("regression")
class EventHistoryWiringTest {

    @Autowired private TransactionAwareDomainEventPublisher publisher;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("P4: the configured history limit is the size of the application's event history")
    void configuredLimitIsApplied() {
        // the audit listener needs a transaction; it is rolled back, nothing is stored and nothing is delivered
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            for (int i = 1; i <= 8; i++) {
                publisher.publish(new DomainEvent.IncidentAcknowledged("EVT-W" + i, Instant.parse("2026-11-07T10:00:00Z"), "INC-W" + i, "TECH-1"));
            }
            status.setRollbackOnly();
        });

        assertThat(publisher.getPublishedHistory()).hasSize(3);
        assertThat(publisher.getPublishedHistory().get(2).eventId()).isEqualTo("EVT-W8");
    }

    @Test
    @DisplayName("R-N: the notification metrics are registered in the application's meter registry")
    void metricsAreRegistered() {
        assertThat(meterRegistry.find(NotificationMetrics.PENDING).gauge()).isNotNull();
        assertThat(meterRegistry.find(NotificationMetrics.DROPPED).functionCounter()).isNotNull();
    }
}
