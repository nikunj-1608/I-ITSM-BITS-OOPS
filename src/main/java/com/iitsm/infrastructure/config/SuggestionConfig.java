package com.iitsm.infrastructure.config;

import com.iitsm.domain.strategy.nlp.DomainVocabulary;
import com.iitsm.domain.strategy.similarity.SimilarityMeasure;
import com.iitsm.domain.strategy.suggestion.ResolutionSuggester;
import com.iitsm.domain.strategy.suggestion.SuggestionPolicy;
import com.iitsm.domain.strategy.suggestion.TfIdfResolutionSuggester;
import com.iitsm.domain.strategy.suggestion.VoteBand;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires resolution suggestions (ADR-040): the policy from {@code iitsm.suggestions.*} and the TF-IDF suggester over the
 * shared {@link SimilarityMeasure} and duplicate vocabulary. Another ranking is plugged in by replacing the
 * {@link ResolutionSuggester} bean.
 */
@Configuration
public class SuggestionConfig {

    @Bean
    public SuggestionPolicy suggestionPolicy(
            @Value("${iitsm.suggestions.max-results:3}") int maxResults,
            @Value("${iitsm.suggestions.min-similarity:0.20}") double minSimilarity,
            @Value("${iitsm.suggestions.min-shared-terms:1}") int minSharedTerms,
            @Value("${iitsm.suggestions.same-category-only:true}") boolean sameCategoryOnly,
            @Value("${iitsm.suggestions.candidate-window:200}") int candidateWindow,
            @Value("${iitsm.suggestions.explanation-terms:5}") int explanationTerms,
            @Value("${iitsm.suggestions.article-window:200}") int articleWindow) {
        return new SuggestionPolicy(maxResults, minSimilarity, minSharedTerms, sameCategoryOnly, candidateWindow,
                explanationTerms, articleWindow);
    }

    /** ADR-041: votes move a score by at most this fraction either way (0.2: factor strictly between 0.8 and 1.2). */
    @Bean
    public VoteBand suggestionVoteBand(@Value("${iitsm.suggestions.vote-weight:0.20}") double voteWeight) {
        return new VoteBand(voteWeight);
    }

    @Bean
    public ResolutionSuggester resolutionSuggester(
            SimilarityMeasure similarityMeasure, DomainVocabulary domainVocabulary, SuggestionPolicy suggestionPolicy,
            VoteBand suggestionVoteBand) {
        return new TfIdfResolutionSuggester(similarityMeasure, domainVocabulary, suggestionPolicy, suggestionVoteBand);
    }
}
