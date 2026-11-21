package com.iitsm.domain.strategy.assignment;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.Technician;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Asking round robin for an explanation has no side effect (ADR-044): {@code rank} shows whose turn it is without
 * advancing the rotation, {@code recommend} advances it once and explains the choice it made, and
 * {@code freshInstance} gives an independent rotation (for the offline comparison) while stateless strategies return
 * themselves.
 */
class RoundRobinExplanationTest {

    private final Incident ticket = AssignmentGrid.incidents().get("NET_P3");
    private final List<Technician> all = AssignmentGrid.candidateLists().get("ALL");

    @Test
    @DisplayName("rank can be asked any number of times without moving the rotation")
    void rankDoesNotAdvanceTheRotation() {
        RoundRobinStrategy strategy = new RoundRobinStrategy();
        strategy.recommend(ticket, all);
        for (int i = 0; i < 5; i++) {
            assertThat(strategy.rank(ticket, all).get(0).technicianId()).isEqualTo(all.get(1).getId());
        }
        assertThat(strategy.recommend(ticket, all).technician()).isSameAs(all.get(1));
        assertThat(strategy.recommend(ticket, all).technician()).isSameAs(all.get(2));
    }

    @Test
    @DisplayName("the ranking lists the candidates in the order the rotation will serve them")
    void rankingFollowsTheRotation() {
        RoundRobinStrategy strategy = new RoundRobinStrategy();
        strategy.recommend(ticket, all);
        strategy.recommend(ticket, all);
        List<CandidateScore> ranking = strategy.rank(ticket, all);
        assertThat(ranking).extracting(CandidateScore::technicianId).containsExactly(
                all.get(2).getId(), all.get(3).getId(), all.get(4).getId(), all.get(5).getId(),
                all.get(6).getId(), all.get(7).getId(), all.get(0).getId(), all.get(1).getId());
        assertThat(ranking.get(0).factorScores().get(0).detail()).isEqualTo("queue slot #3 of 8, turn 1 in the rotation");
        assertThat(ranking).allSatisfy(candidate -> assertThat(candidate.total()).isEqualTo(0.80));
    }

    @Test
    @DisplayName("the recommendation explains the slot it used, not the next one")
    void recommendationExplainsItsOwnChoice() {
        RoundRobinStrategy strategy = new RoundRobinStrategy();
        AssignmentRecommendation first = strategy.recommend(ticket, all);
        assertThat(first.ranking().get(0).technicianId()).isEqualTo(all.get(0).getId());
        assertThat(first.rationale()).contains("queue slot #1 of 8");
        assertThat(strategy.rank(ticket, all).get(0).technicianId()).isEqualTo(all.get(1).getId());
    }

    @Test
    @DisplayName("freshInstance starts its own rotation and leaves the original untouched")
    void freshInstanceIsIndependent() {
        RoundRobinStrategy live = new RoundRobinStrategy();
        live.recommend(ticket, all);
        live.recommend(ticket, all);
        AssignmentStrategy copy = live.freshInstance();

        assertThat(copy).isNotSameAs(live).isInstanceOf(RoundRobinStrategy.class);
        for (int i = 0; i < 7; i++) {
            copy.recommend(ticket, all);
        }
        assertThat(copy.recommend(ticket, all).technician()).isSameAs(all.get(7));
        assertThat(live.recommend(ticket, all).technician()).isSameAs(all.get(2));
    }

    @Test
    @DisplayName("stateless strategies return themselves as their fresh instance")
    void statelessStrategiesReturnThemselves() {
        for (AssignmentStrategy strategy : List.of(new WeightedCompositeStrategy(), new SkillBasedStrategy(),
                new LoadBasedStrategy(), new ExperienceBasedStrategy())) {
            assertThat(strategy.freshInstance()).isSameAs(strategy);
        }
    }
}
