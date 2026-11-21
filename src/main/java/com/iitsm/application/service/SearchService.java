package com.iitsm.application.service;

import com.iitsm.application.port.IncidentSearchPort;
import com.iitsm.domain.exception.ValidationException;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.IncidentStatus;
import com.iitsm.domain.model.Priority;
import com.iitsm.domain.model.SlaStatus;
import com.iitsm.domain.model.TicketDisplayName;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for executing dynamic multi-criteria searches over the incident directory.
 * Pure application layer: delegates to IncidentSearchPort adapter.
 */
@Service
@Transactional(readOnly = true)
public class SearchService {

    public record IncidentSearchCriteria(
            Set<IncidentStatus> statuses,
            Set<Priority> priorities,
            Set<IncidentCategory> categories,
            Set<SlaStatus> slaStatuses,
            String serviceId,
            String assigneeId,
            String reporterId,
            String query,
            Instant fromDate,
            Instant toDate) {}

    private final IncidentSearchPort incidentSearchPort;

    public SearchService(IncidentSearchPort incidentSearchPort) {
        this.incidentSearchPort = Objects.requireNonNull(incidentSearchPort, "incidentSearchPort cannot be null");
    }

    private static IncidentSearchPort.SearchCriteria toPortCriteria(IncidentSearchCriteria c) {
        if (c == null) {
            return null;
        }
        return new IncidentSearchPort.SearchCriteria(
                c.statuses(),
                c.priorities(),
                c.categories(),
                c.slaStatuses(),
                c.serviceId(),
                c.assigneeId(),
                c.reporterId(),
                TicketDisplayName.canonicalId(c.query()),
                c.fromDate(),
                c.toDate()
        );
    }

    /** A creation window whose start lies after its end is a client mistake (ADR-049). */
    private static void validateDateRange(IncidentSearchCriteria c) {
        if (c != null && c.fromDate() != null && c.toDate() != null && c.fromDate().isAfter(c.toDate())) {
            throw new ValidationException("fromDate", "must not be after toDate");
        }
    }

    public List<Incident> search(IncidentSearchCriteria criteria) {
        validateDateRange(criteria);
        return incidentSearchPort.search(toPortCriteria(criteria));
    }

    public Page<Incident> searchPaged(IncidentSearchCriteria criteria, Pageable pageable) {
        validateDateRange(criteria);
        return incidentSearchPort.searchPaged(toPortCriteria(criteria), pageable);
    }
}
