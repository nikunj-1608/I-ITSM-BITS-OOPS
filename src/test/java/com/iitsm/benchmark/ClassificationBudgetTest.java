package com.iitsm.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitsm.domain.strategy.classification.ClassificationResult;
import com.iitsm.domain.strategy.classification.CompositeClassifier;
import com.iitsm.domain.strategy.classification.KeywordClassifier;
import com.iitsm.domain.strategy.classification.NaiveBayesClassifier;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Remaining-defects plan, Phase F (ADR-034, decision D14): the acceptance budget for the classifier, without Spring.
 * The production classifier is built the way {@code IntelligenceConfig} builds it (keyword rules, Naive Bayes trained
 * on {@code sim/corpus.csv}, composite) and measured on the 36 held-out tickets and the audit examples.
 *
 * <p>Budget, taken from the baseline of 2026-11-05 (held-out 32/36 correct, 0/36 to triage; audit examples 8/12):
 * held-out accuracy not lower, every audit example met, at most 10 points more of the held-out tickets in triage
 * (3 of 36). A labelled category counts as met when it is predicted, at any confidence; a TRIAGE label when the ticket
 * ends below the auto-assignment gate; the label "SECURITY|TRIAGE" (A01) when it is SECURITY or in triage.
 */
class ClassificationBudgetTest {

    static final int HELD_OUT_CORRECT_BASELINE = 32;
    static final int HELD_OUT_TRIAGE_BUDGET = 3;
    private static final double GATE = ClassificationResult.TRIAGE_THRESHOLD;

    private static CompositeClassifier classifier;

    @BeforeAll
    static void build() throws IOException {
        NaiveBayesClassifier naiveBayes = new NaiveBayesClassifier();
        try (InputStream corpus = ClassificationBudgetTest.class.getResourceAsStream("/sim/corpus.csv")) {
            naiveBayes.trainFromCsv(corpus);
        }
        classifier = new CompositeClassifier(new KeywordClassifier(), naiveBayes);
    }

    record Row(BenchmarkData.LabelledTicket ticket, ClassificationResult result) {
        boolean triaged() {
            return result.confidence() < GATE;
        }

        boolean met() {
            return ticket.isMetBy(result.category(), triaged());
        }
    }

    private static List<Row> run(List<BenchmarkData.LabelledTicket> tickets) {
        List<Row> rows = new ArrayList<>();
        for (BenchmarkData.LabelledTicket t : tickets) {
            rows.add(new Row(t, classifier.classify(t.title(), t.description())));
        }
        return rows;
    }

    @Test
    @DisplayName("D14: held-out accuracy is not lower than the baseline and at most 3 of 36 tickets go to triage")
    void heldOutBudget() {
        List<Row> rows = run(BenchmarkData.heldOutTickets());

        assertThat(rows.stream().filter(Row::met).count()).as("held-out tickets met").isGreaterThanOrEqualTo(HELD_OUT_CORRECT_BASELINE);
        assertThat(rows.stream().filter(Row::triaged).count()).as("held-out tickets in triage").isLessThanOrEqualTo(HELD_OUT_TRIAGE_BUDGET);
    }

    @Test
    @DisplayName("D14: every audit example is met")
    void auditExamples() {
        List<Row> rows = run(BenchmarkData.auditExamples());

        assertThat(rows.stream().filter(r -> !r.met()).map(r -> r.ticket().id())).as("audit examples missed").isEmpty();
    }
}
