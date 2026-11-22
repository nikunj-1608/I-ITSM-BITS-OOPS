package com.iitsm.domain.state;

import com.iitsm.domain.exception.InvalidStateTransitionException;
import com.iitsm.domain.model.Feedback;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.IncidentStatus;
import com.iitsm.domain.model.PriorityDecision;
import com.iitsm.domain.model.Resolution;
import java.time.Instant;
import java.util.Set;

// OOP (Interface / Abstraction): defines WHAT a state can do; each concrete state decides HOW. Callers depend on this interface only.
/**
 * Pattern: State.
 * Encapsulates state-specific behavior and transition invariants for an Incident.
 * Default methods reject illegal actions by throwing InvalidStateTransitionException.
 */
public interface IncidentState {

    IncidentStatus getStatus();

    Set<String> allowedActions();

    // OOP (Default methods): every action refuses by default, so a state class overrides only the actions it allows.
    default void classify(
            Incident incident,
            IncidentCategory category,
            String serviceId,
            PriorityDecision priorityDecision,
            Instant now) {
        throw new InvalidStateTransitionException(getStatus(), "classify", allowedActions());
    }

    default void assign(Incident incident, String technicianId, Instant now) {
        throw new InvalidStateTransitionException(getStatus(), "assign", allowedActions());
    }

    default void acknowledge(Incident incident, String technicianId, Instant now) {
        throw new InvalidStateTransitionException(getStatus(), "acknowledge", allowedActions());
    }

    default void startInvestigation(Incident incident, String technicianId, Instant now) {
        throw new InvalidStateTransitionException(getStatus(), "startInvestigation", allowedActions());
    }

    default void requestInfo(Incident incident, String technicianId, String message, Instant now) {
        throw new InvalidStateTransitionException(getStatus(), "requestInfo", allowedActions());
    }

    default void provideInfo(Incident incident, String employeeId, String responseText, Instant now) {
        throw new InvalidStateTransitionException(getStatus(), "provideInfo", allowedActions());
    }

    default void resolve(Incident incident, Resolution resolution, Instant now) {
        throw new InvalidStateTransitionException(getStatus(), "resolve", allowedActions());
    }

    default void verify(Incident incident, String employeeId, Instant now) {
        throw new InvalidStateTransitionException(getStatus(), "verify", allowedActions());
    }

    default void reject(Incident incident, String employeeId, String reason, Instant now) {
        throw new InvalidStateTransitionException(getStatus(), "reject", allowedActions());
    }

    default void close(Incident incident, String actorId, Feedback feedback, Instant now) {
        throw new InvalidStateTransitionException(getStatus(), "close", allowedActions());
    }

    default void flagWrongDomain(Incident incident, String technicianId, String reason, Instant now) {
        throw new InvalidStateTransitionException(getStatus(), "flagWrongDomain", allowedActions());
    }

    // OOP (Polymorphism): finished states override this and answer false; no status check is needed in the service.
    /**
     * Whether an administrator may override the ticket's priority in this state (ADR-028). True while the ticket is
     * open; false once it is resolved, because its SLA verdict is final and a new priority could no longer change it.
     */
    default boolean allowsPriorityOverride() {
        return true;
    }
}

