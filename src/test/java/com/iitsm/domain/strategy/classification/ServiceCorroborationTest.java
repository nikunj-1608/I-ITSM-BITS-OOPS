package com.iitsm.domain.strategy.classification;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitsm.domain.model.BusinessCriticality;
import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.ItService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ADR-052, Phase 5: a capped ticket is lifted to the confidence gate only when a keyword supports its category and the
 * matched IT service is owned by the team that handles that category.
 */
class ServiceCorroborationTest {

    private static final double GATE = ClassificationResult.TRIAGE_THRESHOLD;
    private static final String DB_TEAM = "TEAM-DB";

    private static ItService service(String id, String team) {
        return new ItService(id, id, team, BusinessCriticality.TIER_3_BUSINESS_OPERATIONAL, 1000, Set.of("kw"));
    }

    private static ClassificationResult capped(IncidentCategory category, Map<IncidentCategory, Integer> support,
            boolean securityAlert, double confidence) {
        return ClassificationResult.of(category, confidence, "STUB", List.of("stub"), support, securityAlert);
    }

    private static ClassificationResult databaseTicket() {
        return capped(IncidentCategory.DATABASE, Map.of(IncidentCategory.DATABASE, 1), false, 0.55);
    }

    @Test
    @DisplayName("A keyword and a service of the category's own team lift a capped ticket exactly to the gate")
    void liftsToTheGate() {
        ClassificationResult lifted = ServiceCorroboration.apply(
                databaseTicket(), Optional.of(service("SVC-CORE-DB", DB_TEAM)), Optional.of(DB_TEAM));

        assertThat(lifted.confidence()).isEqualTo(GATE);
        assertThat(lifted.requiresTriage()).isFalse();
        assertThat(lifted.category()).isEqualTo(IncidentCategory.DATABASE);
        assertThat(lifted.rationale()).anyMatch(line -> line.contains("SVC-CORE-DB"));
    }

    @Test
    @DisplayName("A service owned by another team is no evidence")
    void otherTeamsServiceDoesNotLift() {
        ClassificationResult result = databaseTicket();

        assertThat(ServiceCorroboration.apply(result, Optional.of(service("SVC-SSO", "TEAM-SEC")), Optional.of(DB_TEAM)))
                .isSameAs(result);
    }

    @Test
    @DisplayName("The general (unclassified) service is no evidence, whatever team owns it")
    void generalServiceDoesNotLift() {
        ClassificationResult result = databaseTicket();

        assertThat(ServiceCorroboration.apply(
                result, Optional.of(service(ItService.GENERAL_SERVICE_ID, DB_TEAM)), Optional.of(DB_TEAM))).isSameAs(result);
    }

    @Test
    @DisplayName("No matched service, or no team for the category, changes nothing")
    void missingSignalChangesNothing() {
        ClassificationResult result = databaseTicket();

        assertThat(ServiceCorroboration.apply(result, Optional.empty(), Optional.of(DB_TEAM))).isSameAs(result);
        assertThat(ServiceCorroboration.apply(result, Optional.of(service("SVC-CORE-DB", DB_TEAM)), Optional.empty()))
                .isSameAs(result);
    }

    @Test
    @DisplayName("Without a keyword for the category a service match alone routes nothing")
    void serviceAloneDoesNotLift() {
        ClassificationResult noKeyword = capped(IncidentCategory.DATABASE, Map.of(), false, 0.55);
        ClassificationResult otherKeyword = capped(IncidentCategory.DATABASE, Map.of(IncidentCategory.STORAGE, 1), false, 0.55);

        assertThat(ServiceCorroboration.apply(noKeyword, Optional.of(service("SVC-CORE-DB", DB_TEAM)), Optional.of(DB_TEAM)))
                .isSameAs(noKeyword);
        assertThat(ServiceCorroboration.apply(otherKeyword, Optional.of(service("SVC-CORE-DB", DB_TEAM)), Optional.of(DB_TEAM)))
                .isSameAs(otherKeyword);
    }

    @Test
    @DisplayName("A security indicator keeps the ticket with a person")
    void securityAlertBlocksTheLift() {
        ClassificationResult result = capped(IncidentCategory.DATABASE, Map.of(IncidentCategory.DATABASE, 1), true, 0.55);

        assertThat(ServiceCorroboration.apply(result, Optional.of(service("SVC-CORE-DB", DB_TEAM)), Optional.of(DB_TEAM)))
                .isSameAs(result);
    }

    @Test
    @DisplayName("A ticket already at the gate is returned unchanged, never raised")
    void neverRaisesAboveTheGate() {
        ClassificationResult confident = capped(IncidentCategory.DATABASE, Map.of(IncidentCategory.DATABASE, 2), false, 0.90);

        assertThat(ServiceCorroboration.apply(confident, Optional.of(service("SVC-CORE-DB", DB_TEAM)), Optional.of(DB_TEAM)))
                .isSameAs(confident);
    }

    @Test
    @DisplayName("The composite classifier carries the keyword evidence into its result")
    void compositeCarriesKeywordEvidence() throws Exception {
        NaiveBayesClassifier naiveBayes = new NaiveBayesClassifier();
        try (var corpus = getClass().getClassLoader().getResourceAsStream("sim/corpus.csv")) {
            naiveBayes.trainFromCsv(corpus);
        }
        CompositeClassifier composite = new CompositeClassifier(new KeywordClassifier(), naiveBayes);

        ClassificationResult result = composite.classify("Report fails with database error", "The monthly report fails.");

        assertThat(result.category()).isEqualTo(IncidentCategory.DATABASE);
        assertThat(result.supportFor(IncidentCategory.DATABASE)).isGreaterThanOrEqualTo(1);
        assertThat(composite.classify("Pop-ups on my laptop", "I think I have a virus.").securityAlert()).isTrue();
    }
}
