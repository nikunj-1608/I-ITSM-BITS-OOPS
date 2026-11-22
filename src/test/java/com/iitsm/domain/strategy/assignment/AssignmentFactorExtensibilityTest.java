package com.iitsm.domain.strategy.assignment;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.Priority;
import com.iitsm.domain.model.Technician;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Open/Closed proof for assignment factors (ADR-044): a new factor is a new class registered with its weights; no
 * existing class changes, and the new factor appears in the explanation with its weight.
 */
class AssignmentFactorExtensibilityTest {

    /** A factor that only likes technicians whose id ends in "B"; stands in for any future factor. */
    private static final class PrefersTeamBFactor implements AssignmentFactor {
        @Override
        public String key() {
            return "PREFERS_B";
        }

        @Override
        public String label() {
            return "B";
        }

        @Override
        public Reading read(AssignmentContext context, Technician technician) {
            return new Reading(technician.getId().endsWith("B") ? 1.0 : 0.0, "ends in B");
        }
    }

    @Test
    @DisplayName("a registered extra factor changes the choice and shows in the breakdown")
    void extraFactorIsUsedAndExplained() {
        Map<String, Double> weights = Map.of("SKILL_MATCH", 0.4, "LOAD", 0.2, "EXPERIENCE", 0.2, "PREFERS_B", 0.2);
        WeightedCompositeStrategy extended = new WeightedCompositeStrategy(
                List.of(new SkillMatchFactor(), new LoadFactor(), new ExperienceFactor(12), new PrefersTeamBFactor()),
                new FactorWeights(Map.of(Priority.P1, weights, Priority.P2, weights, Priority.P3, weights,
                        Priority.P4, weights)));
        Incident ticket = AssignmentGrid.incidents().get("NET_P3");
        List<Technician> tie = AssignmentGrid.candidateLists().get("TIE");

        assertThat(new WeightedCompositeStrategy().recommend(ticket, tie).technician().getId()).isEqualTo("T-NET-A");
        AssignmentRecommendation recommendation = extended.recommend(ticket, tie);
        assertThat(recommendation.technician().getId()).isEqualTo("T-NET-B");
        assertThat(recommendation.ranking().get(0).factorScores()).extracting(FactorScore::factorKey)
                .containsExactly("SKILL_MATCH", "LOAD", "EXPERIENCE", "PREFERS_B");
        assertThat(recommendation.rationale()).contains("B: 1.00 * 0.20");
    }

    @Test
    @DisplayName("the composite's total is the sum of its contributions")
    void compositeTotalIsTheSumOfContributions() {
        List<CandidateScore> ranking = new WeightedCompositeStrategy().rank(
                AssignmentGrid.incidents().get("APP_P4"), AssignmentGrid.candidateLists().get("ALL"));
        for (CandidateScore candidate : ranking) {
            double sum = 0.0;
            for (FactorScore factor : candidate.factorScores()) {
                sum += factor.contribution();
            }
            assertThat(candidate.total()).isEqualTo(sum);
        }
    }
}
