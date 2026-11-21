package com.iitsm.domain.strategy.assignment;

import com.iitsm.domain.exception.TechnicianUnavailableException;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.Technician;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

// OOP (Polymorphism): SkillBasedStrategy is interchangeable with every other AssignmentStrategy; the registry picks one by name at run time.
/**
 * Pattern: Strategy.
 * Routes incidents to the technician with highest technical skill overlap against category and keywords.
 *
 * <p>Score (ADR-044, unchanged): skill score = {@value #SCORE_PER_CATEGORY_SKILL} per skill of the ticket's category
 * plus {@value #SCORE_PER_KEYWORD_POINT} per keyword point ({@link SkillEvidence}), capped at 1, minus a small load
 * penalty of {@value #LOAD_PENALTY_WEIGHT} x load ratio, never below 0. The penalty shows in the explanation as the
 * factor {@code LOAD_PENALTY} with a negative weight. Ties keep the first candidate.
 */
public class SkillBasedStrategy implements AssignmentStrategy {

    // OOP (static final): the strategy's registry name as a constant, never a repeated literal.
    public static final String STRATEGY_NAME = "SKILL_BASED";
    public static final String LOAD_PENALTY_KEY = "LOAD_PENALTY";
    /** Skill score per skill of the ticket's category. */
    public static final double SCORE_PER_CATEGORY_SKILL = 0.30;
    /** Skill score per keyword point. */
    public static final double SCORE_PER_KEYWORD_POINT = 0.15;
    /** How much a full load lowers the score (prefer the less loaded of similar technicians). */
    public static final double LOAD_PENALTY_WEIGHT = 0.10;

    // OOP (Method overriding): this strategy's own answer to recommend(...); callers only see the interface type.
    @Override
    public AssignmentRecommendation recommend(Incident incident, List<Technician> candidates) {
        RankedCandidates ranking = score(incident, candidates);
        RankedCandidates.Entry top = ranking.top();
        SkillEvidence evidence = SkillEvidence.of(top.technician(), AssignmentContext.of(incident));
        String rationale = String.format("Selected for high skill alignment: %d category skills, %d keyword matches (score: %.2f)",
                evidence.categorySkills(), evidence.keywordPoints(), top.factorScores().get(0).rawScore());
        return ranking.toRecommendation(STRATEGY_NAME, rationale);
    }

    @Override
    public List<CandidateScore> rank(Incident incident, List<Technician> candidates) {
        return score(incident, candidates).scores();
    }

    private RankedCandidates score(Incident incident, List<Technician> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            throw new TechnicianUnavailableException("No available technicians found for skill-based assignment");
        }
        AssignmentContext context = AssignmentContext.of(incident);
        List<RankedCandidates.Entry> entries = new ArrayList<>(candidates.size());
        for (Technician technician : candidates) {
            entries.add(scoreOne(context, technician));
        }
        return RankedCandidates.rank(entries, Comparator.comparingDouble(RankedCandidates.Entry::total).reversed());
    }

    private RankedCandidates.Entry scoreOne(AssignmentContext context, Technician technician) {
        SkillEvidence evidence = SkillEvidence.of(technician, context);
        double skillScore = Math.min(1.0, (evidence.categorySkills() * SCORE_PER_CATEGORY_SKILL)
                + (evidence.keywordPoints() * SCORE_PER_KEYWORD_POINT));
        FactorScore skill = FactorScore.of(SkillMatchFactor.KEY, skillScore, 1.0, String.format(Locale.ROOT,
                "%d category skills, %d keyword points", evidence.categorySkills(), evidence.keywordPoints()));
        FactorScore penalty = FactorScore.of(LOAD_PENALTY_KEY, technician.getLoadRatio(), -LOAD_PENALTY_WEIGHT,
                LoadFactor.activeTickets(technician));
        double total = Math.max(0.0, skill.contribution() + penalty.contribution());
        return new RankedCandidates.Entry(technician, List.of(skill, penalty), total);
    }

    @Override
    public String getStrategyName() {
        return STRATEGY_NAME;
    }

    @Override
    public String getDescription() {
        return "Routes incidents to technicians whose skill profile best matches the incident category and keywords.";
    }
}
