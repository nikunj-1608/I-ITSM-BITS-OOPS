package com.iitsm.domain.state;

import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.IncidentStatus;
import java.time.Instant;
import java.util.Set;

// OOP (Realization): ClassifiedState implements IncidentState; each lifecycle status is its own class (State pattern), so no status switch is needed elsewhere.
/**
 * Pattern: State.
 * Represents an incident that has been categorized and prioritized.
 * Permitted action: assign (moves to ASSIGNED).
 */
public class ClassifiedState implements IncidentState {

    private static final Set<String> ALLOWED = Set.of("assign");

    // OOP (Method overriding): replaces the interface's refusing default with the behaviour that is legal in this state.
    @Override
    public IncidentStatus getStatus() {
        return IncidentStatus.CLASSIFIED;
    }

    @Override
    public Set<String> allowedActions() {
        return ALLOWED;
    }

    @Override
    public void assign(Incident incident, String technicianId, Instant now) {
        incident.setAssignee(technicianId);
        incident.transitionTo(IncidentStatus.ASSIGNED);
    }
}

