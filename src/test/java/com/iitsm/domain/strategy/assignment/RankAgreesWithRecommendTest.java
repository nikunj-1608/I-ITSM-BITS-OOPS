package com.iitsm.domain.strategy.assignment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.iitsm.domain.exception.TechnicianUnavailableException;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.Technician;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The explanation agrees with the choice (ADR-044): for every strategy, ticket and candidate list of the grid, the
 * rank-1 candidate of {@code rank} is the technician {@code recommend} picks (ties included), the recommendation
 * carries exactly that ranking, and the ranking is well formed (every candidate once, ranks 1..n, one chosen).
 */
class RankAgreesWithRecommendTest {

    static Stream<Supplier<AssignmentStrategy>> strategies() {
        return Stream.of(WeightedCompositeStrategy::new, SkillBasedStrategy::new, LoadBasedStrategy::new,
                ExperienceBasedStrategy::new, RoundRobinStrategy::new);
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("strategies")
    @DisplayName("rank-1 is the recommended technician and the recommendation carries the same ranking")
    void rankTopIsTheRecommendation(Supplier<AssignmentStrategy> factory) {
        AssignmentStrategy strategy = factory.get();
        for (Incident ticket : AssignmentGrid.incidents().values()) {
            for (List<Technician> candidates : AssignmentGrid.candidateLists().values()) {
                List<CandidateScore> explained = strategy.rank(ticket, candidates);
                AssignmentRecommendation chosen = strategy.recommend(ticket, candidates);

                assertThat(explained.get(0).technicianId()).isEqualTo(chosen.technician().getId());
                assertThat(chosen.ranking()).isEqualTo(explained);
                assertThat(chosen.score()).isEqualTo(clamp(explained.get(0).total()));
                assertWellFormed(explained, candidates);
            }
        }
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("strategies")
    @DisplayName("an exact tie goes to the first candidate in both orders, in rank and in recommend")
    void tiesGoToTheFirstCandidate(Supplier<AssignmentStrategy> factory) {
        Map<String, List<Technician>> lists = AssignmentGrid.candidateLists();
        Incident ticket = AssignmentGrid.incidents().get("NET_P2");
        for (String name : List.of("TIE", "TIE_REVERSED")) {
            AssignmentStrategy strategy = factory.get();
            List<Technician> tie = lists.get(name);
            assertThat(strategy.rank(ticket, tie).get(0).technicianId()).isEqualTo(tie.get(0).getId());
            assertThat(strategy.recommend(ticket, tie).technician().getId()).isEqualTo(tie.get(0).getId());
        }
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("strategies")
    @DisplayName("an empty or missing candidate list is refused by rank as by recommend")
    void emptyListIsRefused(Supplier<AssignmentStrategy> factory) {
        AssignmentStrategy strategy = factory.get();
        Incident ticket = AssignmentGrid.incidents().get("NET_P3");
        assertThatThrownBy(() -> strategy.rank(ticket, List.of())).isInstanceOf(TechnicianUnavailableException.class);
        assertThatThrownBy(() -> strategy.rank(ticket, null)).isInstanceOf(TechnicianUnavailableException.class);
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("strategies")
    @DisplayName("every factor's contribution is its raw score times its weight")
    void contributionsAreRawTimesWeight(Supplier<AssignmentStrategy> factory) {
        AssignmentStrategy strategy = factory.get();
        List<CandidateScore> ranking = strategy.rank(AssignmentGrid.incidents().get("APP_P1"),
                AssignmentGrid.candidateLists().get("ALL"));
        ranking.forEach(candidate -> candidate.factorScores().forEach(factor ->
                assertThat(factor.contribution()).isEqualTo(factor.rawScore() * factor.weight())));
    }

    private static void assertWellFormed(List<CandidateScore> ranking, List<Technician> candidates) {
        assertThat(ranking).extracting(CandidateScore::technicianId)
                .containsExactlyInAnyOrderElementsOf(candidates.stream().map(Technician::getId).toList());
        assertThat(ranking).extracting(CandidateScore::rank)
                .containsExactlyElementsOf(IntStream.rangeClosed(1, candidates.size()).boxed().toList());
        assertThat(ranking).filteredOn(CandidateScore::chosen).hasSize(1);
        assertThat(ranking).allSatisfy(candidate -> assertThat(candidate.factorScores()).isNotEmpty());
    }

    private static double clamp(double score) {
        return Math.max(0.0, Math.min(1.0, score));
    }
}
