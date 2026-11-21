package com.iitsm.infrastructure.persistence.adapter;

import com.iitsm.domain.exception.PersistenceFailureException;
import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.IncidentStatus;
import com.iitsm.domain.repository.ResolvedCaseRepository;
import com.iitsm.domain.strategy.suggestion.SuggestionCandidate;
import com.iitsm.domain.strategy.suggestion.SuggestionSourceType;
import com.iitsm.infrastructure.persistence.repository.ResolvedCaseRow;
import com.iitsm.infrastructure.persistence.repository.SpringDataResolvedCaseRepository;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * Pattern: Adapter / Repository. Reads the resolved-case projection for resolution suggestions (ADR-040); one query, at
 * most {@code limit} rows, no ticket aggregate loaded.
 */
@Component
public class ResolvedCaseRepositoryAdapter implements ResolvedCaseRepository {

    private static final Set<IncidentStatus> CONFIRMED = IncidentStatus.withConfirmedResolution();

    private final SpringDataResolvedCaseRepository springDataRepository;

    public ResolvedCaseRepositoryAdapter(SpringDataResolvedCaseRepository springDataRepository) {
        this.springDataRepository = Objects.requireNonNull(springDataRepository, "springDataRepository cannot be null");
    }

    @Override
    public List<SuggestionCandidate> findNewestResolvedCases(IncidentCategory category, int limit) {
        if (limit < 1) {
            return List.of();
        }
        PageRequest page = PageRequest.of(0, limit);
        try {
            List<ResolvedCaseRow> rows = category == null
                    ? springDataRepository.findNewest(CONFIRMED, page)
                    : springDataRepository.findNewestByCategory(CONFIRMED, category, page);
            return rows.stream().map(ResolvedCaseRepositoryAdapter::toCandidate).toList();
        } catch (DataAccessException ex) {
            throw new PersistenceFailureException("Failed to read resolved tickets for suggestions", ex);
        }
    }

    @Override
    public boolean isResolvedCase(String incidentId) {
        if (incidentId == null || incidentId.isBlank()) {
            return false;
        }
        try {
            return springDataRepository.countResolvedCase(incidentId.trim(), CONFIRMED) > 0;
        } catch (DataAccessException ex) {
            throw new PersistenceFailureException("Failed to check a resolved ticket: " + incidentId, ex);
        }
    }

    private static SuggestionCandidate toCandidate(ResolvedCaseRow row) {
        return new SuggestionCandidate(SuggestionSourceType.RESOLVED_TICKET, row.incidentId(), row.category(),
                row.priority(), row.title(), row.description(), row.rootCause(), row.stepsTaken(), row.preventiveAction());
    }
}
