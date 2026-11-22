package com.iitsm.domain.strategy.suggestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.iitsm.domain.exception.ValidationException;
import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.Priority;
import com.iitsm.domain.strategy.nlp.WordListDomainVocabulary;
import com.iitsm.domain.strategy.similarity.SimilarityMeasure;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** ADR-041: the vote smoothing, its band, the vote value object, and how votes may (and may not) change the ranking. */
class SuggestionVoteRulesTest {

    private static final VoteBand DEFAULT_BAND = new VoteBand(0.20);
    private static final Instant NOW = Instant.parse("2026-11-07T10:00:00Z");

    @Nested
    @DisplayName("smoothing and band")
    class Band {

        @Test
        @DisplayName("Without votes, or with an even split, the factor is exactly 1")
        void neutral() {
            assertThat(DEFAULT_BAND.factor(VoteTally.none())).isEqualTo(1.0);
            assertThat(DEFAULT_BAND.factor(new VoteTally(7, 7))).isCloseTo(1.0, within(1e-12));
        }

        @ParameterizedTest(name = "{0} worked, {1} did not -> {2}")
        @CsvSource({"1, 0, 1.0666666666666667", "0, 1, 0.9333333333333333", "3, 1, 1.0666666666666667", "10, 0, 1.1666666666666667"})
        @DisplayName("The factor is 0.8 + 0.4 x (worked + 1) / (worked + didNotWork + 2)")
        void formula(long worked, long didNotWork, double expected) {
            assertThat(DEFAULT_BAND.factor(new VoteTally(worked, didNotWork))).isCloseTo(expected, within(1e-12));
        }

        @Test
        @DisplayName("However many votes, the factor stays strictly inside (0.8, 1.2)")
        void bounded() {
            assertThat(DEFAULT_BAND.factor(new VoteTally(1_000_000_000L, 0))).isLessThan(1.2).isGreaterThan(1.19);
            assertThat(DEFAULT_BAND.factor(new VoteTally(0, 1_000_000_000L))).isGreaterThan(0.8).isLessThan(0.81);
        }

        @Test
        @DisplayName("More 'worked' votes never lower the factor")
        void monotonic() {
            double previous = 0;
            for (long worked = 0; worked < 50; worked++) {
                double factor = DEFAULT_BAND.factor(new VoteTally(worked, 5));
                assertThat(factor).isGreaterThan(previous);
                previous = factor;
            }
        }

