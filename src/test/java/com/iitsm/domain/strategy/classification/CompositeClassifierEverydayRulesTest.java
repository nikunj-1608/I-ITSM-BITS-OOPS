package com.iitsm.domain.strategy.classification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.iitsm.domain.model.IncidentCategory;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ADR-052, Phase 3: the fusion rules added for everyday tickets. Decisive keywords stand against a Naive Bayes answer that
 * no keyword supports; a capped ticket is labelled with the keyword category when Naive Bayes has no keyword support; a
 * keyword tie led by the title is not broken by Naive Bayes against the title; "virus" is a security indicator. The model
 * answers are stubs, so the rules are pinned independently of the training corpus.
 */
class CompositeClassifierEverydayRulesTest {

    private static final String TITLE = "title";
    private static final String BODY = "description";
    private static final double GATE = ClassificationResult.TRIAGE_THRESHOLD;

    private static ClassificationResult keyword(IncidentCategory category, double confidence,
            Map<IncidentCategory, Integer> support, boolean securityAlert) {
        return ClassificationResult.of(category, confidence, "STUB_KEYWORD", List.of("stub"), support, securityAlert);
    }

    private static ClassificationResult naiveBayes(IncidentCategory category, double confidence) {
        return ClassificationResult.of(category, confidence, "STUB_NB", List.of("stub"));
    }

    private static ClassificationResult fuse(ClassificationResult keyword, ClassificationResult naiveBayes,
            Set<IncidentCategory> titleCategories) {
        KeywordClassifier keywordClassifier = mock(KeywordClassifier.class);
        NaiveBayesClassifier nbClassifier = mock(NaiveBayesClassifier.class);
        when(keywordClassifier.classify(TITLE, BODY)).thenReturn(keyword);
        when(keywordClassifier.titleCategories(TITLE)).thenReturn(titleCategories);
        when(nbClassifier.classify(TITLE, BODY)).thenReturn(naiveBayes);
        return new CompositeClassifier(keywordClassifier, nbClassifier).classify(TITLE, BODY);
    }

    private static ClassificationResult fuse(ClassificationResult keyword, ClassificationResult naiveBayes) {
        return fuse(keyword, naiveBayes, Set.of());
    }

    @Test
    @DisplayName("Two keyword terms of one category beat a Naive Bayes answer no keyword supports (Need software installed)")
    void decisiveKeywordsStandAgainstUnsupportedNaiveBayes() {
        ClassificationResult fused = fuse(
                keyword(IncidentCategory.SOFTWARE, 0.75, Map.of(IncidentCategory.SOFTWARE, 2), false),
                naiveBayes(IncidentCategory.HARDWARE, 0.73));

        assertThat(fused.category()).isEqualTo(IncidentCategory.SOFTWARE);
        assertThat(fused.confidence()).isGreaterThanOrEqualTo(GATE);
        assertThat(fused.rationale()).anyMatch(line -> line.contains("Decisive keyword evidence"));
    }

    @Test
    @DisplayName("Two terms against one of another category is still decisive (Antivirus found a virus on my laptop)")
    void decisiveWithAWeakerRival() {
        ClassificationResult fused = fuse(
                keyword(IncidentCategory.SECURITY, 0.75,
                        Map.of(IncidentCategory.SECURITY, 2, IncidentCategory.HARDWARE, 1), false),
                naiveBayes(IncidentCategory.SOFTWARE, 0.68));

        assertThat(fused.category()).isEqualTo(IncidentCategory.SECURITY);
        assertThat(fused.confidence()).isGreaterThanOrEqualTo(GATE);
    }

    @Test
    @DisplayName("A single keyword term is not decisive: an unsupported Naive Bayes answer still sends the ticket to a person")
    void singleTermIsNotDecisive() {
        ClassificationResult fused = fuse(
                keyword(IncidentCategory.SOFTWARE, 0.55, Map.of(IncidentCategory.SOFTWARE, 1), false),
                naiveBayes(IncidentCategory.DATABASE, 0.79));

        assertThat(fused.confidence()).isLessThan(GATE);
    }

    @Test
    @DisplayName("A capped ticket is labelled with the keyword category when no keyword supports Naive Bayes (Teams calls)")
    void cappedTicketKeepsTheKeywordLabel() {
        ClassificationResult fused = fuse(
                keyword(IncidentCategory.SOFTWARE, 0.55, Map.of(IncidentCategory.SOFTWARE, 1), false),
                naiveBayes(IncidentCategory.DATABASE, 0.79));

        assertThat(fused.category()).isEqualTo(IncidentCategory.SOFTWARE);
    }

    @Test
    @DisplayName("A single keyword in the description does not relabel the ticket (Wi-Fi access points ... firmware update)")
    void weakDescriptionKeywordDoesNotRelabel() {
        ClassificationResult fused = fuse(
                keyword(IncidentCategory.SOFTWARE, 0.50, Map.of(IncidentCategory.SOFTWARE, 1), false),
                naiveBayes(IncidentCategory.NETWORK, 0.80));

        assertThat(fused.category()).isEqualTo(IncidentCategory.NETWORK);
        assertThat(fused.confidence()).isLessThan(GATE);
    }

    @Test
    @DisplayName("Without any keyword the Naive Bayes label is the only suggestion, and the ticket stays capped")
    void noKeywordKeepsTheNaiveBayesLabel() {
        ClassificationResult fused = fuse(
                keyword(IncidentCategory.APPLICATION, 0.20, Map.of(), false), naiveBayes(IncidentCategory.DATABASE, 0.90));

        assertThat(fused.category()).isEqualTo(IncidentCategory.DATABASE);
        assertThat(fused.confidence()).isLessThan(GATE);
    }

