package com.iitsm.infrastructure.observer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.iitsm.domain.event.DomainEvent;
import com.iitsm.domain.event.DomainEventListener;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

/**
 * ADR-017: audit-style listeners run inside the transaction; notification-style listeners are handed to one worker
 * thread, only after commit, never from the request thread (which still holds its database connection then).
 */
class TransactionAwareDomainEventPublisherTest {

    private static final Duration PATIENCE = Duration.ofSeconds(5);

    private static class Recording implements DomainEventListener {
        final String name;
        final boolean deferred;
        final List<String> log;
        final List<String> threads = new CopyOnWriteArrayList<>();

        Recording(String name, boolean deferred, List<String> log) {
            this.name = name;
            this.deferred = deferred;
            this.log = log;
        }

        @Override
        public void onEvent(DomainEvent event) {
            threads.add(Thread.currentThread().getName());
            log.add(name + ":" + event.incidentId());
        }

        @Override
        public boolean deferUntilCommit() {
            return deferred;
        }
    }

    private PlatformTransactionManager transactionManager;
    private TransactionAwareDomainEventPublisher publisher;
    private List<String> log;
    private List<String> transactionOpenedOn;

    @BeforeEach
    void setUp() {
        transactionManager = mock(PlatformTransactionManager.class);
        transactionOpenedOn = new CopyOnWriteArrayList<>();
        when(transactionManager.getTransaction(any())).thenAnswer(invocation -> {
            transactionOpenedOn.add(Thread.currentThread().getName() + "/" + ((TransactionDefinition) invocation.getArgument(0)).getPropagationBehavior());
            return mock(TransactionStatus.class);
        });
        publisher = new TransactionAwareDomainEventPublisher(transactionManager);
        log = new CopyOnWriteArrayList<>();
        publisher.registerListener(new Recording("audit", false, log));
        publisher.registerListener(new Recording("notify", true, log));
    }

