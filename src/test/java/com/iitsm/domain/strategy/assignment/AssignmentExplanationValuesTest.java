package com.iitsm.domain.strategy.assignment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.Priority;
import com.iitsm.domain.model.Technician;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The explanation value objects, the factor weight table and the default {@code rank} of a strategy written before
 * ADR-044.
 */
class AssignmentExplanationValuesTest {

    @Test
    @DisplayName("a factor score computes its contribution and refuses non-finite numbers and a blank key")
    void factorScore() {
        FactorScore score = FactorScore.of("LOAD", 0.5, 0.3, "2/4 tickets active");
        assertThat(score.contribution()).isEqualTo(0.5 * 0.3);
        assertThat(FactorScore.of("X", 1, 1, null).detail()).isEmpty();
        assertThatThrownBy(() -> FactorScore.of(" ", 1, 1, "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FactorScore.of("X", Double.NaN, 1, "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FactorScore.of("X", 1, Double.POSITIVE_INFINITY, ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a candidate score is immutable and only rank 1 may be the chosen one")
    void candidateScore() {
        List<FactorScore> factors = new ArrayList<>(List.of(FactorScore.of("LOAD", 1, 1, "")));
        CandidateScore chosen = new CandidateScore("T-1", factors, 1.0, 1, true);
        factors.clear();
        assertThat(chosen.factorScores()).hasSize(1);
        assertThatThrownBy(() -> chosen.factorScores().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new CandidateScore("T-1", List.of(), 1.0, 2, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CandidateScore("T-1", List.of(), 1.0, 1, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CandidateScore("T-1", List.of(), 1.0, 0, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CandidateScore("", List.of(), 1.0, 1, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the default weights sum to 1 for every priority and match the composite's historic weights")
    void defaultWeights() {
        FactorWeights weights = FactorWeights.defaults();
        for (Priority priority : Priority.values()) {
            double sum = weights.weightOf(priority, "SKILL_MATCH") + weights.weightOf(priority, "LOAD")
                    + weights.weightOf(priority, "EXPERIENCE");
            assertThat(sum).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
        }
        assertThat(weights.weightOf(Priority.P1, "EXPERIENCE")).isEqualTo(0.35);
        assertThat(weights.weightOf(Priority.P3, "SKILL_MATCH")).isEqualTo(0.50);
        assertThat(weights.weightOf(Priority.P4, "LOAD")).isEqualTo(0.50);
        assertThat(weights.covers("UNKNOWN")).isFalse();
        assertThatThrownBy(() -> weights.weightOf(Priority.P1, "UNKNOWN")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a weight table must cover every priority, sum to 1 and hold no negative weight")
    void invalidWeights() {
        Map<String, Double> half = Map.of("A", 0.5);
        assertThatThrownBy(() -> new FactorWeights(Map.of(Priority.P1, half))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FactorWeights(allPriorities(half))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FactorWeights(allPriorities(Map.of("A", 1.5, "B", -0.5))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new FactorWeights(allPriorities(Map.of("A", 1.0))).weightOf(Priority.P2, "A")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("the composite refuses a factor without weights")
    void compositeNeedsAWeightForEveryFactor() {
        assertThatThrownBy(() -> new WeightedCompositeStrategy(List.of(new LoadFactor()),
                new FactorWeights(allPriorities(Map.of("A", 1.0))))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WeightedCompositeStrategy(List.of(), FactorWeights.defaults()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a strategy written before ADR-044 still explains its choice through the default rank")
    void legacyStrategyGetsAnOpaqueRanking() {
        AssignmentStrategy legacy = new AssignmentStrategy() {
            @Override
            public AssignmentRecommendation recommend(Incident incident, List<Technician> candidates) {
                return new AssignmentRecommendation(candidates.get(candidates.size() - 1), 0.7, "LEGACY", "last one");
            }

            @Override
            public String getStrategyName() {
                return "LEGACY";
            }

            @Override
            public String getDescription() {
                return "picks the last candidate";
            }
        };
        List<Technician> tie = AssignmentGrid.candidateLists().get("TIE");
        List<CandidateScore> ranking = legacy.rank(AssignmentGrid.incidents().get("NET_P3"), tie);
        assertThat(ranking).singleElement().satisfies(score -> {
            assertThat(score.technicianId()).isEqualTo(tie.get(1).getId());
            assertThat(score.total()).isEqualTo(0.7);
            assertThat(score.chosen()).isTrue();
            assertThat(score.factorScores()).extracting(FactorScore::factorKey).containsExactly("STRATEGY_SCORE");
        });
        assertThat(legacy.freshInstance()).isSameAs(legacy);
    }

    private static Map<Priority, Map<String, Double>> allPriorities(Map<String, Double> weights) {
        return Map.of(Priority.P1, weights, Priority.P2, weights, Priority.P3, weights, Priority.P4, weights);
    }
}
