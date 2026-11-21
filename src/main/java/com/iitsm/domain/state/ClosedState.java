package com.iitsm.domain.state;

import com.iitsm.domain.model.IncidentStatus;
import java.util.Collections;
import java.util.Set;

// OOP (Realization): ClosedState implements IncidentState; each lifecycle status is its own class (State pattern), so no status switch is needed elsewhere.
/**
 * Pattern: State.
 * Represents an incident permanently closed in the system.
 * Terminal state: strictly no further state transitions permitted.
 */
public class ClosedState implements IncidentState {

    private static final Set<String> ALLOWED = Collections.emptySet();

    // OOP (Method overriding): replaces the interface's refusing default with the behaviour that is legal in this state.
    @Override
    public IncidentStatus getStatus() {
        return IncidentStatus.CLOSED;
    }

    @Override
    public Set<String> allowedActions() {
        return ALLOWED;
    }

    @Override
    public boolean allowsPriorityOverride() {
        return false;
    }
}

