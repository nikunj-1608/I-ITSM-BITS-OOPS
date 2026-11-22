package com.iitsm.domain.strategy.suggestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.Priority;
import com.iitsm.domain.strategy.nlp.TfIdfVectorizer;
import com.iitsm.domain.strategy.nlp.WordListDomainVocabulary;
import com.iitsm.domain.strategy.similarity.CosineTfIdfSimilarity;
import com.iitsm.domain.strategy.similarity.SimilarityMeasure;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** ADR-040: the ranking rules of the TF-IDF suggester, one rule per test. */
class TfIdfResolutionSuggesterTest {

    private static final String TICKET = "NET-2026-000100";
    private static final WordListDomainVocabulary VOCABULARY =
            new WordListDomainVocabulary(List.of("vpn", "gateway", "printer", "toner", "certificate", "dns"));
    private static final SuggestionPolicy DEFAULT = new SuggestionPolicy(3, 0.20, 1, true, 200, 5);

    /** Similarity fixed per candidate: the candidate's title is the key, so each test sets the scores it needs. */
    private static final class FixedSimilarity implements SimilarityMeasure {
        private final Map<String, Double> byTitle = new HashMap<>();

        FixedSimilarity score(String title, double similarity) {
            byTitle.put(title, similarity);
            return this;
        }

        @Override
        public double calculateSimilarity(String query, String candidateText) {
            return byTitle.entrySet().stream()
                    .filter(e -> candidateText.startsWith(e.getKey()))
                    .mapToDouble(Map.Entry::getValue)
                    .findFirst().orElse(0.0);
        }

        @Override
        public String getMeasureName() {
            return "FIXED";
        }
    }

    private static SuggestionCandidate ticket(String id, IncidentCategory category, String title) {
        return new SuggestionCandidate(SuggestionSourceType.RESOLVED_TICKET, id, category, Priority.P3,
                title, "vpn gateway problem", "Expired certificate on the vpn gateway", "Renewed the certificate", "Alert on expiry");
    }

    private static SuggestionQuery query(IncidentCategory category, Set<String> excluded) {
        return new SuggestionQuery(TICKET, category, "VPN gateway refuses connections", excluded);
    }

    private static SuggestionQuery query() {
        return query(IncidentCategory.NETWORK, Set.of(TICKET));
    }

    private static List<String> ids(List<ResolutionSuggestion> suggestions) {
        return suggestions.stream().map(s -> s.source().sourceId()).toList();
    }

    @Nested
    @DisplayName("ranking")
    class Ranking {

        @Test
        @DisplayName("The most similar past fix comes first")
        void mostSimilarFirst() {
            FixedSimilarity sim = new FixedSimilarity().score("A", 0.40).score("B", 0.90).score("C", 0.60);
            var suggester = new TfIdfResolutionSuggester(sim, VOCABULARY, DEFAULT);

            var result = suggester.suggest(query(), List.of(
                    ticket("A", IncidentCategory.NETWORK, "A"),
                    ticket("B", IncidentCategory.NETWORK, "B"),
                    ticket("C", IncidentCategory.NETWORK, "C")));

            assertThat(ids(result)).containsExactly("B", "C", "A");
            assertThat(result.get(0).similarity()).isEqualTo(0.90);
        }

        @Test
        @DisplayName("Equal scores are ordered by source id, so the answer does not depend on the database order")
        void tiesInSourceIdOrder() {
            FixedSimilarity sim = new FixedSimilarity().score("X", 0.5);
            var suggester = new TfIdfResolutionSuggester(sim, VOCABULARY, DEFAULT);

            var result = suggester.suggest(query(), List.of(
                    ticket("Z-2", IncidentCategory.NETWORK, "X"),
                    ticket("Z-1", IncidentCategory.NETWORK, "X")));

            assertThat(ids(result)).containsExactly("Z-1", "Z-2");
        }

        @Test
        @DisplayName("At most max-results suggestions are returned")
        void topNCut() {
            FixedSimilarity sim = new FixedSimilarity().score("A", 0.5).score("B", 0.6).score("C", 0.7).score("D", 0.8);
            var suggester = new TfIdfResolutionSuggester(sim, VOCABULARY, DEFAULT);

            var result = suggester.suggest(query(), List.of(
                    ticket("A", IncidentCategory.NETWORK, "A"), ticket("B", IncidentCategory.NETWORK, "B"),
                    ticket("C", IncidentCategory.NETWORK, "C"), ticket("D", IncidentCategory.NETWORK, "D")));

            assertThat(ids(result)).containsExactly("D", "C", "B");
        }

