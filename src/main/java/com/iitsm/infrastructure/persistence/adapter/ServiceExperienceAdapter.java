package com.iitsm.infrastructure.persistence.adapter;

import com.iitsm.domain.exception.PersistenceFailureException;
import com.iitsm.domain.model.IncidentStatus;
import com.iitsm.domain.strategy.assignment.ServiceExperienceSource;
import com.iitsm.infrastructure.persistence.repository.ResolverCount;
import com.iitsm.infrastructure.persistence.repository.SpringDataServiceExperienceRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * Pattern: Adapter. Implements the domain port {@link ServiceExperienceSource} over the resolved tickets (ADR-044,
 * Phase L4): for a service and a set of technicians, how many tickets of the service each has resolved, counting only
 * fixes that stand (RESOLVED, VERIFIED, CLOSED). One grouped, read-only query per ranking; it joins the caller's
 * transaction, like the rest of the assignment.
 */
@Component
public class ServiceExperienceAdapter implements ServiceExperienceSource {

    private static final Set<IncidentStatus> CONFIRMED = IncidentStatus.withConfirmedResolution();

    private final SpringDataServiceExperienceRepository springDataRepository;

    public ServiceExperienceAdapter(SpringDataServiceExperienceRepository springDataRepository) {
        this.springDataRepository = Objects.requireNonNull(springDataRepository, "springDataRepository cannot be null");
    }

    @Override
    public Map<String, Integer> resolvedTicketCounts(String serviceId, Collection<String> technicianIds) {
        if (serviceId == null || serviceId.isBlank() || technicianIds == null || technicianIds.isEmpty()) {
            return Map.of();
        }
        try {
            List<ResolverCount> rows = springDataRepository.countResolvedByTechnician(serviceId, technicianIds, CONFIRMED);
            Map<String, Integer> counts = new HashMap<>();
            rows.forEach(row -> counts.put(row.resolvedBy(), Math.toIntExact(row.resolved())));
            return Map.copyOf(counts);
        } catch (DataAccessException ex) {
            throw new PersistenceFailureException("Failed to read service experience of " + serviceId, ex);
        }
    }
}