    @AfterEach
    void tearDown() {
        publisher.close();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private static DomainEvent event(String incidentId) {
        return new DomainEvent.IncidentAcknowledged("EV-" + incidentId, Instant.now(), incidentId, "TECH-1");
    }

    @Test
    @DisplayName("Inside a transaction the audit listener runs at once; the notification waits for the commit")
    void deferredListenerWaitsForCommit() {
        TransactionSynchronizationManager.initSynchronization();

        publisher.publish(event("INC-1"));
        assertThat(publisher.awaitIdle(PATIENCE)).isTrue();
        assertThat(log).containsExactly("audit:INC-1");

        TransactionSynchronizationUtils.triggerAfterCommit();
        assertThat(publisher.awaitIdle(PATIENCE)).isTrue();
        assertThat(log).containsExactly("audit:INC-1", "notify:INC-1");
    }

    @Test
    @DisplayName("A rolled-back transaction delivers no notification at all")
    void rollbackDropsDeferredDelivery() {
        TransactionSynchronizationManager.initSynchronization();

        publisher.publish(event("INC-1"));
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        TransactionSynchronizationUtils.invokeAfterCompletion(synchronizations, TransactionSynchronization.STATUS_ROLLED_BACK);

        assertThat(publisher.awaitIdle(PATIENCE)).isTrue();
        assertThat(log).containsExactly("audit:INC-1");
        assertThat(publisher.getPendingCount()).isZero();
    }

    @Test
    @DisplayName("Without a transaction the event goes straight to the worker")
    void noTransactionDeliversViaWorker() {
        publisher.publish(event("INC-1"));

        assertThat(publisher.awaitIdle(PATIENCE)).isTrue();
        assertThat(log).containsExactly("audit:INC-1", "notify:INC-1");
    }

    @Test
    @DisplayName("Deferred deliveries keep the publishing order across events")
    void deliveryOrderIsPreserved() {
        TransactionSynchronizationManager.initSynchronization();

        publisher.publish(event("INC-1"));
        publisher.publish(event("INC-2"));
        publisher.publish(event("INC-3"));
        TransactionSynchronizationUtils.triggerAfterCommit();
        publisher.awaitIdle(PATIENCE);

        assertThat(log).containsExactly("audit:INC-1", "audit:INC-2", "audit:INC-3", "notify:INC-1", "notify:INC-2", "notify:INC-3");
    }

    @Test
    @DisplayName("The deferred listener runs on the worker thread, never on the thread that still holds the connection")
    void deliveryNeverRunsOnThePublishingThread() {
        Recording notify = new Recording("probe", true, log);
        publisher.registerListener(notify);
        TransactionSynchronizationManager.initSynchronization();

        publisher.publish(event("INC-1"));
        TransactionSynchronizationUtils.triggerAfterCommit();
        publisher.awaitIdle(PATIENCE);

        assertThat(notify.threads).containsExactly("event-delivery");
        assertThat(Thread.currentThread().getName()).isNotEqualTo("event-delivery");
    }

    @Test
    @DisplayName("Each delivery opens its own transaction on the worker thread, never a nested one on the caller")
    void deliveryTransactionIsOpenedOnTheWorkerOnly() {
        TransactionSynchronizationManager.initSynchronization();
        publisher.publish(event("INC-1"));

        TransactionSynchronizationUtils.triggerAfterCommit();
        publisher.awaitIdle(PATIENCE);

        assertThat(transactionOpenedOn).containsExactly("event-delivery/" + TransactionDefinition.PROPAGATION_REQUIRED);
    }

    @Test
    @DisplayName("A failing deferred listener is logged and swallowed; the next one still runs")
    void deferredFailureIsIsolated() {
        publisher.registerListener(new DomainEventListener() {
            @Override
            public void onEvent(DomainEvent event) {
                throw new IllegalStateException("mail server down");
            }

            @Override
            public boolean deferUntilCommit() {
                return true;
            }
        });
        publisher.registerListener(new Recording("sms", true, log));
        TransactionSynchronizationManager.initSynchronization();
        publisher.publish(event("INC-1"));

        TransactionSynchronizationUtils.triggerAfterCommit();
        publisher.awaitIdle(PATIENCE);

        assertThat(log).contains("notify:INC-1", "sms:INC-1");
        assertThat(publisher.getPendingCount()).isZero();
    }

    @Test
    @DisplayName("A full queue drops the new event with a count instead of blocking the publisher")
    void fullQueueDropsInsteadOfBlocking() throws Exception {
        TransactionAwareDomainEventPublisher small = new TransactionAwareDomainEventPublisher(transactionManager, 1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        small.registerListener(new DomainEventListener() {
            @Override
            public void onEvent(DomainEvent event) {
                started.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                log.add("slow:" + event.incidentId());
            }

            @Override
            public boolean deferUntilCommit() {
                return true;
            }
        });
        try {
            small.publish(event("INC-1"));
            assertThat(started.await(5, TimeUnit.SECONDS)).as("worker picked up the first event").isTrue();
            small.publish(event("INC-2")); // fills the single queue slot
            small.publish(event("INC-3")); // nowhere to go

            assertThat(small.getDroppedCount()).isEqualTo(1);
            release.countDown();
            assertThat(small.awaitIdle(PATIENCE)).isTrue();
            assertThat(log).containsExactly("slow:INC-1", "slow:INC-2");
        } finally {
            release.countDown();
            small.close();
        }
    }

    @Test
    @DisplayName("Shutdown delivers what is already queued, then refuses new events with a count")
    void closeDrainsTheQueue() {
        publisher.registerListener(new Recording("late", true, log));
        for (int i = 1; i <= 5; i++) {
            publisher.publish(event("INC-" + i));
        }

        publisher.close();

        assertThat(log.stream().filter(entry -> entry.startsWith("notify:")).count()).isEqualTo(5);
        assertThat(log.stream().filter(entry -> entry.startsWith("late:")).count()).isEqualTo(5);
        publisher.publish(event("INC-6"));
        assertThat(publisher.getDroppedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("History still records every published event, committed or not")
    void historyRecordsAllEvents() {
        TransactionSynchronizationManager.initSynchronization();

        publisher.publish(event("INC-1"));

        assertThat(publisher.getPublishedHistory()).hasSize(1);
    }

    @Test
    @DisplayName("P4: the history keeps only the newest events up to its limit; zero keeps none (production)")
    void historyIsBounded() {
        TransactionAwareDomainEventPublisher bounded = new TransactionAwareDomainEventPublisher(transactionManager, 10, 2);
        TransactionAwareDomainEventPublisher none = new TransactionAwareDomainEventPublisher(transactionManager, 10, 0);
        try {
            for (int i = 1; i <= 5; i++) {
                bounded.publish(event("INC-" + i));
                none.publish(event("INC-" + i));
            }

            assertThat(bounded.getPublishedHistory()).extracting(DomainEvent::incidentId).containsExactly("INC-4", "INC-5");
            assertThat(none.getPublishedHistory()).isEmpty();
        } finally {
            bounded.close();
            none.close();
        }
    }

    @Test
    @DisplayName("A non-positive queue capacity is rejected")
    void invalidCapacityIsRejected() {
        assertThatThrownBy(() -> new TransactionAwareDomainEventPublisher(transactionManager, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
