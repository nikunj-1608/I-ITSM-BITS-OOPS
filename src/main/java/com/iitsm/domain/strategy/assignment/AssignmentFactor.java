package com.iitsm.domain.strategy.assignment;

import com.iitsm.domain.model.Technician;

/**
 * Pattern: Strategy. One measurable reason to prefer a technician for a ticket (ADR-044), like {@code PriorityFactor}
 * for priorities. {@link WeightedCompositeStrategy} combines a list of factors with weights per priority; adding a
 * factor means writing a new class and registering it with its weights, without editing the existing factors.
 */
public interface AssignmentFactor {

    /** Stable key of the factor, e.g. {@code SKILL_MATCH}; used to look up its weight and stored with explanations. */
    String key();

    /** Short label used in the strategy's one-line rationale, e.g. {@code Skills}. */
    String label();

    /** Reads the factor for one technician: a raw score in 0..1 and a short human detail. */
    Reading read(AssignmentContext context, Technician technician);

    /**
     * OOP: Immutable value object. A factor's raw score for one technician and its human detail.
     */
    record Reading(double rawScore, String detail) {
    }
}
