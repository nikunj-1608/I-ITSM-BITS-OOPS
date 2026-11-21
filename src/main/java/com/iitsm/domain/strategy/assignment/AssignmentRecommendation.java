package com.iitsm.domain.strategy.assignment;

import com.iitsm.domain.model.Technician;
import java.util.List;
import java.util.Objects;

/**
 * OOP: Value object encapsulating the result of an automated technician assignment recommendation.
 *
 * <p>{@code ranking} is the strategy's explanation (ADR-044): every candidate with its factor scores, best first; its
 * rank-1 entry is the recommended technician. It is empty for a recommendation built without an explanation (the
 * four-argument constructor, kept for existing callers and custom strategies).
 */
public record AssignmentRecommendation(
        Technician technician,
        double score,
        String strategyName,
        String rationale,
        List<CandidateScore> ranking) {

    public AssignmentRecommendation {
        Objects.requireNonNull(technician, "technician cannot be null");
        Objects.requireNonNull(strategyName, "strategyName cannot be null");
        score = Math.max(0.0, Math.min(1.0, score));
        ranking = (ranking != null) ? List.copyOf(ranking) : List.of();
    }

    /** A recommendation without an explanation. */
    public AssignmentRecommendation(Technician technician, double score, String strategyName, String rationale) {
        this(technician, score, strategyName, rationale, List.of());
    }
}
