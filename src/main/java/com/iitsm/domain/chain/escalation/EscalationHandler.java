package com.iitsm.domain.chain.escalation;

import com.iitsm.domain.model.Incident;
import java.util.Optional;

// OOP (Interface): clients depend on this contract, not on a particular tier class.
/**
 * Pattern: Chain of Responsibility.
 * Strategy/Handler interface for multi-tier incident escalation.
 */
public interface EscalationHandler {

    Optional<EscalationAction> handle(Incident incident, EscalationContext context);

    void setNext(EscalationHandler next);

    EscalationHandler getNext();
}