    @Test
    @DisplayName("ADR-034 unchanged: a confident Naive Bayes answer that a keyword supports still wins a disagreement")
    void corroboratedNaiveBayesStillWins() {
        ClassificationResult fused = fuse(
                keyword(IncidentCategory.STORAGE, 0.75, Map.of(IncidentCategory.STORAGE, 2, IncidentCategory.CLOUD, 1), false),
                naiveBayes(IncidentCategory.CLOUD, 0.95));

        assertThat(fused.category()).isEqualTo(IncidentCategory.CLOUD);
        assertThat(fused.confidence()).isGreaterThanOrEqualTo(GATE);
    }

    @Test
    @DisplayName("ADR-034 unchanged: decisive keywords of another category than SECURITY with a security indicator go to a person")
    void securityIndicatorStillCapsDecisiveKeywords() {
        ClassificationResult fused = fuse(
                keyword(IncidentCategory.SOFTWARE, 0.75, Map.of(IncidentCategory.SOFTWARE, 2), true),
                naiveBayes(IncidentCategory.HARDWARE, 0.73));

        assertThat(fused.confidence()).isLessThan(GATE);
    }

    @Test
    @DisplayName("A tie led by the title is not broken by Naive Bayes against the title (Wifi not working ... on my laptop)")
    void titleLedTieIsNotOverruledByNaiveBayes() {
        ClassificationResult fused = fuse(
                keyword(IncidentCategory.HARDWARE, 0.55,
                        Map.of(IncidentCategory.HARDWARE, 1, IncidentCategory.NETWORK, 1), false),
                naiveBayes(IncidentCategory.HARDWARE, 0.74), Set.of(IncidentCategory.NETWORK));

        assertThat(fused.category()).isEqualTo(IncidentCategory.NETWORK);
        assertThat(fused.confidence()).isLessThan(GATE);
        assertThat(fused.rationale()).anyMatch(line -> line.contains("the title names NETWORK"));
    }

    @Test
    @DisplayName("A title-led tie that Naive Bayes confirms is an agreement, as before")
    void titleLedTieConfirmedByNaiveBayes() {
        ClassificationResult fused = fuse(
                keyword(IncidentCategory.HARDWARE, 0.55,
                        Map.of(IncidentCategory.HARDWARE, 1, IncidentCategory.NETWORK, 1), false),
                naiveBayes(IncidentCategory.NETWORK, 0.90), Set.of(IncidentCategory.NETWORK));

        assertThat(fused.category()).isEqualTo(IncidentCategory.NETWORK);
        assertThat(fused.confidence()).isGreaterThanOrEqualTo(GATE);
    }

    @Test
    @DisplayName("When the title names both tied categories, Naive Bayes still breaks the tie, as before (ADR-034)")
    void tieNamedTwiceInTheTitleIsBrokenByNaiveBayes() {
        ClassificationResult fused = fuse(
                keyword(IncidentCategory.HARDWARE, 0.55,
                        Map.of(IncidentCategory.HARDWARE, 1, IncidentCategory.NETWORK, 1), false),
                naiveBayes(IncidentCategory.HARDWARE, 0.74), Set.of(IncidentCategory.NETWORK, IncidentCategory.HARDWARE));

        assertThat(fused.category()).isEqualTo(IncidentCategory.HARDWARE);
        assertThat(fused.confidence()).isGreaterThanOrEqualTo(GATE);
    }

    @Test
    @DisplayName("A title-led tie with a security indicator for another category still goes to a person")
    void titleLedTieKeepsTheSecurityCap() {
        ClassificationResult fused = fuse(
                keyword(IncidentCategory.HARDWARE, 0.55,
                        Map.of(IncidentCategory.HARDWARE, 1, IncidentCategory.SECURITY, 1), true),
                naiveBayes(IncidentCategory.SECURITY, 0.90), Set.of(IncidentCategory.HARDWARE));

        assertThat(fused.category()).isEqualTo(IncidentCategory.HARDWARE);
        assertThat(fused.confidence()).isLessThan(GATE);
    }

    @Test
    @DisplayName("'virus' is a security indicator, so a ticket another category wins goes to a person")
    void virusIsASecurityIndicator() {
        KeywordClassifier keywords = new KeywordClassifier();

        assertThat(keywords.classify("Pop-ups on my laptop", "I think I have a virus.").securityAlert()).isTrue();
        assertThat(keywords.classify("Antivirus update failed", "").securityAlert()).isFalse();
    }

    @Test
    @DisplayName("The title's categories are read from the title alone")
    void titleCategoriesComeFromTheTitle() {
        KeywordClassifier keywords = new KeywordClassifier();

        assertThat(keywords.titleCategories("Wifi not working")).containsExactly(IncidentCategory.NETWORK);
        assertThat(keywords.titleCategories("Account locked")).containsExactly(IncidentCategory.AUTHENTICATION);
        assertThat(keywords.titleCategories("Help me please")).isEmpty();
    }

    @Test
    @DisplayName("leadsAllOtherCategories: strictly more support than every other category")
    void leadsAllOtherCategories() {
        assertThat(keyword(IncidentCategory.SOFTWARE, 0.75,
                Map.of(IncidentCategory.SOFTWARE, 2, IncidentCategory.HARDWARE, 1), false).leadsAllOtherCategories()).isTrue();
        assertThat(keyword(IncidentCategory.SOFTWARE, 0.55,
                Map.of(IncidentCategory.SOFTWARE, 1, IncidentCategory.HARDWARE, 1), false).leadsAllOtherCategories()).isFalse();
        assertThat(keyword(IncidentCategory.APPLICATION, 0.20, Map.of(), false).leadsAllOtherCategories()).isFalse();
    }
}
