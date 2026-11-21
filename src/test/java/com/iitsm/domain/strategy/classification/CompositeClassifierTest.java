package com.iitsm.domain.strategy.classification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.iitsm.domain.model.IncidentCategory;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
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

    /** A keyword result with per-category support and the security alert flag (ADR-034). */
    private static ClassificationResult keyword(
            IncidentCategory category, double confidence, Map<IncidentCategory, Integer> support, boolean securityAlert) {
        return ClassificationResult.of(category, confidence, "STUB_KEYWORD", List.of("stub"), support, securityAlert);
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

    // ---- ADR-034: disagreement, ties and the security indicator ------------------------------------------------

    @Test
    @DisplayName("Confident keywords against a Naive Bayes answer that no keyword supports: the keyword label stands "
            + "(ADR-052 replaces the ADR-034 triage cap: two lexicon terms outweigh an unsupported guess)")
    void uncorroboratedDisagreementKeepsTheKeywordLabel() {
        ClassificationResult fused = fuse(
                keyword(IncidentCategory.STORAGE, 0.75, Map.of(IncidentCategory.STORAGE, 2), false),
                result(IncidentCategory.NETWORK, 0.99));

        assertThat(fused.category()).isEqualTo(IncidentCategory.STORAGE);
        assertThat(fused.confidence()).isEqualTo(0.75);
        assertThat(fused.requiresTriage()).isFalse();
    }

    @Test
    @DisplayName("Confident keywords against a Naive Bayes answer that at least one keyword supports: Naive Bayes is trusted")
    void corroboratedDisagreementTrustsNaiveBayes() {
        ClassificationResult fused = fuse(
                keyword(IncidentCategory.NETWORK, 0.75,
                        Map.of(IncidentCategory.NETWORK, 2, IncidentCategory.HARDWARE, 1), false),
                result(IncidentCategory.HARDWARE, 0.95));

        assertThat(fused.category()).isEqualTo(IncidentCategory.HARDWARE);
        assertThat(fused.requiresTriage()).isFalse();
    }

    @Test
    @DisplayName("A keyword-backed Naive Bayes answer that is itself below the gate does not corroborate: the keywords "
            + "decide (ADR-052: two terms against one, no longer capped)")
    void unconfidentNaiveBayesDoesNotCorroborate() {
        ClassificationResult fused = fuse(
                keyword(IncidentCategory.NETWORK, 0.75,
                        Map.of(IncidentCategory.NETWORK, 2, IncidentCategory.HARDWARE, 1), false),
                result(IncidentCategory.HARDWARE, 0.58));

        assertThat(fused.category()).isEqualTo(IncidentCategory.NETWORK);
        assertThat(fused.requiresTriage()).isFalse();
    }

    @Test
    @DisplayName("Split keyword evidence: Naive Bayes picks one of the tied categories and the ticket is not capped")
    void naiveBayesBreaksAKeywordTie() {
        ClassificationResult fused = fuse(
                keyword(IncidentCategory.SOFTWARE, 0.55,
                        Map.of(IncidentCategory.SOFTWARE, 1, IncidentCategory.NETWORK, 1), false),
                result(IncidentCategory.NETWORK, 0.95));

        assertThat(fused.category()).isEqualTo(IncidentCategory.NETWORK);
        assertThat(fused.confidence()).isEqualTo(0.95);
        assertThat(fused.requiresTriage()).isFalse();
    }

    @Test
    @DisplayName("Split keyword evidence: Naive Bayes picks a category outside the tie, so a person decides")
    void naiveBayesOutsideTheTieGoesToTriage() {
        ClassificationResult fused = fuse(
                keyword(IncidentCategory.SOFTWARE, 0.55,
                        Map.of(IncidentCategory.SOFTWARE, 1, IncidentCategory.NETWORK, 1), false),
                result(IncidentCategory.HARDWARE, 0.95));

        assertThat(fused.confidence()).isLessThanOrEqualTo(0.55);
        assertThat(fused.requiresTriage()).isTrue();
    }

    @Test
    @DisplayName("A security indicator in a ticket another category wins goes to a person, even when both models agree")
    void securityIndicatorCapsAnAgreedOtherCategory() {
        ClassificationResult fused = fuse(
                keyword(IncidentCategory.SOFTWARE, 0.75, Map.of(IncidentCategory.SOFTWARE, 2), true),
                result(IncidentCategory.SOFTWARE, 0.99));

        assertThat(fused.category()).isEqualTo(IncidentCategory.SOFTWARE);
        assertThat(fused.requiresTriage()).isTrue();
        assertThat(String.join(" ", fused.rationale())).contains("Security indicator");
    }

    @Test
    @DisplayName("A strong keyword match no longer short-cuts past the security indicator")
    void securityIndicatorStopsTheKeywordShortcut() {
        ClassificationResult fused = fuse(
                keyword(IncidentCategory.SOFTWARE, 0.90, Map.of(IncidentCategory.SOFTWARE, 3), true),
                result(IncidentCategory.SOFTWARE, 0.99));

        assertThat(fused.requiresTriage()).isTrue();
    }

    @Test
    @DisplayName("A security indicator in a ticket that SECURITY wins is not capped")
    void securityIndicatorInASecurityTicketIsNotCapped() {
        ClassificationResult fused = fuse(
                keyword(IncidentCategory.SECURITY, 0.75, Map.of(IncidentCategory.SECURITY, 2), true),
                result(IncidentCategory.SECURITY, 0.95));

        assertThat(fused.category()).isEqualTo(IncidentCategory.SECURITY);
        assertThat(fused.requiresTriage()).isFalse();
    }

    @Test
    @DisplayName("Without a security indicator a strong keyword match is still adopted at once")
    void strongKeywordMatchWithoutAlertShortCuts() {
        ClassificationResult fused = fuse(
                keyword(IncidentCategory.NETWORK, 0.90, Map.of(IncidentCategory.NETWORK, 3), false),
                result(IncidentCategory.HARDWARE, 0.99));

        assertThat(fused.category()).isEqualTo(IncidentCategory.NETWORK);
        assertThat(fused.confidence()).isEqualTo(0.90);
        assertThat(fused.requiresTriage()).isFalse();
    }

    // ---- the audit examples of stress-audit finding H9, with the real models ------------------------------------

    @Test
    @DisplayName("Audit A01: phishing with an Excel macro is SECURITY or goes to a person")
    void phishingWithAnExcelMacro() {
        ClassificationResult fused = realClassifier.classify("Phishing email opened Excel macro",
                "I opened an attachment from an unknown sender and enabled the Excel macro it asked for. "
                        + "Now Outlook shows odd sent items.");

        assertThat(fused.category() == IncidentCategory.SECURITY || fused.requiresTriage()).isTrue();
    }

    @Test
    @DisplayName("Audit A02 to A04: a desk drawer PIN, a parking garage door and a coffee machine go to triage")
    void notItIncidentsGoToTriage() {
        assertThat(realClassifier.classify("Desk drawer locked, lost the pin",
                "The drawer of my office desk is locked and I lost the PIN for the key pad.").requiresTriage()).isTrue();
        assertThat(realClassifier.classify("Parking garage service door stuck",
                "The service door in the parking garage does not open when I badge in.").requiresTriage()).isTrue();
        assertThat(realClassifier.classify("Coffee machine power switch broken",
                "The power switch of the coffee machine on floor 2 is broken.").requiresTriage()).isTrue();
    }

    @Test
    @DisplayName("Audit A05: a repeated vpn is one keyword in the title and, with Naive Bayes agreeing, NETWORK")
    void repeatedVpnIsNetwork() {
        ClassificationResult fused = realClassifier.classify("vpn vpn vpn", "vpn");

        assertThat(fused.category()).isEqualTo(IncidentCategory.NETWORK);
    }

    @Test
    @DisplayName("Audit controls A06 to A12: real faults that use generic words keep their category and stay automatic")
    void controlTicketsKeepTheirCategory() {
        assertThat(realClassifier.classify("DNS lookups failing for internal hosts",
                "Internal hostnames do not resolve and nslookup times out against the corporate DNS servers.").category())
                .isEqualTo(IncidentCategory.NETWORK);
        ClassificationResult locked = realClassifier.classify("Account locked after password reset",
                "After the password reset this morning my account is locked and the login page rejects my credentials.");
        assertThat(locked.category()).isEqualTo(IncidentCategory.AUTHENTICATION);
        assertThat(locked.requiresTriage()).isFalse();
        ClassificationResult database = realClassifier.classify("Database connection pool exhausted",
                "The orders service logs HikariCP timeouts; all connections in the database pool are in use.");
        assertThat(database.category()).isEqualTo(IncidentCategory.DATABASE);
        assertThat(database.requiresTriage()).isFalse();
        assertThat(realClassifier.classify("Laptop power adapter not charging",
                "The laptop does not charge from its power adapter; the battery stays at 3 percent.").category())
                .isEqualTo(IncidentCategory.HARDWARE);
        assertThat(realClassifier.classify("Docking station HDMI port dead",
                "Monitors stay black when connected to the HDMI port of the docking station.").category())
                .isEqualTo(IncidentCategory.HARDWARE);
        assertThat(realClassifier.classify("Checkout fails when placing an order on the web portal",
                "Customers get an error page at checkout when they submit an order on the website.").category())
                .isEqualTo(IncidentCategory.APPLICATION);
    }
}
