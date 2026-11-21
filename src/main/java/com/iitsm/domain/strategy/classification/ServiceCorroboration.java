package com.iitsm.domain.strategy.classification;

import com.iitsm.domain.model.ItService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Pattern: Strategy (a pure policy over a finished classification).
 * A capped ticket reaches automatic assignment when two independent signals name the same team: the keywords found a
 * term for the final category, and the IT service the ticket text matched is owned by the team that handles that
 * category (ADR-052, Phase 5). "Report fails with database error" is one keyword for DATABASE; its service, Core
 * Banking DB, belongs to the database team that handles DATABASE: together that is enough to route it.
 *
 * <p>It only lifts a ticket to the gate, never above it, never over a security indicator, never for the general
 * "unclassified" service and never for a category the keywords did not support, so a loose service match alone cannot
 * route anything and cannot change the category.
 */
public final class ServiceCorroboration {

    private ServiceCorroboration() { }

    /**
     * Lifts a capped result to the confidence gate when the keywords and the matched service agree.
     *
     * @param result         the classification, with the keyword evidence the composite classifier carries
     * @param matchedService the IT service the ticket text matched, if any
     * @param teamOfCategory the team that handles the result's category, if any
     * @return the result lifted to the gate when the signals agree, the same result otherwise
     */
    public static ClassificationResult apply(
            ClassificationResult result, Optional<ItService> matchedService, Optional<String> teamOfCategory) {
        if (!qualifies(result, matchedService, teamOfCategory)) {
            return result;
        }
        List<String> rationale = new ArrayList<>(result.rationale());
        rationale.add("Matched IT service " + matchedService.orElseThrow().getId() + " is owned by the team that handles "
                + result.category() + " and a keyword supports it; lifted to the confidence gate");
        return ClassificationResult.of(result.category(), ClassificationResult.TRIAGE_THRESHOLD, result.classifierName(),
                rationale, result.keywordSupport(), result.securityAlert());
    }

    private static boolean qualifies(
            ClassificationResult result, Optional<ItService> matchedService, Optional<String> teamOfCategory) {
        return result.confidence() < ClassificationResult.TRIAGE_THRESHOLD
                && !result.securityAlert()
                && result.supportFor(result.category()) >= 1
                && matchedService.isPresent()
                && !ItService.GENERAL_SERVICE_ID.equals(matchedService.get().getId())
                && teamOfCategory.isPresent()
                && teamOfCategory.get().equals(matchedService.get().getOwningTeamId());
    }
}
