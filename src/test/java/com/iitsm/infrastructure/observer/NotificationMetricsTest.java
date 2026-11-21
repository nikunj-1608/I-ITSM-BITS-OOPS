package com.iitsm.infrastructure.observer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.iitsm.domain.event.DomainEvent;
import com.iitsm.domain.event.DomainEventListener;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

/**
 * Remaining-defects plan, Phase I (ADR-038, item R-N): the number of notifications waiting for the delivery worker and
 * the number dropped (queue full or shutting down) were only visible through Java methods. They are now Actuator
 * metrics, so an operator can see a backlog or a loss without reading logs.
 */
class NotificationMetricsTest {

    private static final Instant NOW = Instant.parse("2026-11-07T10:00:00Z");

    private TransactionAwareDomainEventPublisher publisher;
    private SimpleMeterRegistry registry;
    private final CountDownLatch release = new CountDownLatch(1);

    private static DomainEvent event(int n) {
        return new DomainEvent.IncidentAcknowledged("EVT-" + n, NOW, "INC-" + n, "TECH-1");
    }

    @BeforeEach
    void setUp() {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        publisher = new TransactionAwareDomainEventPublisher(transactionManager, 2, 0);
        // a listener that blocks until released keeps the queue full
        publisher.registerListener(new DomainEventListener() {
            @Override
            public void onEvent(DomainEvent event) {
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }

            @Override
            public boolean deferUntilCommit() {
                return true;
            }
        });
        registry = new SimpleMeterRegistry();
        new NotificationMetrics(publisher).bindTo(registry);
    }

    @AfterEach
    void tearDown() {
        release.countDown();
        publisher.close();
    }

    @Test
    @DisplayName("R-N: both metrics are registered under their documented names")
    void metricsExist() {
        assertThat(registry.find(NotificationMetrics.PENDING).gauge()).isNotNull();
        assertThat(registry.find(NotificationMetrics.DROPPED).functionCounter()).isNotNull();
        assertThat(NotificationMetrics.PENDING).isEqualTo("iitsm.notifications.pending");
        assertThat(NotificationMetrics.DROPPED).isEqualTo("iitsm.notifications.dropped");
    }

    @Test
    @DisplayName("R-N: the pending gauge follows the events waiting for the worker")
    void pendingFollowsTheQueue() {
        assertThat(registry.get(NotificationMetrics.PENDING).gauge().value()).isZero();

        publisher.publish(event(1));
        publisher.publish(event(2));

        assertThat(registry.get(NotificationMetrics.PENDING).gauge().value()).isEqualTo(publisher.getPendingCount());
        assertThat(registry.get(NotificationMetrics.PENDING).gauge().value()).isGreaterThan(0);
    }

    @Test
    @DisplayName("R-N: the dropped counter counts events refused because the queue is full")
    void droppedFollowsOverflow() {
        for (int i = 1; i <= 8; i++) {
            publisher.publish(event(i));
        }

        assertThat(publisher.getDroppedCount()).isGreaterThan(0);
        assertThat(registry.get(NotificationMetrics.DROPPED).functionCounter().count())
                .isEqualTo((double) publisher.getDroppedCount());
    }

    @Test
    @DisplayName("R-N: after the backlog is delivered the pending gauge returns to zero")
    void pendingReturnsToZero() {
        publisher.publish(event(1));
        release.countDown();

        assertThat(publisher.awaitIdle(Duration.ofSeconds(5))).isTrue();

        assertThat(registry.get(NotificationMetrics.PENDING).gauge().value()).isZero();
    }
}
