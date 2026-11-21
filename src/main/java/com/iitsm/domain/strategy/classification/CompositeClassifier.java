package com.iitsm.domain.strategy.classification;

import com.iitsm.domain.model.IncidentCategory;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

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
    /**
     * Distinct keyword terms of one category, with more than any other category, that are decisive against a Naive Bayes
     * answer no keyword supports (ADR-052): two independent lexicon words outweigh a statistical guess without evidence.
     */
    private static final int DECISIVE_KEYWORD_TERMS = 2;

    private final KeywordClassifier keywordClassifier;
    private final NaiveBayesClassifier naiveBayesClassifier;

    public CompositeClassifier(KeywordClassifier keywordClassifier, NaiveBayesClassifier naiveBayesClassifier) {
        this.keywordClassifier = Objects.requireNonNull(keywordClassifier, "keywordClassifier cannot be null");
        this.naiveBayesClassifier = Objects.requireNonNull(naiveBayesClassifier, "naiveBayesClassifier cannot be null");
    }

    @Override
    public ClassificationResult classify(String title, String description) {
        ClassificationResult keywordResult = keywordClassifier.classify(title, description);

        // 1. If keyword classifier has very strong confidence, adopt it immediately, unless a security indicator
        //    appears in a ticket that another category wins (a person decides, step 4 below)
        if (keywordResult.confidence() >= HIGH_KEYWORD_THRESHOLD && !isSecurityAlertForOtherCategory(keywordResult)) {
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

        // A keyword tie is split evidence: Naive Bayes may pick any of the tied categories (and then the models agree),
        // unless the title names exactly one of them and Naive Bayes picks another (ADR-052)
        Optional<IncidentCategory> titleLedTie = titleLedTieOverruled(keywordResult, nbResult, title);
        if (titleLedTie.isPresent()) {
            combinedRationale.add("Keyword tie: the title names " + titleLedTie.get() + " but Naive Bayes chose "
                    + nbResult.category() + "; capped at 0.55 for human triage");
            return capForSecurity(withKeywordEvidence(ClassificationResult.of(titleLedTie.get(),
                    Math.min(keywordResult.confidence(), TRIAGE_CAP), CLASSIFIER_NAME, combinedRationale), keywordResult),
                    keywordResult);
        }
        boolean modelsAgree = keywordResult.category() == nbResult.category()
                || keywordResult.tiedWith().contains(nbResult.category());

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
        } else if (!naiveBayesIsCorroborated(keywordResult, nbResult) && keywordResult.confidence() >= CONFIDENT_THRESHOLD) {
            // Confident keywords against a Naive Bayes answer that no keyword supports: the lexicon-backed label wins
            // and the cap below sends the ticket to a person
            finalCategory = keywordResult.category();
            finalConfidence = keywordResult.confidence();
            combinedRationale.add("Selected Keyword prediction: no keyword supports the Naive Bayes answer");
        } else if (hasUncontestedKeywordLabel(keywordResult, nbResult)) {
            // Weak keywords against a Naive Bayes answer that no keyword supports: the ticket is capped below, and the
            // category a person sees as the suggestion is the one the text names, not the unsupported guess (ADR-052)
            finalCategory = keywordResult.category();
            finalConfidence = keywordResult.confidence();
            combinedRationale.add("Selected Keyword prediction: the Naive Bayes answer has no keyword support");
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
        } else if (!modelsAgree && !naiveBayesIsCorroborated(keywordResult, nbResult) && keywordsDecisive(keywordResult)) {
            // Decisive keywords against a Naive Bayes answer that no keyword supports: the keyword label stands (ADR-052)
            combinedRationale.add("Decisive keyword evidence (" + keywordResult.supportFor(keywordResult.category())
                    + " terms, more than any other category) outweighs a Naive Bayes answer without keyword support");
        } else if (!modelsAgree && !naiveBayesIsCorroborated(keywordResult, nbResult)) {
            // Confident but not decisive keywords against a Naive Bayes answer that no keyword supports: neither may win
            // unreviewed (ADR-034). Since ADR-052 every keyword result at or above the gate has two or more terms and
            // leads every other category, so this is a guard for a future keyword rule; a Naive Bayes answer with at
            // least one supporting keyword is trusted, as before.
            finalConfidence = Math.min(finalConfidence, TRIAGE_CAP);
            combinedRationale.add("Keyword and Naive Bayes models disagree and no keyword supports the Naive Bayes answer; "
                    + "capped at 0.55 for human triage");
        } else if (keywordConfidence < CONFIDENT_THRESHOLD && nbResult.confidence() < CONFIDENT_THRESHOLD) {
            finalConfidence = Math.min(finalConfidence, TRIAGE_CAP);
            combinedRationale.add("Both models below confidence threshold; capped at 0.55 for human triage");
        }
        return capForSecurity(withKeywordEvidence(
                ClassificationResult.of(finalCategory, finalConfidence, CLASSIFIER_NAME, combinedRationale), keywordResult),
                keywordResult);
    }

    /**
     * The fused result carries the keyword evidence (support per category, security indicator), so a later step can see
     * what the keywords found without running the keyword classifier again (ADR-052, Phase 5).
     */
    private static ClassificationResult withKeywordEvidence(ClassificationResult fused, ClassificationResult keywordResult) {
        return ClassificationResult.of(fused.category(), fused.confidence(), fused.classifierName(), fused.rationale(),
                keywordResult.keywordSupport(), keywordResult.securityAlert());
    }

    /** A security indicator in a ticket another category wins is capped, so a person decides (ADR-034). */
    private static ClassificationResult capForSecurity(ClassificationResult result, ClassificationResult keywordResult) {
        if (result.category() == IncidentCategory.SECURITY || !keywordResult.securityAlert()) {
            return result;
        }
        List<String> rationale = new ArrayList<>(result.rationale());
        rationale.add("Security indicator in a ticket classified as " + result.category()
                + "; capped at 0.55 so a person decides whether it is a security incident");
        return ClassificationResult.of(result.category(), Math.min(result.confidence(), TRIAGE_CAP), CLASSIFIER_NAME,
                rationale, result.keywordSupport(), result.securityAlert());
    }

    /**
     * The keywords tie, exactly one of the tied categories has a term in the title, and Naive Bayes picks another tied
     * category: the title's category, to be confirmed by a person ("Wifi not working ... on my laptop" is not a laptop
     * fault). Empty when there is no tie or the title does not single out one category.
     */
    private Optional<IncidentCategory> titleLedTieOverruled(
            ClassificationResult keywordResult, ClassificationResult nbResult, String title) {
        if (keywordResult.tiedWith().isEmpty()) {
            return Optional.empty();
        }
        Set<IncidentCategory> tied = new HashSet<>(keywordResult.tiedWith());
        tied.add(keywordResult.category());
        Set<IncidentCategory> titled = new HashSet<>(keywordClassifier.titleCategories(title));
        titled.retainAll(tied);
        if (titled.size() != 1 || titled.contains(nbResult.category()) || !tied.contains(nbResult.category())) {
            return Optional.empty();
        }
        return titled.stream().findFirst();
    }

    private static boolean isSecurityAlertForOtherCategory(ClassificationResult keywordResult) {
        return keywordResult.securityAlert() && keywordResult.category() != IncidentCategory.SECURITY;
    }

    /** At least {@value #DECISIVE_KEYWORD_TERMS} distinct terms of the keyword category, more than any other category. */
    private static boolean keywordsDecisive(ClassificationResult keywordResult) {
        return keywordResult.supportFor(keywordResult.category()) >= DECISIVE_KEYWORD_TERMS
                && keywordResult.leadsAllOtherCategories();
    }

    /**
     * The keywords name one category without a tie, more strongly than a single description word (a title term or two
     * terms), and not a single keyword supports the Naive Bayes category. A lone description word ("after a firmware
     * update") is too weak to relabel the ticket (ADR-052).
     */
    private static boolean hasUncontestedKeywordLabel(ClassificationResult keywordResult, ClassificationResult nbResult) {
        return keywordResult.confidence() > WEAK_KEYWORD_MAX && keywordResult.leadsAllOtherCategories()
                && keywordResult.supportFor(nbResult.category()) == 0;
    }

    /** Naive Bayes is confident and at least one lexicon term backs its category. */
    private static boolean naiveBayesIsCorroborated(ClassificationResult keywordResult, ClassificationResult nbResult) {
        return nbResult.confidence() >= CONFIDENT_THRESHOLD && keywordResult.supportFor(nbResult.category()) >= 1;
    }

    @Override
    public String getName() {
        return CLASSIFIER_NAME;
    }
}

