package com.iitsm.domain.repository;

import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.IncidentStatus;
import com.iitsm.domain.model.Priority;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Pattern: Repository.
 * Domain port for Incident entity persistence and querying. Pure Java, zero framework dependencies.
 */
public interface IncidentRepository {

    Incident save(Incident incident);

    Optional<Incident> findById(String id);

    List<Incident> findByReporterId(String reporterId);

    List<Incident> findByAssigneeId(String assigneeId);

    /** The tickets of the given IT services, in every status. */
    List<Incident> findByServiceIdIn(java.util.Collection<String> serviceIds);

    /** The tickets of the given categories, in every status (a team's queue is the tickets of the categories it handles). */
    List<Incident> findByCategoryIn(java.util.Collection<IncidentCategory> categories);

    List<Incident> findByStatus(IncidentStatus status);

    List<Incident> findByCategory(IncidentCategory category);

    List<Incident> findOpenIncidentsByServiceAndCategorySince(
            String serviceId, IncidentCategory category, Instant since);

    List<Incident> findOpenIncidentsSince(Instant since);

    List<Incident> findActiveIncidents();

    /** Ids of the tickets the SLA scan watches (not VERIFIED or CLOSED); the scan loads and saves each in its own transaction. */
    List<String> findActiveIncidentIds();

    List<Incident> findByNeedsTriageTrue();

    List<Incident> findAll();

    long count();

    long countByStatusIn(List<IncidentStatus> statuses);

    long countByPriority(Priority priority);
}

