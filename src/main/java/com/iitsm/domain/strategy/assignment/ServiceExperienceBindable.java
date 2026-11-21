package com.iitsm.domain.strategy.assignment;

/**
 * Pattern: Strategy (optional capability). An assignment strategy that reads service experience and can be re-bound to
 * another {@link ServiceExperienceSource} (ADR-044, Phase L4). The offline comparison binds such a strategy to its own
 * in-memory source before a replay, so a strategy wired to the real database never reads it during a simulation.
 */
public interface ServiceExperienceBindable {

    /** A copy of this strategy (same factors, weights and name) reading its experience from {@code source}. */
    AssignmentStrategy bindExperience(ServiceExperienceSource source);
}
