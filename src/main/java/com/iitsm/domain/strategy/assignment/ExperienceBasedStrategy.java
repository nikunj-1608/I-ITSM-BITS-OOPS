package com.iitsm.domain.strategy.assignment;

import com.iitsm.domain.exception.TechnicianUnavailableException;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.Technician;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Pattern: Strategy.
 * Routes incidents to the technician with the highest years of domain experience.
 *
 * <p>Ranking (ADR-044, unchanged): most experience first, then the lower load ratio, then input order. The score is
 * experience scaled to {@value ExperienceFactor#EXPERIENCE_STRATEGY_FULL_SCORE_YEARS} years; load appears in the
 * explanation with weight 0 because it only breaks ties.
 */
public class ExperienceBasedStrategy implements AssignmentStrategy {

    public static final String STRATEGY_NAME = "EXPERIENCE_BASED";

    private static final Comparator<RankedCandidates.Entry> MOST_EXPERIENCED_FIRST =
            Comparator.comparingInt((RankedCandidates.Entry e) -> e.technician().getExperienceYears())
                    .thenComparing(Comparator.comparingDouble(
                            (RankedCandidates.Entry e) -> e.technician().getLoadRatio()).reversed())
                    .reversed();

    private final ExperienceFactor experience =
            new ExperienceFactor(ExperienceFactor.EXPERIENCE_STRATEGY_FULL_SCORE_YEARS);
    private final LoadFactor load = new LoadFactor();

    @Override
    public AssignmentRecommendation recommend(Incident incident, List<Technician> candidates) {
        RankedCandidates ranking = score(incident, candidates);
        Technician selected = ranking.top().technician();
        String rationale = String.format("Selected for highest experience: %d years domain expertise (load: %.2f)",
                selected.getExperienceYears(), selected.getLoadRatio());
        return ranking.toRecommendation(STRATEGY_NAME, rationale);
    }

    @Override
    public List<CandidateScore> rank(Incident incident, List<Technician> candidates) {
        return score(incident, candidates).scores();
    }

    private RankedCandidates score(Incident incident, List<Technician> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            throw new TechnicianUnavailableException("No available technicians found for experience-based assignment");
        }
        AssignmentContext context = AssignmentContext.of(incident);
        List<RankedCandidates.Entry> entries = new ArrayList<>(candidates.size());
        for (Technician technician : candidates) {
            AssignmentFactor.Reading years = experience.read(context, technician);
            AssignmentFactor.Reading free = load.read(context, technician);
            List<FactorScore> scores = List.of(
                    FactorScore.of(ExperienceFactor.KEY, years.rawScore(), 1.0, years.detail()),
                    FactorScore.of(LoadFactor.KEY, free.rawScore(), 0.0, "tie-break: " + free.detail()));
            entries.add(new RankedCandidates.Entry(technician, scores, years.rawScore()));
        }
        return RankedCandidates.rank(entries, MOST_EXPERIENCED_FIRST);
    }

    @Override
    public String getStrategyName() {
        return STRATEGY_NAME;
    }

    @Override
    public String getDescription() {
        return "Assigns tickets to the most experienced technicians to accelerate root cause analysis on difficult issues.";
    }
}
