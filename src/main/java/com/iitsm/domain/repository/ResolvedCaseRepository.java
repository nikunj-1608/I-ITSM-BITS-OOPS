package com.iitsm.domain.repository;

import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.strategy.suggestion.SuggestionCandidate;
import java.util.List;

/**
 * Pattern: Repository (read-only port). The resolved tickets that resolution suggestions compare with (ADR-040), as
 * light {@link SuggestionCandidate}s: only the technical content is read, never a whole ticket with its notes, factor
 * rows and SLA tracker.
 */
public interface ResolvedCaseRepository {

    /**
     * The newest tickets (by resolution time) whose status has a confirmed resolution (RESOLVED, VERIFIED, CLOSED).
     *
     * @param category the category to keep, or null for every category
     * @param limit    the most rows read (the candidate window)
     */
    List<SuggestionCandidate> findNewestResolvedCases(IncidentCategory category, int limit);

    /** True when the ticket exists, has a resolution and its status confirms it (a valid suggestion source, ADR-041). */
    boolean isResolvedCase(String incidentId);
}
