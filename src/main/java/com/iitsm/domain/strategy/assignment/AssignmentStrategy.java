package com.iitsm.domain.strategy.assignment;

import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.Technician;
import java.util.List;

/**
 * Pattern: Strategy.
 * Interface defining swappable technician assignment strategies.
 *
 * <p>Explainable ranking (ADR-044): {@link #rank} scores every candidate without changing the strategy's state, and
 * {@link #recommend} chooses the top of that same ranking and carries it in
 * {@link AssignmentRecommendation#ranking()}, so the explanation of a real assignment is computed once, with the state
 * that made the choice. The five built-in strategies override {@link #rank}.
 */
public interface AssignmentStrategy {

    /**
     * Recommends the optimal technician for the given incident among eligible candidates.
     */
    AssignmentRecommendation recommend(Incident incident, List<Technician> candidates);

    /**
     * Scores every candidate, best first; rank 1 is the technician {@link #recommend} would choose now. Has no side
     * effect: asking for an explanation never advances a rotation.
     *
     * <p>The default serves strategies written before ADR-044: it returns the ranking their recommendation carries,
     * or the chosen technician alone with its final score. It calls {@link #recommend}, so a strategy with state must
     * override this method.
     */
    default List<CandidateScore> rank(Incident incident, List<Technician> candidates) {
        AssignmentRecommendation recommendation = recommend(incident, candidates);
        if (!recommendation.ranking().isEmpty()) {
            return recommendation.ranking();
        }
        return List.of(CandidateScore.opaque(recommendation.technician().getId(), recommendation.score()));
    }

    /**
     * An instance with its own state, for a replay that must not disturb the live one (the offline comparison). A
     * stateless strategy returns itself; a strategy with state (round robin) returns a new instance.
     */
    default AssignmentStrategy freshInstance() {
        return this;
    }

    /**
     * Unique key identifying this assignment strategy.
     */
    String getStrategyName();

    /**
     * Human-readable description of how this strategy routes tickets.
     */
    String getDescription();
}
