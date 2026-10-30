package com.iitsm.domain.strategy.classification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.iitsm.domain.model.IncidentCategory;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the triage-gating policy of CompositeClassifier: tickets are routed to human triage only on
 * genuinely weak evidence, so confident tickets still reach automatic technician assignment.
 */
class CompositeClassifierTest {

    private static final String TITLE = "title";
    private static final String BODY = "description";

    private static CompositeClassifier realClassifier;

    @BeforeAll
    static void trainRealClassifier() throws Exception {
        NaiveBayesClassifier naiveBayes = new NaiveBayesClassifier();
        try (InputStream corpus = CompositeClassifierTest.class.getClassLoader().getResourceAsStream("sim/corpus.csv")) {
            assertThat(corpus).isNotNull();
            naiveBayes.trainFromCsv(corpus);
        }
        realClassifier = new CompositeClassifier(new KeywordClassifier(), naiveBayes);
    }

    private static ClassificationResult result(IncidentCategory category, double confidence) {
        return ClassificationResult.of(category, confidence, "STUB", List.of("stub"));
    }

    private static ClassificationResult fuse(ClassificationResult keyword, ClassificationResult naiveBayes) {
        KeywordClassifier keywordClassifier = mock(KeywordClassifier.class);
        NaiveBayesClassifier nbClassifier = mock(NaiveBayesClassifier.class);
        when(keywordClassifier.classify(TITLE, BODY)).thenReturn(keyword);
        when(nbClassifier.classify(TITLE, BODY)).thenReturn(naiveBayes);
        return new CompositeClassifier(keywordClassifier, nbClassifier).classify(TITLE, BODY);
    }

    @Test
    @DisplayName("Confident Naive Bayes without any keyword support is capped (NB is overconfident alone)")
    void noKeywordSupportIsCappedDespiteConfidentNaiveBayes() {
        ClassificationResult fused = fuse(
                result(IncidentCategory.APPLICATION, 0.20), result(IncidentCategory.DATABASE, 0.90));

        assertThat(fused.confidence()).isLessThanOrEqualTo(0.55);
        assertThat(fused.requiresTriage()).isTrue();
    }

    @Test
    @DisplayName("Single keyword in the title (0.55) agreeing with Naive Bayes is not capped")
    void singleTitleKeywordAgreeingWithNaiveBayesIsNotCapped() {
        ClassificationResult fused = fuse(
                result(IncidentCategory.NETWORK, 0.55), result(IncidentCategory.NETWORK, 0.75));

        assertThat(fused.category()).isEqualTo(IncidentCategory.NETWORK);
        assertThat(fused.requiresTriage()).isFalse();
    }

    @Test
    @DisplayName("Single keyword outside the title (0.50) is capped even when Naive Bayes agrees")
    void singleBodyKeywordIsCappedEvenWhenNaiveBayesAgrees() {
        ClassificationResult fused = fuse(
                result(IncidentCategory.HARDWARE, 0.50), result(IncidentCategory.HARDWARE, 0.95));

        assertThat(fused.confidence()).isLessThanOrEqualTo(0.55);
        assertThat(fused.requiresTriage()).isTrue();
    }

    @Test
    @DisplayName("Title keyword contradicted by a confident Naive Bayes is capped for triage")
    void titleKeywordContradictedByNaiveBayesIsCapped() {
        ClassificationResult fused = fuse(
                result(IncidentCategory.NETWORK, 0.55), result(IncidentCategory.HARDWARE, 0.95));

        assertThat(fused.confidence()).isLessThanOrEqualTo(0.55);
        assertThat(fused.requiresTriage()).isTrue();
    }

    @Test
    @DisplayName("Weak keyword contradicting Naive Bayes is capped for human triage")
    void weakKeywordContradictingNaiveBayesIsCapped() {
        ClassificationResult fused = fuse(
                result(IncidentCategory.NETWORK, 0.50), result(IncidentCategory.HARDWARE, 0.80));

        assertThat(fused.confidence()).isLessThanOrEqualTo(0.55);
        assertThat(fused.requiresTriage()).isTrue();
    }

    @Test
    @DisplayName("Both models below the confidence threshold are capped for human triage")
    void bothModelsBelowThresholdAreCapped() {
        ClassificationResult fused = fuse(
                result(IncidentCategory.SOFTWARE, 0.50), result(IncidentCategory.SOFTWARE, 0.40));

        assertThat(fused.confidence()).isLessThanOrEqualTo(0.55);
        assertThat(fused.requiresTriage()).isTrue();
    }

    @Test
    @DisplayName("Real models: one-keyword ticket is confident enough for auto-assignment")
    void singleKeywordTicketIsAutoAssignable() {
        ClassificationResult fused = realClassifier.classify(
                "VPN keeps dropping again",
                "It keeps dropping again today and I really cannot work like this, please help me soon.");

        assertThat(fused.category()).isEqualTo(IncidentCategory.NETWORK);
        assertThat(fused.requiresTriage()).isFalse();
    }

    @Test
    @DisplayName("Real models: vague ticket still goes to human triage")
    void vagueTicketGoesToTriage() {
        ClassificationResult fused = realClassifier.classify(
                "Something is not working properly",
                "Things seem slow and strange since yesterday afternoon, please have a look when you get some time.");

        assertThat(fused.requiresTriage()).isTrue();
    }

    @Test
    @DisplayName("Real models: out-of-vocabulary gibberish goes to human triage")
    void gibberishGoesToTriage() {
        ClassificationResult fused = realClassifier.classify(
                "Xyzw9999 totallyunheardofword", "Asdfghjk zxcvbnm qwertyuiop1234 plmokn");

        assertThat(fused.requiresTriage()).isTrue();
    }
}
