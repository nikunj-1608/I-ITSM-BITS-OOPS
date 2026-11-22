package com.iitsm.domain.state;

import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.IncidentStatus;
import java.time.Instant;
import java.util.Set;

// OOP (Realization): ResolvedState implements IncidentState; each lifecycle status is its own class (State pattern), so no status switch is needed elsewhere.
/**
 * Pattern: State.
 * Represents an incident resolved by a technician and awaiting employee verification.
 * Permitted actions:
 * - verify (moves to VERIFIED)
 * - reject (reopens incident, increments reopenCount, returns to IN_PROGRESS, SLA clock runs again)
 */
public class ResolvedState implements IncidentState {

    private static final Set<String> ALLOWED = Set.of("verify", "reject");

    // OOP (Method overriding): replaces the interface's refusing default with the behaviour that is legal in this state.
    @Override
    public IncidentStatus getStatus() {
        return IncidentStatus.RESOLVED;
    }

    @Override
    public Set<String> allowedActions() {
        return ALLOWED;
    }

    @Override
    public boolean allowsPriorityOverride() {
        return false;
    }

    @Override
    public void verify(Incident incident, String employeeId, Instant now) {
        incident.transitionTo(IncidentStatus.VERIFIED);
    }

    @Override
    public void reject(Incident incident, String employeeId, String reason, Instant now) {
        if (incident.getSlaTracker() != null) {
            incident.getSlaTracker().reopen(now);
        }
        incident.incrementReopenCount();
        incident.transitionTo(IncidentStatus.IN_PROGRESS);
    }
}

