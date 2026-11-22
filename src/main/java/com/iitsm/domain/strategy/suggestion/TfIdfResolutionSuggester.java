package com.iitsm.domain.strategy.suggestion;

import com.iitsm.domain.strategy.nlp.DomainVocabulary;
import com.iitsm.domain.strategy.nlp.TextNormalizer;
import com.iitsm.domain.strategy.similarity.SimilarityMeasure;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

// OOP (Strategy): one way to suggest fixes; another ResolutionSuggester could be plugged in without touching the service.
/**
 * Pattern: Strategy (concrete). Suggests past fixes by TF-IDF cosine similarity through the wired
 * {@link SimilarityMeasure} (ADR-040). A candidate must pass the hard category filter, must not be the ticket or one of
 * its duplicates, must reach the minimum similarity and must share enough domain terms (the same content guard as
 * duplicate detection, ADR-016); the shared terms are returned as the explanation. Votes (ADR-041) then move the score
 * within the {@link VoteBand}: they reorder qualified candidates before the top-N cut, but the minimum applies to the
 * raw similarity, so votes never let a weak match in or push a strong one out of qualifying.
 */
public final class TfIdfResolutionSuggester implements ResolutionSuggester {

    /**
     * Best vote-adjusted score first, then the higher similarity, then source-id order, so the answer never depends on
     * the database order.
     */
    private static final Comparator<ResolutionSuggestion> BEST_FIRST =
            Comparator.comparingDouble(ResolutionSuggestion::score).reversed()
                    .thenComparing(Comparator.comparingDouble(ResolutionSuggestion::similarity).reversed())
                    .thenComparing(s -> s.source().sourceId());

    private final SimilarityMeasure similarityMeasure;
    private final DomainVocabulary vocabulary;
    private final SuggestionPolicy policy;
    private final VoteBand voteBand;

    public TfIdfResolutionSuggester(
            SimilarityMeasure similarityMeasure, DomainVocabulary vocabulary, SuggestionPolicy policy, VoteBand voteBand) {
        this.similarityMeasure = Objects.requireNonNull(similarityMeasure, "similarityMeasure cannot be null");
        this.vocabulary = Objects.requireNonNull(vocabulary, "vocabulary cannot be null");
        this.policy = Objects.requireNonNull(policy, "policy cannot be null");
        this.voteBand = Objects.requireNonNull(voteBand, "voteBand cannot be null");
    }

    /** Without votes (a neutral band). */
    public TfIdfResolutionSuggester(SimilarityMeasure similarityMeasure, DomainVocabulary vocabulary, SuggestionPolicy policy) {
        this(similarityMeasure, vocabulary, policy, VoteBand.neutral());
    }

    @Override
    public List<ResolutionSuggestion> suggest(
            SuggestionQuery query, Collection<SuggestionCandidate> candidates, VoteTallies votes) {
        Objects.requireNonNull(query, "query cannot be null");
        VoteTallies tallies = votes == null ? VoteTallies.none() : votes;
        if (candidates == null || query.text().isEmpty() || !policy.canSuggestFor(query)) {
            return List.of();
        }
        Map<String, String> queryTerms = domainTermsAsWritten(query.text());
        return candidates.stream()
                .filter(candidate -> !query.excludes(candidate))
                .filter(candidate -> policy.admitsCategory(query, candidate))
                .map(candidate -> score(query, queryTerms, candidate, tallies))
                .flatMap(Optional::stream)
                .sorted(BEST_FIRST)
                .limit(policy.maxResults())
                .collect(Collectors.toUnmodifiableList());
    }

    private Optional<ResolutionSuggestion> score(
            SuggestionQuery query, Map<String, String> queryTerms, SuggestionCandidate candidate, VoteTallies votes) {
        String candidateText = candidate.matchingText();
        Set<String> sharedStems = new HashSet<>(TextNormalizer.tokenize(candidateText));
        sharedStems.retainAll(queryTerms.keySet());
        double similarity = similarityMeasure.calculateSimilarity(query.text(), candidateText);
        if (!policy.accepts(similarity, sharedStems.size())) {
            return Optional.empty();
        }
        List<String> explanation = sharedStems.stream()
                .map(queryTerms::get)
                .collect(Collectors.toCollection(TreeSet::new)).stream()
                .limit(policy.maxExplanationTerms())
                .toList();
        VoteTally tally = votes.of(candidate.key());
        double score = similarity * voteBand.factor(tally);
        return Optional.of(new ResolutionSuggestion(candidate, similarity, score, tally, explanation));
    }

    /**
     * The distinct domain terms of the ticket text: stem to the word as the ticket first wrote it (lower case), so the
     * explanation reads "postgres" and not the stem "postgr".
     */
    private Map<String, String> domainTermsAsWritten(String text) {
        Map<String, String> terms = new HashMap<>();
        for (String word : TextNormalizer.normalize(text).split(" ")) {
            for (String stem : TextNormalizer.tokenize(word)) {
                if (vocabulary.isDomainTerm(stem)) {
                    terms.putIfAbsent(stem, word);
                }
            }
        }
        return terms;
    }
}
