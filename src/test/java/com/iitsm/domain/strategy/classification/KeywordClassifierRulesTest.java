package com.iitsm.domain.strategy.classification;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitsm.domain.model.IncidentCategory;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Remaining-defects plan, Phase F (ADR-034): the evidence rules of the keyword classifier. A term counts once however
 * often it is repeated, a tie is reported, the strong confidence needs a clear lead, the lexicon holds no word that is
 * ordinary English outside IT, and a strong security term is flagged.
 */
class KeywordClassifierRulesTest {

    private final KeywordClassifier classifier = new KeywordClassifier();

    @Test
    @DisplayName("A repeated keyword counts once: 'vpn vpn vpn' is one term, a single keyword in the title")
    void repeatedKeywordCountsOnce() {
        KeywordClassifier.Evidence evidence = classifier.evidence("vpn vpn vpn", "vpn");
        ClassificationResult result = classifier.classify("vpn vpn vpn", "vpn");

        assertThat(evidence.count()).isEqualTo(1);
        assertThat(evidence.category()).isEqualTo(IncidentCategory.NETWORK);
        assertThat(result.confidence()).isEqualTo(0.55);
        assertThat(result.supportFor(IncidentCategory.NETWORK)).isEqualTo(1);
    }

    @Test
    @DisplayName("The same word in title and description is still one term")
    void sameWordInTitleAndDescriptionIsOneTerm() {
        assertThat(classifier.evidence("Switch the router", "router router router").count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Three distinct terms and nothing competing is a strong match (0.90)")
    void threeDistinctTermsAreStrong() {
        ClassificationResult result = classifier.classify("VPN router firewall down", "");

        assertThat(result.category()).isEqualTo(IncidentCategory.NETWORK);
        assertThat(result.confidence()).isEqualTo(0.90);
    }

    @Test
    @DisplayName("Three terms against one other term (a lead of two) is still a clear lead")
    void aLeadOfTwoIsClear() {
        KeywordClassifier.Evidence evidence = classifier.evidence("VPN router firewall postgres", "");

        assertThat(evidence.runnerUpCount()).isEqualTo(1);
        assertThat(evidence.hasClearLead()).isTrue();
        assertThat(classifier.classify("VPN router firewall postgres", "").confidence()).isEqualTo(0.90);
    }

    @Test
    @DisplayName("Three terms against two (a lead of one) is not a clear lead: the keyword confidence stays below the shortcut")
    void aLeadOfOneIsNotClear() {
        String title = "VPN router firewall postgres database";
        KeywordClassifier.Evidence evidence = classifier.evidence(title, "");
        ClassificationResult result = classifier.classify(title, "");

        assertThat(evidence.count()).isEqualTo(3);
        assertThat(evidence.runnerUpCount()).isEqualTo(2);
        assertThat(evidence.hasClearLead()).isFalse();
        assertThat(result.confidence()).isEqualTo(0.75);
        assertThat(result.rationale().getFirst()).contains("competing evidence");
    }

    @Test
    @DisplayName("A tie for the best count is reported with the tied categories, at 0.55 so a second model must decide")
    void tieIsReported() {
        ClassificationResult result = classifier.classify("VPN and Excel", "");

        assertThat(result.confidence()).isEqualTo(0.55);
        assertThat(Set.of(result.category()).stream().findFirst().orElseThrow())
                .isIn(IncidentCategory.NETWORK, IncidentCategory.SOFTWARE);
        assertThat(result.tiedWith()).hasSize(1)
                .allMatch(other -> other != result.category())
                .allMatch(other -> other == IncidentCategory.NETWORK || other == IncidentCategory.SOFTWARE);
        assertThat(result.rationale().getFirst()).contains("split between categories");
    }

    @Test
    @DisplayName("A single winner has no tie")
    void singleWinnerHasNoTie() {
        assertThat(classifier.classify("VPN router firewall down", "").tiedWith()).isEmpty();
    }

    @Test
    @DisplayName("No keyword at all is 0.20 for APPLICATION, as before")
    void noKeyword() {
        ClassificationResult result = classifier.classify("Strange behaviour today", "Something is not right.");

        assertThat(result.confidence()).isEqualTo(0.20);
        assertThat(result.category()).isEqualTo(IncidentCategory.APPLICATION);
    }

    @Test
    @DisplayName("ADR-034: the generic words are in no category's lexicon")
    void genericWordsAreNotInTheLexicon() {
        for (IncidentCategory category : IncidentCategory.values()) {
            assertThat(classifier.lexiconWords(category)).as(category.name())
                    .doesNotContainAnyElementsOf(KeywordClassifier.GENERIC_WORDS);
        }
    }

    @Test
    @DisplayName("ADR-034: a ticket made of generic words only has no keyword evidence")
    void genericWordsAreNoEvidence() {
        KeywordClassifier.Evidence evidence = classifier.evidence(
                "Desk drawer locked, lost the pin", "The power switch of the service door port connection order.");

        assertThat(evidence.count()).isZero();
    }

    @Test
    @DisplayName("A strong security term raises the security alert, whatever category wins")
    void securityAlertIsReported() {
        ClassificationResult phishing = classifier.classify("Phishing email opened Excel macro",
                "I enabled the Excel macro it asked for. Now Outlook shows odd sent items.");

        assertThat(phishing.category()).isEqualTo(IncidentCategory.SOFTWARE);
        assertThat(phishing.securityAlert()).isTrue();
        assertThat(classifier.classify("VPN router firewall down", "").securityAlert()).isFalse();
    }

    @Test
    @DisplayName("Broad security words (scan, security, threat) are lexicon terms but no alert on their own")
    void broadSecurityWordsAreNoAlert() {
        assertThat(classifier.classify("Scan to email not working", "The printer scan function fails.").securityAlert())
                .isFalse();
    }

    @Test
    @DisplayName("The security lexicon knows attack, brute and exploit")
    void securityLexiconIsExtended() {
        assertThat(classifier.lexiconWords(IncidentCategory.SECURITY)).contains("attack", "brute", "exploit");
    }

    @Test
    @DisplayName("ADR-052: everyday words land in their category")
    void everydayWordsLandInTheirCategory() {
        assertThat(classifier.evidence("Printer jammed again", "").category()).isEqualTo(IncidentCategory.HARDWARE);
        assertThat(classifier.evidence("Webcam not detected", "").category()).isEqualTo(IncidentCategory.HARDWARE);
        assertThat(classifier.evidence("Internet is down", "").category()).isEqualTo(IncidentCategory.NETWORK);
        assertThat(classifier.evidence("Server unreachable", "").category()).isEqualTo(IncidentCategory.NETWORK);
        assertThat(classifier.evidence("Emails stuck in the outbox", "").category()).isEqualTo(IncidentCategory.SOFTWARE);
        assertThat(classifier.evidence("Zoom client frozen", "").category()).isEqualTo(IncidentCategory.SOFTWARE);
        assertThat(classifier.evidence("Antivirus found a virus", "").category()).isEqualTo(IncidentCategory.SECURITY);
        assertThat(classifier.evidence("Test VM not starting", "").category()).isEqualTo(IncidentCategory.CLOUD);
        assertThat(classifier.evidence("Please unlock me", "").category()).isEqualTo(IncidentCategory.AUTHENTICATION);
    }

    @Test
    @DisplayName("ADR-052: inflected forms match (jammed, printers, emails, viruses)")
    void inflectedFormsMatch() {
        assertThat(classifier.evidence("Paper jammed", "").terms()).containsExactly("jam");
        assertThat(classifier.evidence("All printers offline", "").count()).isEqualTo(1);
        assertThat(classifier.evidence("Two emails missing", "").category()).isEqualTo(IncidentCategory.SOFTWARE);
        assertThat(classifier.evidence("Viruses on the share", "").category()).isEqualTo(IncidentCategory.SECURITY);
    }

    @Test
    @DisplayName("ADR-052: 'account' and 'locked' together are one AUTHENTICATION term, in either order")
    void accountAndLockedArePairTerm() {
        KeywordClassifier.Evidence titleOnly = classifier.evidence("Account locked", "");
        KeywordClassifier.Evidence reversed = classifier.evidence("Locked again", "My account will not open.");

        assertThat(titleOnly.category()).isEqualTo(IncidentCategory.AUTHENTICATION);
        assertThat(titleOnly.terms()).containsExactly("account+locked");
        assertThat(reversed.category()).isEqualTo(IncidentCategory.AUTHENTICATION);
        assertThat(reversed.count()).isEqualTo(1);
        assertThat(classifier.evidence("Account disabled", "").terms()).containsExactly("account+disabled");
    }

    @Test
    @DisplayName("ADR-052: a pair term in the title is a title match (0.55); split over title and description it is weak (0.50)")
    void pairTermTitleRule() {
        assertThat(classifier.classify("Account locked", "Please help.").confidence()).isEqualTo(0.55);
        assertThat(classifier.classify("Locked again", "My account will not open.").confidence()).isEqualTo(0.50);
    }

    @Test
    @DisplayName("ADR-052: either pair word alone is no evidence (a locked drawer, a bank account)")
    void pairWordAloneIsNoEvidence() {
        assertThat(classifier.evidence("Desk drawer locked", "The drawer is locked.").count()).isZero();
        assertThat(classifier.evidence("Account balance looks wrong", "The account shows an old balance.").count()).isZero();
    }

    @Test
    @DisplayName("ADR-052: no pair word is a lexicon term of its own category (the pair would add nothing)")
    void pairWordsAreNotLexiconTerms() {
        for (KeywordClassifier.PairTerm pair : KeywordClassifier.PAIR_TERMS) {
            assertThat(classifier.lexiconWords(pair.category())).as(pair.label())
                    .doesNotContain(pair.first(), pair.second());
        }
    }
}
