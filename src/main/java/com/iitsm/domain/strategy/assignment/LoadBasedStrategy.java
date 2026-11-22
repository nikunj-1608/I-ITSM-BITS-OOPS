package com.iitsm.domain.strategy.assignment;

import com.iitsm.domain.exception.TechnicianUnavailableException;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.Technician;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

// OOP (Polymorphism): LoadBasedStrategy is interchangeable with every other AssignmentStrategy; the registry picks one by name at run time.
/**
 * Pattern: Strategy.
 * Routes incidents to the technician with the lowest active load ratio to balance team workload.
 *
 * <p>Ranking (ADR-044, unchanged): lowest load ratio first, then more experience, then input order. The score is
 * {@code 1 - load ratio}; experience appears in the explanation with weight 0 because it only breaks ties.
 */
public class LoadBasedStrategy implements AssignmentStrategy {

    // OOP (static final): the strategy's registry name as a constant, never a repeated literal.
    public static final String STRATEGY_NAME = "LOAD_BASED";

    private static final Comparator<RankedCandidates.Entry> LOWEST_LOAD_FIRST =
            Comparator.comparingDouble((RankedCandidates.Entry e) -> e.technician().getLoadRatio())
                    .thenComparing(Comparator.comparingInt(
                            (RankedCandidates.Entry e) -> e.technician().getExperienceYears()).reversed());

    private final LoadFactor load = new LoadFactor();
    private final ExperienceFactor experience =
            new ExperienceFactor(ExperienceFactor.EXPERIENCE_STRATEGY_FULL_SCORE_YEARS);

    // OOP (Method overriding): this strategy's own answer to recommend(...); callers only see the interface type.
    @Override
    public AssignmentRecommendation recommend(Incident incident, List<Technician> candidates) {
        RankedCandidates ranking = score(incident, candidates);
        Technician selected = ranking.top().technician();
        String rationale = String.format("Selected for lowest workload: %d/%d active tickets (load ratio: %.2f)",
                selected.getActiveIncidentCount(), selected.getMaxCapacity(), selected.getLoadRatio());
        return ranking.toRecommendation(STRATEGY_NAME, rationale);
    }

    @Override
    public List<CandidateScore> rank(Incident incident, List<Technician> candidates) {
        return score(incident, candidates).scores();
    }

    private RankedCandidates score(Incident incident, List<Technician> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            throw new TechnicianUnavailableException("No available technicians found for load-based assignment");
        }
        AssignmentContext context = AssignmentContext.of(incident);
        List<RankedCandidates.Entry> entries = new ArrayList<>(candidates.size());
        for (Technician technician : candidates) {
            AssignmentFactor.Reading free = load.read(context, technician);
            AssignmentFactor.Reading years = experience.read(context, technician);
            List<FactorScore> scores = List.of(
                    FactorScore.of(LoadFactor.KEY, free.rawScore(), 1.0, free.detail()),
                    FactorScore.of(ExperienceFactor.KEY, years.rawScore(), 0.0, "tie-break: " + years.detail()));
            entries.add(new RankedCandidates.Entry(technician, scores, 1.0 - technician.getLoadRatio()));
        }
        return RankedCandidates.rank(entries, LOWEST_LOAD_FIRST);
    }

    @Override
    public String getStrategyName() {
        return STRATEGY_NAME;
    }

    @Override
    public String getDescription() {
        return "Balances workload across support teams by routing to the technician with the lowest load ratio.";
    }
}
