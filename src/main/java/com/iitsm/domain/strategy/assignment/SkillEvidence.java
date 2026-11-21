package com.iitsm.domain.strategy.assignment;

import com.iitsm.domain.model.Skill;
import com.iitsm.domain.model.Technician;
import java.util.Locale;

/**
 * OOP: Immutable value object. How a technician's skills match a ticket (ADR-044), counted the way both skill-aware
 * strategies always counted it: one per skill of the ticket's category, and for each skill {@value #FULL_NAME_POINTS}
 * keyword points when its full name appears in the ticket text, otherwise {@value #TOKEN_POINTS} point for every ticket
 * token contained in the skill name.
 */
public record SkillEvidence(int categorySkills, int keywordPoints) {

    /** Keyword points for a skill whose full name appears in the ticket text. */
    public static final int FULL_NAME_POINTS = 2;
    /** Keyword points for each ticket token found inside a skill name. */
    public static final int TOKEN_POINTS = 1;

    /** Counts the technician's skill evidence for the ticket. */
    public static SkillEvidence of(Technician technician, AssignmentContext context) {
        int categorySkills = 0;
        int keywordPoints = 0;
        for (Skill skill : technician.getSkills()) {
            if (context.category() != null && skill.category() == context.category()) {
                categorySkills++;
            }
            keywordPoints += keywordPoints(skill, context);
        }
        return new SkillEvidence(categorySkills, keywordPoints);
    }

    private static int keywordPoints(Skill skill, AssignmentContext context) {
        String skillName = skill.name().toLowerCase(Locale.ROOT);
        if (context.fullText().contains(skillName)) {
            return FULL_NAME_POINTS;
        }
        int points = 0;
        for (String token : context.tokens()) {
            if (skillName.contains(token)) {
                points += TOKEN_POINTS;
            }
        }
        return points;
    }
}
