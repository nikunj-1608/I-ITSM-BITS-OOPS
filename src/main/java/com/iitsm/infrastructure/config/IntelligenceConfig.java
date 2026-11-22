package com.iitsm.infrastructure.config;

import com.iitsm.domain.policy.RoutingPolicy;
import com.iitsm.domain.strategy.assignment.AssignmentStrategyRegistry;
import com.iitsm.domain.strategy.assignment.CandidateFilter;
import com.iitsm.domain.strategy.assignment.ServiceExperienceSource;
import com.iitsm.domain.strategy.assignment.WeightedCompositeStrategy;
import com.iitsm.domain.strategy.classification.CompositeClassifier;
import com.iitsm.domain.strategy.classification.KeywordClassifier;
import com.iitsm.domain.strategy.classification.NaiveBayesClassifier;
import com.iitsm.domain.strategy.classification.ServiceMatcher;
import com.iitsm.domain.strategy.nlp.DomainVocabulary;
import com.iitsm.domain.strategy.nlp.TfIdfVectorizer;
import com.iitsm.domain.strategy.nlp.WordListDomainVocabulary;
import com.iitsm.domain.strategy.priority.CompositePriorityCalculator;
import com.iitsm.domain.strategy.similarity.CosineTfIdfSimilarity;
import com.iitsm.domain.strategy.similarity.DuplicateMatchPolicy;
import com.iitsm.domain.strategy.similarity.SimilarityMeasure;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;

/**
 * Spring configuration initializing intelligence subsystem components (NLP, ML classifiers, priority engine, assignment strategies).
 */
@Configuration
public class IntelligenceConfig {

    private static final Logger log = LoggerFactory.getLogger(IntelligenceConfig.class);
    private static final String CORPUS_PATH = "sim/corpus.csv";

    @Bean
    public TfIdfVectorizer tfIdfVectorizer() {
        TfIdfVectorizer vectorizer = new TfIdfVectorizer();
        List<String> documents = new ArrayList<>();

        try {
            ClassPathResource resource = new ClassPathResource(CORPUS_PATH);
            if (resource.exists()) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    boolean header = true;
                    while ((line = reader.readLine()) != null) {
                        if (header) {
                            header = false;
                            continue;
                        }
                        String[] parts = line.split(",", 3);
                        if (parts.length >= 2) {
                            documents.add((parts.length == 3) ? parts[1] + " " + parts[2] : parts[1]);
                        }
                    }
                }
                vectorizer.train(documents);
                log.info("Trained TfIdfVectorizer on {} corpus documents with vocabulary size {}",
                        documents.size(), vectorizer.getVocabularySize());
            } else {
                log.warn("Corpus file {} not found; TfIdfVectorizer initialized without baseline corpus", CORPUS_PATH);
            }
        } catch (Exception ex) {
            log.error("Failed to train TfIdfVectorizer from corpus: {}", ex.getMessage(), ex);
        }

        return vectorizer;
    }

    @Bean
    public KeywordClassifier keywordClassifier() {
        return new KeywordClassifier();
    }

    @Bean
    public NaiveBayesClassifier naiveBayesClassifier() {
        NaiveBayesClassifier classifier = new NaiveBayesClassifier();
        try {
            ClassPathResource resource = new ClassPathResource(CORPUS_PATH);
            if (resource.exists()) {
                try (InputStream is = resource.getInputStream()) {
                    classifier.trainFromCsv(is);
                    log.info("Trained NaiveBayesClassifier on corpus with vocabulary size {}",
                            classifier.getVocabularySize());
                }
            } else {
                log.warn("Corpus file {} not found; NaiveBayesClassifier initialized untrained", CORPUS_PATH);
            }
        } catch (Exception ex) {
            log.error("Failed to train NaiveBayesClassifier from corpus: {}", ex.getMessage(), ex);
        }
        return classifier;
    }

    @Bean
    @Primary
    public CompositeClassifier compositeClassifier(
            KeywordClassifier keywordClassifier,
            NaiveBayesClassifier naiveBayesClassifier) {
        return new CompositeClassifier(keywordClassifier, naiveBayesClassifier);
    }

    @Bean
    public ServiceMatcher serviceMatcher() {
        return new ServiceMatcher();
    }

    @Bean
    public CompositePriorityCalculator compositePriorityCalculator() {
        return new CompositePriorityCalculator();
    }

    @Bean
    public CandidateFilter candidateFilter() {
        return new CandidateFilter();
    }

    /** Which team owns a ticket: the one that handles its category (ADR-036). */
    @Bean
    public RoutingPolicy routingPolicy() {
        return new RoutingPolicy();
    }

    /**
     * The registry of assignment strategies. The default strategy, WEIGHTED_COMPOSITE, gets the service-experience factor
     * of ADR-044 (Phase L4) when {@code iitsm.assignment.service-experience-weight} is above 0: the three historic weights
     * are scaled by {@code 1 - w} and the factor gets {@code w}; with 0 the strategy is exactly the historic one.
     */
    @Bean
    public AssignmentStrategyRegistry assignmentStrategyRegistry(
            ServiceExperienceSource serviceExperienceSource,
            @Value("${iitsm.assignment.service-experience-weight:0.10}") double serviceExperienceWeight,
            @Value("${iitsm.assignment.service-experience-full-score-tickets:5}") int fullScoreTickets) {
        if (!(serviceExperienceWeight >= 0.0) || serviceExperienceWeight > WeightedCompositeStrategy.MAX_SERVICE_EXPERIENCE_WEIGHT) {
            throw new IllegalArgumentException("iitsm.assignment.service-experience-weight must be from 0 to "
                    + WeightedCompositeStrategy.MAX_SERVICE_EXPERIENCE_WEIGHT + " (was " + serviceExperienceWeight + ")");
        }
        log.info("Assignment: service-experience weight {} (full score at {} resolved tickets)", serviceExperienceWeight, fullScoreTickets);
        return new AssignmentStrategyRegistry(WeightedCompositeStrategy.withServiceExperience(
                serviceExperienceSource, serviceExperienceWeight, fullScoreTickets));
    }

    /** The technical words duplicate detection needs two tickets to share; frozen apart from the classifier (ADR-035). */
    @Bean
    public DomainVocabulary domainVocabulary() {
        return WordListDomainVocabulary.duplicateVocabulary();
    }

    @Bean
    public DuplicateMatchPolicy duplicateMatchPolicy(
            SimilarityMeasure similarityMeasure,
            DomainVocabulary domainVocabulary,
            @Value("${iitsm.duplicates.similarity-threshold:0.30}") double similarityThreshold,
            @Value("${iitsm.duplicates.min-shared-terms:1}") int minSharedTerms) {
        return new DuplicateMatchPolicy(similarityMeasure, domainVocabulary, similarityThreshold, minSharedTerms);
    }

    @Bean
    public SimilarityMeasure similarityMeasure(TfIdfVectorizer vectorizer) {
        return new CosineTfIdfSimilarity(vectorizer);
    }
}

