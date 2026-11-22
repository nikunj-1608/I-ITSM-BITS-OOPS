package com.iitsm.domain.state;

import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.IncidentStatus;
import java.time.Instant;
import java.util.Set;

// OOP (Realization): PendingInfoState implements IncidentState; each lifecycle status is its own class (State pattern), so no status switch is needed elsewhere.
/**
 * Pattern: State.
 * Represents an incident awaiting information from the reporting employee.
 * SLA clock is paused during this state.
 * Permitted action: provideInfo (resumes SLA clock, returns to IN_PROGRESS).
 */
public class PendingInfoState implements IncidentState {

    private static final Set<String> ALLOWED = Set.of("provideInfo");

    // OOP (Method overriding): replaces the interface's refusing default with the behaviour that is legal in this state.
    @Override
    public IncidentStatus getStatus() {
        return IncidentStatus.PENDING_INFO;
    }

    @Override
    public Set<String> allowedActions() {
        return ALLOWED;
    }

    @Override
    public void provideInfo(Incident incident, String employeeId, String responseText, Instant now) {
        if (incident.getSlaTracker() != null) {
            incident.getSlaTracker().resumeClock(now);
        }
        incident.transitionTo(IncidentStatus.IN_PROGRESS);
    }
}

