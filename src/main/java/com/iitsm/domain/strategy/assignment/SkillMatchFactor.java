package com.iitsm.domain.strategy.assignment;

import com.iitsm.domain.model.Technician;
import java.util.Locale;

/**
 * Pattern: Strategy (an {@link AssignmentFactor}). How well the technician's skills match the ticket (ADR-044):
 * {@value #POINTS_PER_CATEGORY_SKILL} points per skill of the ticket's category plus the keyword points of
 * {@link SkillEvidence}, worth {@value #SCORE_PER_POINT} each, capped at 1 ({@value #FULL_MATCH_POINTS} points).
 */
public final class SkillMatchFactor implements AssignmentFactor {

    public static final String KEY = "SKILL_MATCH";
    /** Points a skill of the ticket's category is worth. */
    public static final int POINTS_PER_CATEGORY_SKILL = 2;
    /** Raw score per skill point. */
    public static final double SCORE_PER_POINT = 0.20;
    /** Points that give the full raw score of 1. */
    public static final int FULL_MATCH_POINTS = 5;

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String label() {
        return "Skills";
    }

    @Override
    public Reading read(AssignmentContext context, Technician technician) {
        SkillEvidence evidence = SkillEvidence.of(technician, context);
        int points = evidence.categorySkills() * POINTS_PER_CATEGORY_SKILL + evidence.keywordPoints();
        double raw = Math.min(1.0, points * SCORE_PER_POINT);
        String detail = String.format(Locale.ROOT, "%d of %d skill points (%d category skills, %d keyword points)",
                Math.min(points, FULL_MATCH_POINTS), FULL_MATCH_POINTS, evidence.categorySkills(), evidence.keywordPoints());
        return new Reading(raw, detail);
    }
}
