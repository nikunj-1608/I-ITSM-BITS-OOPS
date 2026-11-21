package com.iitsm.domain.strategy.similarity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.iitsm.domain.strategy.nlp.WordListDomainVocabulary;
import com.iitsm.domain.strategy.nlp.DomainVocabulary;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-016: threshold plus content guard. Texts come from the H1 Phase 0 pair set. */
class DuplicateMatchPolicyTest {

    /** Identical texts score 0.95, anything else 0.10: isolates the policy from the real vectorizer. */
    private static final SimilarityMeasure FIXED = new SimilarityMeasure() {
        @Override
        public double calculateSimilarity(String textA, String textB) {
            return textA.equals(textB) ? 0.95 : 0.10;
        }

        @Override
        public String getMeasureName() {
            return "FIXED";
        }
    };
    private final DomainVocabulary lexicon = WordListDomainVocabulary.duplicateVocabulary();

    private static CosineTfIdfSimilarity cosine() {
        com.iitsm.domain.strategy.nlp.TfIdfVectorizer vectorizer = new com.iitsm.domain.strategy.nlp.TfIdfVectorizer();
        vectorizer.train(List.of(
                "vpn gateway timeout anyconnect", "keyboard laptop battery screen", "payroll portal error salary",
                "problem since this morning nothing obvious", "database deadlock query"));
        return new CosineTfIdfSimilarity(vectorizer);
    }

    @Test
    @DisplayName("Accepts a pair that reaches the threshold and shares a domain term")
    void acceptsPairWithDomainTerm() {
        DuplicateMatchPolicy policy = new DuplicateMatchPolicy(FIXED, lexicon, 0.30, 1);

        assertThat(policy.match("VPN gateway timeout again", "VPN gateway timeout again")).hasValue(0.95);
    }

    @Test
    @DisplayName("Rejects a pair below the threshold even when it shares domain terms")
    void rejectsBelowThreshold() {
        DuplicateMatchPolicy policy = new DuplicateMatchPolicy(FIXED, lexicon, 0.30, 1);

        assertThat(policy.match("VPN gateway timeout", "VPN certificate expired")).isEmpty();
    }

    @Test
    @DisplayName("Rejects near-empty tickets that score high but share only filler words")
    void rejectsHighScoreWithoutDomainTerm() {
        DuplicateMatchPolicy policy = new DuplicateMatchPolicy(FIXED, lexicon, 0.30, 1);
        String generic = "Problem since this morning, nothing else to add right now sorry";

        assertThat(policy.match(generic, generic)).isEmpty();
    }

    @Test
    @DisplayName("A minimum of zero turns the content guard off")
    void zeroMinimumDisablesGuard() {
        DuplicateMatchPolicy policy = new DuplicateMatchPolicy(FIXED, lexicon, 0.30, 0);
        String generic = "Problem since this morning, nothing else to add right now sorry";

        assertThat(policy.match(generic, generic)).hasValue(0.95);
    }

    @Test
    @DisplayName("Real similarity: paraphrases link; same-category different faults and filler-only pairs do not")
    void realSimilarityBehaviour() {
        DuplicateMatchPolicy policy = new DuplicateMatchPolicy(cosine(), lexicon, 0.30, 1);

        assertThat(policy.match("VPN keeps timing out AnyConnect VPN fails with a gateway timeout",
                "Cannot connect to corporate VPN, the VPN gateway timed out")).isPresent();
        assertThat(policy.match("Cannot connect to corporate VPN since this morning",
                "VPN certificate expired warning on startup")).isEmpty();
        assertThat(policy.match("Problem since this morning, nothing obvious to add",
                "Problem since this morning, nothing obvious to add today")).isEmpty();
    }

    @Test
    @DisplayName("Rejects invalid configuration")
    void validatesConfiguration() {
        assertThatThrownBy(() -> new DuplicateMatchPolicy(FIXED, lexicon, 0.0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DuplicateMatchPolicy(FIXED, lexicon, 1.1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DuplicateMatchPolicy(FIXED, lexicon, 0.3, -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
