package com.iitsm.domain.event;

// OOP (Interface / Polymorphism): any class can listen to events; the publisher calls them all through this one type.
/**
 * Pattern: Observer.
 * Consumer interface reacting to domain lifecycle events.
 */
@FunctionalInterface
public interface DomainEventListener {

    /**
     * Handles the published domain event.
     *
     * @param event strongly-typed domain event
     */
    void onEvent(DomainEvent event);

    // OOP (Default method): existing listeners keep working; a listener overrides it only when it needs after-commit delivery.
    /**
     * Whether this listener must only run once the publishing transaction has committed (ADR-017). Listeners with
     * side effects the outside world can see (notifications) return {@code true}, so a change that rolls back
     * never announces itself; listeners that record state in the same transaction (audit) keep the default.
     */
    default boolean deferUntilCommit() {
        return false;
    }
}