        @Test
        @DisplayName("A candidate below the minimum similarity is not suggested: an empty list beats a weak match")
        void belowMinimumIsDropped() {
            FixedSimilarity sim = new FixedSimilarity().score("A", 0.19).score("B", 0.20);
            var suggester = new TfIdfResolutionSuggester(sim, VOCABULARY, DEFAULT);

            assertThat(ids(suggester.suggest(query(), List.of(
                    ticket("A", IncidentCategory.NETWORK, "A"), ticket("B", IncidentCategory.NETWORK, "B")))))
                    .containsExactly("B");
            assertThat(suggester.suggest(query(), List.of(ticket("A", IncidentCategory.NETWORK, "A")))).isEmpty();
        }
    }

    @Nested
    @DisplayName("filters")
    class Filters {

        @Test
        @DisplayName("Another category is never suggested while the category filter is on, however similar")
        void sameCategoryIsAHardFilter() {
            FixedSimilarity sim = new FixedSimilarity().score("A", 0.99).score("B", 0.30);
            var suggester = new TfIdfResolutionSuggester(sim, VOCABULARY, DEFAULT);

            var result = suggester.suggest(query(), List.of(
                    ticket("A", IncidentCategory.SECURITY, "A"), ticket("B", IncidentCategory.NETWORK, "B")));

            assertThat(ids(result)).containsExactly("B");
        }

        @Test
        @DisplayName("With the category filter off, other categories compete on similarity")
        void categoryFilterOff() {
            FixedSimilarity sim = new FixedSimilarity().score("A", 0.99).score("B", 0.30);
            var suggester = new TfIdfResolutionSuggester(sim, VOCABULARY, new SuggestionPolicy(3, 0.20, 1, false, 200, 5));

            var result = suggester.suggest(query(), List.of(
                    ticket("A", IncidentCategory.SECURITY, "A"), ticket("B", IncidentCategory.NETWORK, "B")));

            assertThat(ids(result)).containsExactly("A", "B");
        }

        @Test
        @DisplayName("A ticket without a category gets nothing while the category filter is on")
        void noCategoryNoSuggestions() {
            FixedSimilarity sim = new FixedSimilarity().score("A", 0.99);
            var suggester = new TfIdfResolutionSuggester(sim, VOCABULARY, DEFAULT);

            assertThat(suggester.suggest(query(null, Set.of(TICKET)), List.of(ticket("A", null, "A")))).isEmpty();
        }

        @Test
        @DisplayName("The ticket itself is never suggested")
        void selfExcluded() {
            FixedSimilarity sim = new FixedSimilarity().score("A", 1.0).score("B", 0.5);
            var suggester = new TfIdfResolutionSuggester(sim, VOCABULARY, DEFAULT);

            var result = suggester.suggest(query(), List.of(
                    ticket(TICKET, IncidentCategory.NETWORK, "A"), ticket("B", IncidentCategory.NETWORK, "B")));

            assertThat(ids(result)).containsExactly("B");
        }

        @Test
        @DisplayName("A ticket linked as a duplicate (parent or child) is never suggested")
        void linkedDuplicatesExcluded() {
            FixedSimilarity sim = new FixedSimilarity().score("A", 0.9).score("B", 0.8).score("C", 0.5);
            var suggester = new TfIdfResolutionSuggester(sim, VOCABULARY, DEFAULT);

            var result = suggester.suggest(query(IncidentCategory.NETWORK, Set.of(TICKET, "PARENT", "CHILD")), List.of(
                    ticket("PARENT", IncidentCategory.NETWORK, "A"),
                    ticket("CHILD", IncidentCategory.NETWORK, "B"),
                    ticket("OTHER", IncidentCategory.NETWORK, "C")));

            assertThat(ids(result)).containsExactly("OTHER");
        }

