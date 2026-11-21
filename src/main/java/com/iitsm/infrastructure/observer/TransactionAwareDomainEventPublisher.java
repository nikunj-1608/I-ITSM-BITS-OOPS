package com.iitsm.infrastructure.observer;

import com.iitsm.domain.event.DomainEvent;
import com.iitsm.domain.event.DomainEventListener;
import com.iitsm.domain.event.DomainEventPublisher;
import com.iitsm.domain.event.InMemoryDomainEventPublisher;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Pattern: Observer / Decorator.
 * Event publisher that is aware of the surrounding transaction (ADR-017). Listeners that record state in the same
 * transaction (the audit trail) run immediately on the publishing thread, exactly as before. Listeners that declare
 * {@link DomainEventListener#deferUntilCommit()} (notifications) are handed to one dedicated worker thread, and only
 * after the publishing transaction has committed; a rolled-back transaction hands over nothing.
 *
 * <p>Why a separate thread: while the after-commit callbacks run, the request's own database connection is still
 * checked out. A listener that needs a connection of its own on that same thread would wait for a second connection
 * while holding the first; with N requests committing at once and a pool of N connections every connection is held
 * by a thread waiting for another, and the application stalls until the pool times out. The worker has no
 * transaction and holds nothing, and the request thread releases its connection as soon as it returns, so no wait
 * cycle can form: the worker needs at most one connection at a time and always finishes.
 *
 * <p>Delivery is in publishing order, bounded (a full queue drops the new event with a warning instead of blocking
 * a request), failures are logged and swallowed, and queued events are drained on shutdown.
 */
public class TransactionAwareDomainEventPublisher implements DomainEventPublisher, AutoCloseable {

    /** Default capacity of the delivery queue (events waiting for the worker). */
    public static final int DEFAULT_QUEUE_CAPACITY = 10_000;

    private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(10);
    private static final Duration IDLE_POLL = Duration.ofMillis(5);
    private static final Logger log = LoggerFactory.getLogger(TransactionAwareDomainEventPublisher.class);

    private final InMemoryDomainEventPublisher immediate;
    private final List<DomainEventListener> deferred = new CopyOnWriteArrayList<>();
    private final TransactionTemplate deliveryTransaction;
    private final ThreadPoolExecutor worker;
    private final AtomicInteger pending = new AtomicInteger();
    private final AtomicLong dropped = new AtomicLong();

    public TransactionAwareDomainEventPublisher(PlatformTransactionManager transactionManager) {
        this(transactionManager, DEFAULT_QUEUE_CAPACITY);
    }

    public TransactionAwareDomainEventPublisher(PlatformTransactionManager transactionManager, int queueCapacity) {
        this(transactionManager, queueCapacity, InMemoryDomainEventPublisher.DEFAULT_HISTORY_LIMIT);
    }

    /**
     * @param historyLimit how many of the newest events are kept for verification; zero keeps none (production,
     *     ADR-038), so the publisher holds no memory that grows with the number of ticket actions
     */
    public TransactionAwareDomainEventPublisher(PlatformTransactionManager transactionManager, int queueCapacity, int historyLimit) {
        Objects.requireNonNull(transactionManager, "transactionManager cannot be null");
        this.immediate = new InMemoryDomainEventPublisher(historyLimit);
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("Delivery queue capacity must be positive: " + queueCapacity);
        }
        // The worker thread has no transaction, so a plain template opens a fresh one per listener: nothing is
        // suspended and nothing is held while a connection is requested.
        this.deliveryTransaction = new TransactionTemplate(transactionManager);
        this.worker = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(queueCapacity),
                runnable -> {
                    Thread thread = new Thread(runnable, "event-delivery");
                    thread.setDaemon(true);
                    return thread;
                });
    }

    @Override
    public void registerListener(DomainEventListener listener) {
        Objects.requireNonNull(listener, "listener cannot be null");
        if (listener.deferUntilCommit()) {
            deferred.add(listener);
        } else {
            immediate.registerListener(listener);
        }
    }

    @Override
    public void publish(DomainEvent event) {
        Objects.requireNonNull(event, "event cannot be null");
        immediate.publish(event);
        if (deferred.isEmpty()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    enqueue(event);
                }
            });
        } else {
            enqueue(event);
        }
    }

    /** The newest events published (at most the history limit), including those of transactions that later rolled back (a verification aid). */
    public List<DomainEvent> getPublishedHistory() {
        return immediate.getPublishedHistory();
    }

    /** Events handed to the worker and not yet fully delivered. */
    public int getPendingCount() {
        return pending.get();
    }

    /** Events discarded because the delivery queue was full or the publisher was shutting down. */
    public long getDroppedCount() {
        return dropped.get();
    }

    /** Waits until everything handed to the worker so far has been delivered; used by tests and on shutdown. */
    public boolean awaitIdle(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (pending.get() > 0) {
            if (System.nanoTime() >= deadline) {
                return false;
            }
            try {
                Thread.sleep(IDLE_POLL.toMillis());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /** Stops accepting events and delivers what is already queued, for at most the shutdown grace period. */
    @Override
    public void close() {
        worker.shutdown();
        try {
            if (!worker.awaitTermination(SHUTDOWN_GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("Event delivery did not finish within {}; discarding {} queued event(s)", SHUTDOWN_GRACE, pending.get());
                worker.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            worker.shutdownNow();
        }
    }

    private void enqueue(DomainEvent event) {
        pending.incrementAndGet();
        try {
            worker.execute(() -> deliver(event));
        } catch (RejectedExecutionException ex) {
            pending.decrementAndGet();
            dropped.incrementAndGet();
            log.warn("Event {} not delivered to deferred listeners: delivery queue is full or shutting down", event.eventId());
        }
    }

    private void deliver(DomainEvent event) {
        try {
            for (DomainEventListener listener : deferred) {
                try {
                    deliveryTransaction.executeWithoutResult(status -> listener.onEvent(event));
                } catch (RuntimeException ex) {
                    log.error("Deferred listener {} failed for event {}: {}",
                            listener.getClass().getSimpleName(), event.eventId(), ex.getMessage(), ex);
                }
            }
        } finally {
            pending.decrementAndGet();
        }
    }
}
