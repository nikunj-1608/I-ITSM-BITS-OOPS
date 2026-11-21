package com.iitsm.domain.strategy.assignment;

import java.util.List;
import java.util.Objects;

/**
 * OOP: Immutable value object. How one candidate technician scored under an assignment strategy (ADR-044): the factor
 * scores, the total the strategy ranks by, the rank (1 = best) and whether this candidate was chosen.
 *
 * <p>Exactly the rank-1 candidate is chosen. The total is the strategy's own score; it equals the sum of the
 * contributions for the weighted strategies, and it can differ where a strategy clamps its score (skill-based never
 * goes below 0) or ranks by a tie-break that carries weight 0.
 */
public record CandidateScore(String technicianId, List<FactorScore> factorScores, double total, int rank, boolean chosen) {

    /** Factor key used when a strategy only reports its final score, not a breakdown. */
    public static final String STRATEGY_SCORE_KEY = "STRATEGY_SCORE";

    public CandidateScore {
        if (technicianId == null || technicianId.isBlank()) {
            throw new IllegalArgumentException("technicianId must not be blank");
        }
        factorScores = List.copyOf(Objects.requireNonNull(factorScores, "factorScores cannot be null"));
        if (!Double.isFinite(total)) {
            throw new IllegalArgumentException("total must be a finite number");
        }
        if (rank < 1) {
            throw new IllegalArgumentException("rank starts at 1");
        }
        if (chosen != (rank == 1)) {
            throw new IllegalArgumentException("exactly the rank-1 candidate is the chosen one");
        }
    }

    /** The chosen candidate of a strategy that gives no breakdown: one factor holding its final score. */
    public static CandidateScore opaque(String technicianId, double score) {
        return new CandidateScore(technicianId,
                List.of(FactorScore.of(STRATEGY_SCORE_KEY, score, 1.0, "score reported by the strategy")),
                score, 1, true);
    }
}
