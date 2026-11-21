package com.iitsm.domain.event;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Pattern: Observer.
 * In-memory implementation of DomainEventPublisher.
 * Distributes events synchronously or asynchronously to registered DomainEventListeners.
 *
 * <p>The publisher also keeps the most recent events as a verification aid (tests, the lifecycle demo). That history is
 * a <b>bounded buffer</b> (ADR-038, stress-audit item P4): it used to be a list that only grew, so a long-running
 * instance leaked memory with every ticket action. A limit of zero keeps nothing; production runs with zero.
 */
public class InMemoryDomainEventPublisher implements DomainEventPublisher {

    /** History size used when none is configured (tests and the demo). */
    public static final int DEFAULT_HISTORY_LIMIT = 1000;

    // CopyOnWriteArrayList chosen for thread-safe iteration without lock contention during event dispatch
    private final List<DomainEventListener> listeners = new CopyOnWriteArrayList<>();

    // The newest events, oldest first; every access is guarded by the deque's own monitor
    private final Deque<DomainEvent> publishedHistory = new ArrayDeque<>();
    private final int historyLimit;

    public InMemoryDomainEventPublisher() {
        this(DEFAULT_HISTORY_LIMIT);
    }

    public InMemoryDomainEventPublisher(int historyLimit) {
        if (historyLimit < 0) {
            throw new IllegalArgumentException("History limit cannot be negative: " + historyLimit);
        }
        this.historyLimit = historyLimit;
    }

    @Override
    public void publish(DomainEvent event) {
        Objects.requireNonNull(event, "event cannot be null");
        remember(event);
        for (DomainEventListener listener : listeners) {
            listener.onEvent(event);
        }
    }

    private void remember(DomainEvent event) {
        if (historyLimit == 0) {
            return;
        }
        synchronized (publishedHistory) {
            publishedHistory.addLast(event);
            while (publishedHistory.size() > historyLimit) {
                publishedHistory.pollFirst();
            }
        }
    }

    @Override
    public void registerListener(DomainEventListener listener) {
        Objects.requireNonNull(listener, "listener cannot be null");
        listeners.add(listener);
    }

    /** A snapshot of the newest events (at most the history limit), oldest first. */
    public List<DomainEvent> getPublishedHistory() {
        synchronized (publishedHistory) {
            return List.copyOf(publishedHistory);
        }
    }

    public void clearHistory() {
        synchronized (publishedHistory) {
            publishedHistory.clear();
        }
    }
}
