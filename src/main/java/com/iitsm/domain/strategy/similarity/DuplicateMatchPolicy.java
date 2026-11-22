package com.iitsm.domain.strategy.similarity;

import com.iitsm.domain.strategy.nlp.DomainVocabulary;
import com.iitsm.domain.strategy.nlp.TextNormalizer;
import java.util.HashSet;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * Pattern: Strategy (decision policy).
 * Decides whether two ticket texts are duplicates (ADR-016). Precision comes first: a pair qualifies only when its
 * similarity reaches the threshold <em>and</em> the two texts share at least a minimum number of domain terms, so
 * near-empty tickets that merely share filler words ("problem since this morning") are never linked.
 */
public final class DuplicateMatchPolicy {

    private final SimilarityMeasure similarityMeasure;
    private final DomainVocabulary vocabulary;
    private final double threshold;
    private final int minSharedDomainTerms;

    /**
     * @param threshold            similarity in (0, 1] a pair must reach
     * @param minSharedDomainTerms domain terms both texts must contain; 0 disables the content guard
     */
    public DuplicateMatchPolicy(
            SimilarityMeasure similarityMeasure,
            DomainVocabulary vocabulary,
            double threshold,
            int minSharedDomainTerms) {
        this.similarityMeasure = Objects.requireNonNull(similarityMeasure, "similarityMeasure cannot be null");
        this.vocabulary = Objects.requireNonNull(vocabulary, "vocabulary cannot be null");
        if (threshold <= 0.0 || threshold > 1.0) {
            throw new IllegalArgumentException("Duplicate similarity threshold must be in (0, 1]: " + threshold);
        }
        if (minSharedDomainTerms < 0) {
            throw new IllegalArgumentException("Minimum shared domain terms cannot be negative: " + minSharedDomainTerms);
        }
        this.threshold = threshold;
        this.minSharedDomainTerms = minSharedDomainTerms;
    }

    /** The similarity score when the pair qualifies as duplicates, otherwise empty. */
    public OptionalDouble match(String textA, String textB) {
        double similarity = similarityMeasure.calculateSimilarity(textA, textB);
        if (similarity < threshold || sharedDomainTerms(textA, textB) < minSharedDomainTerms) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(similarity);
    }

    public double getThreshold() {
        return threshold;
    }

    public int getMinSharedDomainTerms() {
        return minSharedDomainTerms;
    }

    private int sharedDomainTerms(String textA, String textB) {
        if (minSharedDomainTerms == 0) {
            return 0;
        }
        Set<String> shared = new HashSet<>(TextNormalizer.tokenize(textA));
        shared.retainAll(new HashSet<>(TextNormalizer.tokenize(textB)));
        return (int) shared.stream().filter(vocabulary::isDomainTerm).count();
    }
}
