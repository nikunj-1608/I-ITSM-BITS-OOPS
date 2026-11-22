package com.iitsm.application.port;

import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.IncidentStatus;
import com.iitsm.domain.model.Priority;
import com.iitsm.domain.model.SlaStatus;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Port for dynamic multi-criteria search over the incident directory.
 */
public interface IncidentSearchPort {

    record SearchCriteria(
            Set<IncidentStatus> statuses,
            Set<Priority> priorities,
            Set<IncidentCategory> categories,
            Set<SlaStatus> slaStatuses,
            String serviceId,
            String assigneeId,
            String reporterId,
            String query,
            Instant fromDate,
            Instant toDate
    ) {}

    List<Incident> search(SearchCriteria criteria);
    Page<Incident> searchPaged(SearchCriteria criteria, Pageable pageable);
}

