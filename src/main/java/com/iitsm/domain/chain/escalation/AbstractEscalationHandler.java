package com.iitsm.domain.chain.escalation;

import com.iitsm.domain.model.Incident;
import java.util.Optional;

// OOP (Abstract class): holds the shared link to the next handler; subclasses supply only the handling rule.
/**
 * Pattern: Chain of Responsibility (Abstract Base Handler).
 * Handles boilerplate chaining and traversal logic for escalation handlers.
 */
public abstract class AbstractEscalationHandler implements EscalationHandler {

    // OOP (Association): each handler knows the next one; protected so subclasses can read it, outsiders cannot.
    protected EscalationHandler next;

    @Override
    public void setNext(EscalationHandler next) {
        this.next = next;
    }

    @Override
    public EscalationHandler getNext() {
        return next;
    }

    // OOP (Template idea): reusable step that subclasses call when they do not handle the case themselves.
    protected Optional<EscalationAction> forward(Incident incident, EscalationContext context) {
        if (next != null) {
            return next.handle(incident, context);
        }
        return Optional.empty();
    }
}

