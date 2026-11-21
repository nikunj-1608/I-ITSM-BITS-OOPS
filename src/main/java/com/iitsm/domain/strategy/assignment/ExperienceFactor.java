package com.iitsm.domain.strategy.assignment;

import com.iitsm.domain.model.Technician;
import java.util.Locale;

// OOP (final class + interface): ExperienceFactor is one small, immutable scoring part; the composite strategy combines factors it HAS (composition).
/**
 * Pattern: Strategy (an {@link AssignmentFactor}). The technician's years of experience, scaled so that
 * {@code fullScoreYears} years give the full raw score of 1 (ADR-044).
 *
 * <p>The two strategies that use experience scale it differently, as they always did: the weighted composite reaches
 * the full score at {@value #COMPOSITE_FULL_SCORE_YEARS} years, the experience-based strategy at
 * {@value #EXPERIENCE_STRATEGY_FULL_SCORE_YEARS}.
 */
public final class ExperienceFactor implements AssignmentFactor {

    public static final String KEY = "EXPERIENCE";
    /** Years that give the full score in the weighted composite strategy. */
    public static final int COMPOSITE_FULL_SCORE_YEARS = 12;
    /** Years that give the full score in the experience-based strategy. */
    public static final int EXPERIENCE_STRATEGY_FULL_SCORE_YEARS = 15;

    private final int fullScoreYears;

    public ExperienceFactor(int fullScoreYears) {
        if (fullScoreYears <= 0) {
            throw new IllegalArgumentException("fullScoreYears must be positive");
        }
        this.fullScoreYears = fullScoreYears;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String label() {
        return "Exp";
    }

    @Override
    public Reading read(AssignmentContext context, Technician technician) {
        double raw = Math.min(1.0, technician.getExperienceYears() / (double) fullScoreYears);
        String detail = String.format(Locale.ROOT, "%d years (full score at %d)",
                technician.getExperienceYears(), fullScoreYears);
        return new Reading(raw, detail);
    }
}
