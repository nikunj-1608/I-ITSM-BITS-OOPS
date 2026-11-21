package com.iitsm.domain.strategy.nlp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Remaining-defects plan, Phase F (ADR-035): the vocabulary of duplicate detection is a list of its own, frozen apart
 * from the classifier's lexicon. This replaces {@code KeywordClassifierDomainTermTest}, which tested the same behaviour
 * on the classifier.
 */
class WordListDomainVocabularyTest {

    @Test
    @DisplayName("Listed words and their stems are domain terms; filler words are not")
    void recognisesDomainTermsAndStems() {
        DomainVocabulary vocabulary = WordListDomainVocabulary.duplicateVocabulary();

        assertThat(vocabulary.isDomainTerm("vpn")).isTrue();
        assertThat(vocabulary.isDomainTerm("keyboard")).isTrue();
        assertThat(TextNormalizer.tokenize("keyboards freezing")).allMatch(vocabulary::isDomainTerm);
        assertThat(vocabulary.isDomainTerm("problem")).isFalse();
        assertThat(vocabulary.isDomainTerm("morn")).isFalse();
        assertThat(vocabulary.isDomainTerm(null)).isFalse();
    }

    @Test
    @DisplayName("ADR-035: the frozen list keeps the generic words the classifier lexicon drops")
    void frozenListKeepsTheGenericWords() {
        DomainVocabulary vocabulary = WordListDomainVocabulary.duplicateVocabulary();

        for (String generic : List.of("locked", "pin", "power", "port", "service", "order", "connection", "switch")) {
            assertThat(vocabulary.isDomainTerm(TextNormalizer.stem(generic))).as(generic).isTrue();
        }
    }

    @Test
    @DisplayName("A list read from a stream ignores comments and blank lines and lower-cases words")
    void readsAStream() {
        String list = "# comment\n\nVPN\n  Router  \n";

        WordListDomainVocabulary vocabulary = WordListDomainVocabulary.fromStream(
                new ByteArrayInputStream(list.getBytes(StandardCharsets.UTF_8)));

        assertThat(vocabulary.isDomainTerm("vpn")).isTrue();
        assertThat(vocabulary.isDomainTerm("router")).isTrue();
        assertThat(vocabulary.isDomainTerm("comment")).isFalse();
    }

    @Test
    @DisplayName("The shipped list covers every word of the classifier lexicon as it was before the split")
    void shippedListIsComplete() {
        assertThat(WordListDomainVocabulary.duplicateVocabulary().size()).isGreaterThanOrEqualTo(201);
    }
}