        @Test
        @DisplayName("A similar candidate that shares no domain term is not suggested (content guard, as for duplicates)")
        void sharedDomainTermRequired() {
            SuggestionCandidate fillerOnly = new SuggestionCandidate(SuggestionSourceType.RESOLVED_TICKET, "F",
                    IncidentCategory.NETWORK, Priority.P3, "F refuses connections", "", "Someone restarted it", "Waited", "");
            var suggester = new TfIdfResolutionSuggester(new FixedSimilarity().score("F", 0.9), VOCABULARY, DEFAULT);

            assertThat(suggester.suggest(query(), List.of(fillerOnly))).isEmpty();
        }
    }

    @Nested
    @DisplayName("explanation")
    class Explanation {

        @Test
        @DisplayName("The matched terms are the domain terms both texts contain, in alphabetical order")
        void matchedTermsAreSharedDomainTerms() {
            var suggester = new TfIdfResolutionSuggester(new FixedSimilarity().score("A", 0.5), VOCABULARY, DEFAULT);

            var result = suggester.suggest(query(), List.of(ticket("A", IncidentCategory.NETWORK, "A")));

            // query: vpn, gateway; candidate: vpn, gateway, certificate -> shared: gateway, vpn
            assertThat(result.get(0).matchedTerms()).containsExactly("gateway", "vpn");
        }

        @Test
        @DisplayName("The matched terms are shown as the ticket wrote them, not as stems")
        void matchedTermsAreWordsNotStems() {
            var vocabulary = new WordListDomainVocabulary(List.of("postgres", "connections"));
            var suggester = new TfIdfResolutionSuggester(new FixedSimilarity().score("DB", 0.5), vocabulary, DEFAULT);
            SuggestionCandidate fix = new SuggestionCandidate(SuggestionSourceType.RESOLVED_TICKET, "DB",
                    IncidentCategory.DATABASE, Priority.P2, "DB pool", "", "Postgres connections leaked", "Capped the pool", "");
            SuggestionQuery query = new SuggestionQuery(TICKET, IncidentCategory.DATABASE,
                    "Postgres refuses connections", Set.of(TICKET));

            assertThat(suggester.suggest(query, List.of(fix)).get(0).matchedTerms()).containsExactly("connections", "postgres");
        }

        @Test
        @DisplayName("The explanation is cut to explanation-terms and is unmodifiable")
        void explanationIsBounded() {
            var policy = new SuggestionPolicy(3, 0.20, 1, true, 200, 1);
            var suggester = new TfIdfResolutionSuggester(new FixedSimilarity().score("A", 0.5), VOCABULARY, policy);

            List<String> terms = suggester.suggest(query(), List.of(ticket("A", IncidentCategory.NETWORK, "A")))
                    .get(0).matchedTerms();

            assertThat(terms).containsExactly("gateway");
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> terms.add("x"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Test
    @DisplayName("With the real TF-IDF measure a VPN ticket finds the VPN fix and not the printer fix")
    void realMeasureFindsTheRelevantFix() {
        TfIdfVectorizer vectorizer = new TfIdfVectorizer();
        vectorizer.train(List.of(
                "vpn gateway certificate expired remote users cannot connect",
                "printer toner empty pages are blank",
                "dns resolution fails for internal hosts",
                "office printer jams on every page"));
        var suggester = new TfIdfResolutionSuggester(new CosineTfIdfSimilarity(vectorizer), VOCABULARY, DEFAULT);
        SuggestionCandidate printer = new SuggestionCandidate(SuggestionSourceType.RESOLVED_TICKET, "PRN",
                IncidentCategory.NETWORK, Priority.P4, "Printer prints blank pages", "", "Toner empty", "Replaced toner", "");

        var result = suggester.suggest(query(), List.of(ticket("VPN", IncidentCategory.NETWORK, "VPN gateway drops users"), printer));

        assertThat(ids(result)).containsExactly("VPN");
        assertThat(result.get(0).similarity()).isBetween(0.20, 1.0);
    }

    @Test
    @DisplayName("No candidates, null candidates or an empty ticket text give an empty list")
    void emptyInputs() {
        var suggester = new TfIdfResolutionSuggester(new FixedSimilarity(), VOCABULARY, DEFAULT);

        assertThat(suggester.suggest(query(), List.of())).isEmpty();
        assertThat(suggester.suggest(query(), null)).isEmpty();
        assertThat(suggester.suggest(new SuggestionQuery(TICKET, IncidentCategory.NETWORK, "  ", Set.of()),
                List.of(ticket("A", IncidentCategory.NETWORK, "A")))).isEmpty();
    }
}
