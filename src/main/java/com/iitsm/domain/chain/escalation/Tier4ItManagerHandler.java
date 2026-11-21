package com.iitsm.domain.chain.escalation;

import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.Priority;
import com.iitsm.domain.model.SlaStatus;
import java.util.Optional;

// OOP (Inheritance): Tier4ItManagerHandler extends the abstract handler and only supplies its own tier rule (Chain of Responsibility).
/**
 * Pattern: Chain of Responsibility (Tier 4 Handler).
 * Handles catastrophic P1 breaches on Mission Critical IT services, directly alerting executive IT Leadership.
 */
public class Tier4ItManagerHandler extends AbstractEscalationHandler {

    // OOP (Method overriding): implements handle(...) from EscalationHandler; if this tier does not apply, it forwards to the next handler.
    @Override
    public Optional<EscalationAction> handle(Incident incident, EscalationContext context) {
        if (incident.getSlaTracker() != null) {
            SlaStatus status = incident.getSlaTracker().getStatus();
            boolean isP1 = incident.getPriority() == Priority.P1;

            if (status == SlaStatus.BREACHED && isP1 && context.isMissionCriticalService()) {
                return Optional.of(new EscalationAction(
                        4,
                        "IT_MANAGER_BREACH",
                        context.itManagerId(),
                        "IT_MANAGER",
                        "Catastrophic SLA breach on P1 Mission-Critical service " + incident.getServiceId()
                ));
            }
        }
        return forward(incident, context);
    }
}

