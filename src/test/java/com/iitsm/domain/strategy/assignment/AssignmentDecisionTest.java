package com.iitsm.domain.strategy.assignment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.Technician;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The assignment decision value object, the routed pool and the candidate filter's pool kinds (ADR-044, Phase L2).
 */
class AssignmentDecisionTest {

    private static final Instant NOW = Instant.parse("2026-11-08T12:00:00Z");

    private AssignmentRecommendation compositeOverAll() {
        return new WeightedCompositeStrategy().recommend(AssignmentGrid.incidents().get("NET_P2"),
                AssignmentGrid.candidateLists().get("ALL"));
    }

    @Test
    @DisplayName("a strategy decision keeps the strategy's ranking, best first, and the full count")
    void strategyDecisionKeepsTheRanking() {
        AssignmentRecommendation recommendation = compositeOverAll();
        AssignmentDecision decision = AssignmentDecision.byStrategy("ASG-1", "INC-1", PoolKind.OWNING_TEAM,
                recommendation, "SYSTEM", NOW, 10);

        assertThat(decision.strategyName()).isEqualTo("WEIGHTED_COMPOSITE");
        assertThat(decision.chosenTechnicianId()).isEqualTo(recommendation.technician().getId());
        assertThat(decision.candidates()).isEqualTo(recommendation.ranking());
        assertThat(decision.candidateCount()).isEqualTo(8);
    }

    @Test
    @DisplayName("the stored ranking is cut to the configured number, and the chosen technician is always kept")
    void rankingIsCappedAndKeepsTheChoice() {
        AssignmentRecommendation recommendation = compositeOverAll();
        AssignmentDecision decision = AssignmentDecision.byStrategy("ASG-1", "INC-1", PoolKind.OWNING_TEAM,
                recommendation, "SYSTEM", NOW, 3);

        assertThat(decision.candidates()).hasSize(3);
        assertThat(decision.candidates().get(0).chosen()).isTrue();
        assertThat(decision.candidates().get(0).technicianId()).isEqualTo(decision.chosenTechnicianId());
        assertThat(decision.candidateCount()).isEqualTo(8);
        assertThat(AssignmentDecision.byStrategy("ASG-2", "INC-1", PoolKind.OWNING_TEAM, recommendation, "SYSTEM", NOW, 1)
                .candidates()).hasSize(1);
        assertThatThrownBy(() -> AssignmentDecision.byStrategy("ASG-3", "INC-1", PoolKind.OWNING_TEAM,
                recommendation, "SYSTEM", NOW, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a recommendation without a ranking (an older strategy) is stored with its final score only")
    void recommendationWithoutRankingIsStoredOpaque() {
        Technician tech = AssignmentGrid.technicians().get("T-APP");
        AssignmentDecision decision = AssignmentDecision.byStrategy("ASG-1", "INC-1", PoolKind.SKILLED_OVERFLOW,
                new AssignmentRecommendation(tech, 0.7, "LEGACY", "x"), "DISPATCHER", NOW, 10);

        assertThat(decision.candidates()).singleElement().satisfies(c -> {
            assertThat(c.technicianId()).isEqualTo("T-APP");
            assertThat(c.total()).isEqualTo(0.7);
        });
        assertThat(decision.candidateCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("manual, inherited and requeued decisions carry no strategy and no ranking")
    void decisionsWithoutAStrategy() {
        AssignmentDecision manual = AssignmentDecision.manual("ASG-1", "INC-1", "TECH-1", "ADMIN-1", NOW);
        AssignmentDecision inherited = AssignmentDecision.inheritedFromParent("ASG-2", "INC-2", "TECH-1", "INC-1", "SYSTEM", NOW);
        AssignmentDecision requeued = AssignmentDecision.requeuedToSameTechnician("ASG-3", "INC-1", "TECH-1", "ADMIN-1", NOW);

        for (AssignmentDecision d : List.of(manual, inherited, requeued)) {
            assertThat(d.strategyName()).isNull();
            assertThat(d.candidates()).isEmpty();
            assertThat(d.candidateCount()).isZero();
        }
        assertThat(manual.poolKind()).isEqualTo(PoolKind.MANUAL);
        assertThat(inherited.relatedIncidentId()).isEqualTo("INC-1");
        assertThat(requeued.poolKind()).isEqualTo(PoolKind.REQUEUED_TO_SAME_TECHNICIAN);
        assertThatThrownBy(() -> AssignmentDecision.inheritedFromParent("ASG-4", "INC-2", "TECH-1", null, "SYSTEM", NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("invariants: a strategy decision needs its strategy and a ranking that starts with the chosen technician")
    void invariants() {
        List<CandidateScore> ranking = compositeOverAll().ranking();
        String chosen = ranking.get(0).technicianId();
        assertThatThrownBy(() -> new AssignmentDecision("A", "I", PoolKind.OWNING_TEAM, null, chosen, "SYSTEM", null,
                NOW, ranking.size(), ranking)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AssignmentDecision("A", "I", PoolKind.OWNING_TEAM, "X", "SOMEONE-ELSE", "SYSTEM",
                null, NOW, ranking.size(), ranking)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AssignmentDecision("A", "I", PoolKind.OWNING_TEAM, "X", chosen, "SYSTEM", null,
                NOW, 1, ranking)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AssignmentDecision("A", "I", PoolKind.MANUAL, "X", chosen, "SYSTEM", null,
                NOW, 0, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AssignmentDecision("A", "I", PoolKind.MANUAL, null, chosen, " ", null,
                NOW, 0, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AssignmentDecision("A", "I", PoolKind.MANUAL, null, chosen, "SYSTEM", null,
                null, 0, List.of())).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("the candidate filter names its pool: owning team, skilled overflow, or nobody")
    void candidateFilterNamesThePool() {
        Map<String, Technician> t = AssignmentGrid.technicians();
        CandidateFilter filter = new CandidateFilter();
        List<Technician> all = List.copyOf(t.values());

        RoutedCandidates team = filter.route(all, "TEAM-T-APP", IncidentCategory.APPLICATION, null);
        assertThat(team.pool()).isEqualTo(PoolKind.OWNING_TEAM);
        assertThat(team.technicians()).containsExactly(t.get("T-APP"));

        RoutedCandidates overflow = filter.route(all, "TEAM-NOBODY", IncidentCategory.NETWORK, null);
        assertThat(overflow.pool()).isEqualTo(PoolKind.SKILLED_OVERFLOW);
        assertThat(overflow.technicians()).containsExactly(t.get("T-NET-A"), t.get("T-NET-B"), t.get("T-VET"));

        RoutedCandidates none = filter.route(all, "TEAM-NOBODY", IncidentCategory.HARDWARE, null);
        assertThat(none.isEmpty()).isTrue();
        assertThat(filter.filterCandidates(all, "TEAM-NOBODY", IncidentCategory.NETWORK, null))
                .isEqualTo(overflow.technicians());
        assertThatThrownBy(() -> new RoutedCandidates(PoolKind.MANUAL, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
