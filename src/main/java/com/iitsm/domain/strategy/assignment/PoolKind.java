package com.iitsm.domain.strategy.assignment;

/**
 * OOP: Enumeration. Where the technician of an assignment decision came from (ADR-044, Phase L2).
 *
 * <p>Two kinds are a strategy's choice among routed candidates (ADR-036): the owning team's technicians, or skilled
 * technicians of other teams when the owning team had nobody free. The other three set an assignee without a strategy:
 * an administrator's manual assignment, a confident duplicate that rides its parent's technician (F4, no slot), and the
 * administrator rejecting a wrong-domain request, which returns the ticket to the technician who flagged it.
 */
public enum PoolKind {
    OWNING_TEAM(true, "Owning team"),
    SKILLED_OVERFLOW(true, "Skilled technicians of other teams"),
    MANUAL(false, "Manual assignment"),
    INHERITED_FROM_PARENT(false, "Inherited from the parent ticket"),
    REQUEUED_TO_SAME_TECHNICIAN(false, "Returned to the same technician");

    private final boolean rankedByStrategy;
    private final String label;

    PoolKind(boolean rankedByStrategy, String label) {
        this.rankedByStrategy = rankedByStrategy;
        this.label = label;
    }

    /** Whether a strategy ranked candidates for this decision (so the decision carries a ranking). */
    public boolean isRankedByStrategy() {
        return rankedByStrategy;
    }

    /** Short human label. */
    public String getLabel() {
        return label;
    }
}
