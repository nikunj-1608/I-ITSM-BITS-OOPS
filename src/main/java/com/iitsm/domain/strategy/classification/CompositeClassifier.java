package com.iitsm.domain.strategy.classification;

import com.iitsm.domain.model.IncidentCategory;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Pattern: Composite / Strategy.
 * Orchestrates a multi-stage classification pipeline combining rule-based keyword extraction and probabilistic Naive Bayes.
 * Automatically flags low-confidence classifications for human-in-the-loop administrator triage.
 */
public class CompositeClassifier implements IncidentClassifier {

    private static final String CLASSIFIER_NAME = "COMPOSITE_CLASSIFIER";
    private static final double HIGH_KEYWORD_THRESHOLD = 0.85;
    private static final double CONFIDENT_THRESHOLD = ClassificationResult.TRIAGE_THRESHOLD;
    private static final double WEAK_KEYWORD_MAX = 0.50;
    private static final double NO_KEYWORD_MATCH_MAX = 0.20;
    private static final double TRIAGE_CAP = 0.55;
    private static final double AGREEMENT_BOOST_FACTOR = 1.15;
    private static final double AGREEMENT_BOOST_BONUS = 0.10;
    private static final double MAX_CONFIDENCE = 0.99;

    private final KeywordClassifier keywordClassifier;
    private final NaiveBayesClassifier naiveBayesClassifier;

    public CompositeClassifier(KeywordClassifier keywordClassifier, NaiveBayesClassifier naiveBayesClassifier) {
        this.keywordClassifier = Objects.requireNonNull(keywordClassifier, "keywordClassifier cannot be null");
        this.naiveBayesClassifier = Objects.requireNonNull(naiveBayesClassifier, "naiveBayesClassifier cannot be null");
    }

    @Override
    public ClassificationResult classify(String title, String description) {
        ClassificationResult keywordResult = keywordClassifier.classify(title, description);

        // 1. If keyword classifier has very strong confidence, adopt it immediately
        if (keywordResult.confidence() >= HIGH_KEYWORD_THRESHOLD) {
            return keywordResult;
        }

        // 2. Evaluate Naive Bayes classifier
        ClassificationResult nbResult = naiveBayesClassifier.classify(title, description);

        // 3. Fusion logic
        List<String> combinedRationale = new ArrayList<>();
        combinedRationale.addAll(keywordResult.rationale());
        combinedRationale.addAll(nbResult.rationale());

        IncidentCategory finalCategory;
        double finalConfidence;

        boolean modelsAgree = (keywordResult.category() == nbResult.category());

        if (modelsAgree) {
            finalCategory = nbResult.category();
            if (keywordResult.confidence() >= CONFIDENT_THRESHOLD && nbResult.confidence() >= CONFIDENT_THRESHOLD) {
                finalConfidence = Math.min(MAX_CONFIDENCE,
                        Math.max(nbResult.confidence(), keywordResult.confidence()) * AGREEMENT_BOOST_FACTOR
                                + AGREEMENT_BOOST_BONUS);
            } else {
                finalConfidence = Math.max(nbResult.confidence(), keywordResult.confidence());
            }
            combinedRationale.add("Keyword and Naive Bayes models in agreement: " + finalCategory);
        } else if (nbResult.confidence() >= keywordResult.confidence()) {
            finalCategory = nbResult.category();
            finalConfidence = nbResult.confidence();
            combinedRationale.add("Selected Naive Bayes prediction over keyword classifier");
        } else {
            finalCategory = keywordResult.category();
            finalConfidence = keywordResult.confidence();
            combinedRationale.add("Selected Keyword prediction over Naive Bayes classifier");
        }

        // Triage routing: cap below the auto-assign threshold when evidence is weak. Naive Bayes alone is
        // overconfident on vague text (0.8-0.97 observed with zero keyword hits), so it must be corroborated:
        // 1. No domain keyword matched at all, OR
        // 2. A single keyword matched outside the title (<= 0.50), OR
        // 3. Models disagree and keyword support is below the confidence threshold, OR
        // 4. Neither model reaches the confidence threshold.
        double keywordConfidence = keywordResult.confidence();
        if (keywordConfidence <= NO_KEYWORD_MATCH_MAX) {
            finalConfidence = Math.min(finalConfidence, TRIAGE_CAP);
            combinedRationale.add("No explicit domain keywords matched; capped at 0.55 for human triage");
        } else if (keywordConfidence <= WEAK_KEYWORD_MAX) {
            finalConfidence = Math.min(finalConfidence, TRIAGE_CAP);
            combinedRationale.add("Weak keyword support (confidence <= 0.50); capped at 0.55 for human triage");
        } else if (!modelsAgree && keywordConfidence < CONFIDENT_THRESHOLD) {
            finalConfidence = Math.min(finalConfidence, TRIAGE_CAP);
            combinedRationale.add("Models in disagreement with weak keyword support; capped at 0.55 for human triage");
        } else if (keywordConfidence < CONFIDENT_THRESHOLD && nbResult.confidence() < CONFIDENT_THRESHOLD) {
            finalConfidence = Math.min(finalConfidence, TRIAGE_CAP);
            combinedRationale.add("Both models below confidence threshold; capped at 0.55 for human triage");
        }
        return ClassificationResult.of(finalCategory, finalConfidence, CLASSIFIER_NAME, combinedRationale);
    }

    @Override
    public String getName() {
        return CLASSIFIER_NAME;
    }
}

