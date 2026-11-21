package com.iitsm.infrastructure.persistence.repository;

import com.iitsm.domain.model.IncidentStatus;
import com.iitsm.infrastructure.persistence.entity.ResolutionJpaEntity;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Read-only count of resolved tickets per resolver for one IT service (ADR-044, Phase L4), over {@code resolutions}
 * joined with {@code incidents}. A ticket the employee sent back is back in progress and is not counted until it is
 * resolved again.
 */
public interface SpringDataServiceExperienceRepository extends org.springframework.data.repository.Repository<ResolutionJpaEntity, String> {

    @Query("SELECT new com.iitsm.infrastructure.persistence.repository.ResolverCount(r.resolvedBy, COUNT(r)) "
            + "FROM ResolutionJpaEntity r JOIN r.incident i "
            + "WHERE i.serviceId = :serviceId AND r.resolvedBy IN :technicianIds AND i.status IN :statuses "
            + "GROUP BY r.resolvedBy")
    List<ResolverCount> countResolvedByTechnician(
            @Param("serviceId") String serviceId,
            @Param("technicianIds") Collection<String> technicianIds,
            @Param("statuses") Collection<IncidentStatus> statuses);
}
