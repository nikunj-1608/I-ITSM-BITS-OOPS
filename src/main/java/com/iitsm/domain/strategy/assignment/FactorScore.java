package com.iitsm.domain.strategy.assignment;

import java.util.Objects;

/**
 * OOP: Immutable value object. One factor's part in a technician's assignment score (ADR-044): the raw score (normally
 * 0..1), the weight it carries in the strategy, the weighted contribution ({@code rawScore x weight}) and a short human
 * detail such as "3 of 5 skill points" or "2/5 tickets active".
 *
 * <p>A weight of 0 marks a factor that only breaks ties; a negative weight marks a penalty (the skill-based strategy's
 * load penalty). The contribution is always {@code rawScore * weight}, computed here, so it cannot disagree with them.
 */
public record FactorScore(String factorKey, double rawScore, double weight, double contribution, String detail) {

    public FactorScore {
        if (factorKey == null || factorKey.isBlank()) {
            throw new IllegalArgumentException("factorKey must not be blank");
        }
        requireFinite(rawScore, "rawScore");
        requireFinite(weight, "weight");
        requireFinite(contribution, "contribution");
        detail = Objects.requireNonNullElse(detail, "");
    }

    /** A factor score whose contribution is computed from the raw score and the weight. */
    public static FactorScore of(String factorKey, double rawScore, double weight, String detail) {
        return new FactorScore(factorKey, rawScore, weight, rawScore * weight, detail);
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be a finite number");
        }
    }
}
