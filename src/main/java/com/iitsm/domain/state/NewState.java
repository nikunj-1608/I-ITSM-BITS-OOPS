package com.iitsm.domain.state;

import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.IncidentStatus;
import com.iitsm.domain.model.PriorityDecision;
import java.time.Instant;
import java.util.Set;

// OOP (Realization): NewState implements IncidentState; each lifecycle status is its own class (State pattern), so no status switch is needed elsewhere.
/**
 * Pattern: State.
 * Represents an incident freshly submitted by an employee.
 * Permitted action: classify (moves to CLASSIFIED).
 */
public class NewState implements IncidentState {

    private static final Set<String> ALLOWED = Set.of("classify");

    // OOP (Method overriding): replaces the interface's refusing default with the behaviour that is legal in this state.
    @Override
    public IncidentStatus getStatus() {
        return IncidentStatus.NEW;
    }

    @Override
    public Set<String> allowedActions() {
        return ALLOWED;
    }

    @Override
    public void classify(
            Incident incident,
            IncidentCategory category,
            String serviceId,
            PriorityDecision priorityDecision,
            Instant now) {
        incident.setClassificationData(category, serviceId, priorityDecision);
        incident.transitionTo(IncidentStatus.CLASSIFIED);
    }
}

