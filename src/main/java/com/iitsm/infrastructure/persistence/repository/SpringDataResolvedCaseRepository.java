package com.iitsm.infrastructure.persistence.repository;

import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.IncidentStatus;
import com.iitsm.infrastructure.persistence.entity.ResolutionJpaEntity;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// OOP (Interface inheritance): extends the Spring Data interface, so CRUD methods are inherited and the framework writes the implementation.
/**
 * Read-only projection queries over {@code resolutions} joined with {@code incidents} (ADR-040). Two queries instead
 * of one with {@code :category IS NULL}, because PostgreSQL cannot type a null enum parameter.
 */
public interface SpringDataResolvedCaseRepository extends org.springframework.data.repository.Repository<ResolutionJpaEntity, String> {

    String SELECT_ROW = "SELECT new com.iitsm.infrastructure.persistence.repository.ResolvedCaseRow("
            + "i.id, i.category, i.priority, i.title, i.description, r.rootCause, r.stepsTaken, r.preventiveAction) "
            + "FROM ResolutionJpaEntity r JOIN r.incident i WHERE i.status IN :statuses ";
    String NEWEST_FIRST = " ORDER BY r.resolvedAt DESC, i.id DESC";

    @Query(SELECT_ROW + "AND i.category = :category" + NEWEST_FIRST)
    List<ResolvedCaseRow> findNewestByCategory(
            @Param("statuses") Collection<IncidentStatus> statuses,
            @Param("category") IncidentCategory category,
            Pageable page);

    @Query(SELECT_ROW + NEWEST_FIRST)
    List<ResolvedCaseRow> findNewest(@Param("statuses") Collection<IncidentStatus> statuses, Pageable page);

    @Query("SELECT COUNT(r) FROM ResolutionJpaEntity r JOIN r.incident i WHERE i.id = :id AND i.status IN :statuses")
    long countResolvedCase(@Param("id") String incidentId, @Param("statuses") Collection<IncidentStatus> statuses);
}