        @Test
        @DisplayName("Weight 0 switches votes off; a weight outside [0, 1) is refused at start-up")
        void weightRange() {
            assertThat(VoteBand.neutral().factor(new VoteTally(100, 0))).isEqualTo(1.0);
            assertThatThrownBy(() -> new VoteBand(-0.1)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new VoteBand(1.0)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new VoteBand(Double.NaN)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Vote counts cannot be negative; unknown sources have no votes")
        void tallies() {
            assertThatThrownBy(() -> new VoteTally(-1, 0)).isInstanceOf(IllegalArgumentException.class);
            SuggestionSourceKey key = new SuggestionSourceKey(SuggestionSourceType.RESOLVED_TICKET, "A");
            VoteTallies tallies = VoteTallies.of(Map.of(key, new VoteTally(2, 1)));
            assertThat(tallies.of(key)).isEqualTo(new VoteTally(2, 1));
            assertThat(tallies.of(new SuggestionSourceKey(SuggestionSourceType.KB_ARTICLE, "A"))).isEqualTo(VoteTally.none());
        }
    }

    @Nested
    @DisplayName("the vote")
    class Vote {

        private final SuggestionSourceKey source = new SuggestionSourceKey(SuggestionSourceType.RESOLVED_TICKET, "NET-1");

        @Test
        @DisplayName("Recasting keeps the key and replaces verdict and time")
        void recast() {
            SuggestionVote first = SuggestionVote.cast("TECH-1", source, "NET-2", true, NOW);
            SuggestionVote second = first.recast(false, NOW.plusSeconds(5));

            assertThat(second.voterId()).isEqualTo("TECH-1");
            assertThat(second.source()).isEqualTo(source);
            assertThat(second.targetIncidentId()).isEqualTo("NET-2");
            assertThat(second.worked()).isFalse();
            assertThat(second.votedAt()).isEqualTo(NOW.plusSeconds(5));
        }

        @Test
        @DisplayName("A ticket cannot be voted as its own fix; an article with the ticket's id can")
        void selfVoteRefused() {
            assertThatThrownBy(() -> SuggestionVote.cast("TECH-1", source, "NET-1", true, NOW))
                    .isInstanceOf(ValidationException.class);
            SuggestionSourceKey article = new SuggestionSourceKey(SuggestionSourceType.KB_ARTICLE, "NET-1");
            assertThat(SuggestionVote.cast("TECH-1", article, "NET-1", true, NOW).source()).isEqualTo(article);
        }

        @Test
        @DisplayName("A vote needs a voter, a source, a ticket and a time")
        void invariants() {
            assertThatThrownBy(() -> new SuggestionVote(" ", source, "NET-2", true, NOW)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new SuggestionVote("T", null, "NET-2", true, NOW)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new SuggestionVote("T", source, "", true, NOW)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new SuggestionVote("T", source, "NET-2", true, null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new SuggestionSourceKey(SuggestionSourceType.KB_ARTICLE, " "))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("votes in the ranking")
    class Ranking {

        private static final String TICKET = "NET-9";
        private final WordListDomainVocabulary vocabulary = new WordListDomainVocabulary(List.of("vpn"));
        private final SuggestionQuery query = new SuggestionQuery(TICKET, IncidentCategory.NETWORK, "vpn down", Set.of(TICKET));

        /** Similarity read from the candidate title, which is the number itself. */
        private final SimilarityMeasure titleScore = new SimilarityMeasure() {
            @Override
            public double calculateSimilarity(String a, String candidateText) {
                return Double.parseDouble(candidateText.split(" ")[0]);
            }

            @Override
            public String getMeasureName() {
                return "TITLE";
            }
        };

        private SuggestionCandidate candidate(String id, double similarity) {
            return new SuggestionCandidate(SuggestionSourceType.RESOLVED_TICKET, id, IncidentCategory.NETWORK, Priority.P3,
                    similarity + " vpn", "", "vpn", "vpn", "");
        }

        private TfIdfResolutionSuggester suggester(int maxResults) {
            return new TfIdfResolutionSuggester(titleScore, vocabulary,
                    new SuggestionPolicy(maxResults, 0.20, 1, true, 200, 5), DEFAULT_BAND);
        }

        private VoteTallies votes(String id, long worked, long didNotWork) {
            return VoteTallies.of(Map.of(new SuggestionSourceKey(SuggestionSourceType.RESOLVED_TICKET, id),
                    new VoteTally(worked, didNotWork)));
        }

        private List<String> ids(List<ResolutionSuggestion> result) {
            return result.stream().map(s -> s.source().sourceId()).toList();
        }

        @Test
        @DisplayName("Votes reorder two close suggestions: score = similarity x factor")
        void votesReorderCloseSuggestions() {
            var close = List.of(candidate("A", 0.50), candidate("B", 0.48));

            assertThat(ids(suggester(3).suggest(query, close, VoteTallies.none()))).containsExactly("A", "B");
            var voted = suggester(3).suggest(query, close, votes("B", 5, 0));
            assertThat(ids(voted)).containsExactly("B", "A");
            ResolutionSuggestion b = voted.get(0);
            assertThat(b.similarity()).isEqualTo(0.48);
            assertThat(b.score()).isCloseTo(0.48 * DEFAULT_BAND.factor(new VoteTally(5, 0)), within(1e-12));
            assertThat(b.votes()).isEqualTo(new VoteTally(5, 0));
        }

        @Test
        @DisplayName("Votes cannot overturn a clear gap in similarity: 0.30 with endless praise stays below 0.50")
        void votesNeverOverrideAClearGap() {
            var apart = List.of(candidate("A", 0.50), candidate("B", 0.30));

            var result = suggester(3).suggest(query, apart, VoteTallies.of(Map.of(
                    new SuggestionSourceKey(SuggestionSourceType.RESOLVED_TICKET, "B"), new VoteTally(1_000_000, 0),
                    new SuggestionSourceKey(SuggestionSourceType.RESOLVED_TICKET, "A"), new VoteTally(0, 1_000_000))));

            assertThat(ids(result)).containsExactly("A", "B");
        }

        @Test
        @DisplayName("The minimum applies to similarity alone: praise does not let a weak match in")
        void votesDoNotLiftBelowMinimum() {
            var result = suggester(3).suggest(query, List.of(candidate("WEAK", 0.19)), votes("WEAK", 1_000, 0));

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("The minimum applies to similarity alone: complaints do not push a qualified match out")
        void votesDoNotDropAQualifiedMatch() {
            var result = suggester(3).suggest(query, List.of(candidate("OK", 0.21)), votes("OK", 0, 1_000));

            assertThat(ids(result)).containsExactly("OK");
            assertThat(result.get(0).score()).isLessThan(0.21).isGreaterThan(0.21 * 0.8);
        }

        @Test
        @DisplayName("Votes are applied before the top-N cut, so a praised close fourth can enter the top 3")
        void votesApplyBeforeTheCut() {
            var four = List.of(candidate("A", 0.50), candidate("B", 0.49), candidate("C", 0.48), candidate("D", 0.47));

            assertThat(ids(suggester(3).suggest(query, four, votes("D", 10, 0)))).containsExactly("D", "A", "B");
        }
    }
}
