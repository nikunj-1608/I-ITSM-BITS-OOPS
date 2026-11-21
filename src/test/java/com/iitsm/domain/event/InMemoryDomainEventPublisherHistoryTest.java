package com.iitsm.domain.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Remaining-defects plan, Phase I (ADR-038, stress-audit item P4): the publisher kept every event it ever published in
 * a list that only grew, so a long-running instance leaked memory with every ticket action. The history is now a
 * bounded buffer (a verification aid) with a configurable size; a size of zero keeps nothing (production).
 */
class InMemoryDomainEventPublisherHistoryTest {

    private static final Instant NOW = Instant.parse("2026-11-07T10:00:00Z");

    private static DomainEvent event(int n) {
        return new DomainEvent.IncidentAcknowledged("EVT-" + n, NOW.plusSeconds(n), "INC-" + n, "TECH-1");
    }

    @Test
    @DisplayName("P4: the history never holds more than its limit; the oldest events go first")
    void historyIsBounded() {
        InMemoryDomainEventPublisher publisher = new InMemoryDomainEventPublisher(3);

        for (int i = 1; i <= 10; i++) {
            publisher.publish(event(i));
        }

        assertThat(publisher.getPublishedHistory()).extracting(DomainEvent::eventId).containsExactly("EVT-8", "EVT-9", "EVT-10");
    }

    @Test
    @DisplayName("P4: a limit of zero keeps no history, and the listeners still get every event")
    void zeroKeepsNothing() {
        InMemoryDomainEventPublisher publisher = new InMemoryDomainEventPublisher(0);
        List<DomainEvent> seen = new ArrayList<>();
        publisher.registerListener(seen::add);

        for (int i = 1; i <= 5; i++) {
            publisher.publish(event(i));
        }

        assertThat(publisher.getPublishedHistory()).isEmpty();
        assertThat(seen).hasSize(5);
    }

    @Test
    @DisplayName("P4: the default constructor is bounded too (it is not the old unlimited list)")
    void defaultIsBounded() {
        InMemoryDomainEventPublisher publisher = new InMemoryDomainEventPublisher();

        for (int i = 0; i < InMemoryDomainEventPublisher.DEFAULT_HISTORY_LIMIT + 50; i++) {
            publisher.publish(event(i));
        }

        assertThat(publisher.getPublishedHistory()).hasSize(InMemoryDomainEventPublisher.DEFAULT_HISTORY_LIMIT);
        assertThat(publisher.getPublishedHistory().get(publisher.getPublishedHistory().size() - 1).eventId())
                .isEqualTo("EVT-" + (InMemoryDomainEventPublisher.DEFAULT_HISTORY_LIMIT + 49));
    }

    @Test
    @DisplayName("P4: the history a caller gets is a snapshot that cannot be changed and does not follow later events")
    void historyIsASnapshot() {
        InMemoryDomainEventPublisher publisher = new InMemoryDomainEventPublisher(5);
        publisher.publish(event(1));
        List<DomainEvent> snapshot = publisher.getPublishedHistory();

        publisher.publish(event(2));

        assertThat(snapshot).hasSize(1);
        assertThatThrownBy(() -> snapshot.add(event(3))).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("P4: a negative limit is refused")
    void negativeLimitIsRefused() {
        assertThatThrownBy(() -> new InMemoryDomainEventPublisher(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("P4: clearing the history empties it and keeps the bound")
    void clearKeepsTheBound() {
        InMemoryDomainEventPublisher publisher = new InMemoryDomainEventPublisher(2);
        publisher.publish(event(1));
        publisher.clearHistory();
        assertThat(publisher.getPublishedHistory()).isEmpty();

        for (int i = 2; i <= 6; i++) {
            publisher.publish(event(i));
        }

        assertThat(publisher.getPublishedHistory()).hasSize(2);
    }

    @Test
    @DisplayName("P4: parallel publishers never push the history past its limit")
    void parallelPublishersStayWithinTheLimit() throws Exception {
        InMemoryDomainEventPublisher publisher = new InMemoryDomainEventPublisher(50);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            for (int t = 0; t < threads; t++) {
                int base = t * 1000;
                pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < 500; i++) {
                        publisher.publish(event(base + i));
                    }
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(publisher.getPublishedHistory()).hasSize(50);
    }
}
