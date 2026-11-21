package com.iitsm.infrastructure.persistence.specification;

import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.IncidentStatus;
import com.iitsm.domain.model.Priority;
import com.iitsm.domain.model.SlaStatus;
import com.iitsm.infrastructure.persistence.entity.IncidentJpaEntity;
import jakarta.persistence.criteria.JoinType;
import java.time.Instant;
import java.util.Collection;
import org.springframework.data.jpa.domain.Specification;

/**
 * Pattern: Specification.
 * Composable JPA criteria specifications for multi-criteria incident search.
 * Satisfies the requirement of >= 5 search/filter criteria.
 */
public final class IncidentSpecifications {

    private IncidentSpecifications() {}

    public static Specification<IncidentJpaEntity> hasStatus(IncidentStatus status) {
        return (root, query, cb) -> status == null ? null : cb.equal(root.get("status"), status);
    }

    public static Specification<IncidentJpaEntity> hasPriority(Priority priority) {
        return (root, query, cb) -> priority == null ? null : cb.equal(root.get("priority"), priority);
    }

    public static Specification<IncidentJpaEntity> hasCategory(IncidentCategory category) {
        return (root, query, cb) -> category == null ? null : cb.equal(root.get("category"), category);
    }

    /** Matches any of the given statuses; a null or empty set applies no filter (ADR-049). */
    public static Specification<IncidentJpaEntity> hasStatusIn(Collection<IncidentStatus> statuses) {
        return (root, query, cb) -> (statuses == null || statuses.isEmpty())
                ? null
                : root.get("status").in(statuses);
    }

    /**
     * Matches any of the given SLA statuses, read from the stored {@code sla_trackers.status} column
     * (the value as of the last SLA evaluation, ADR-049). A ticket without a tracker never matches a
     * non-empty filter (inner join); a null or empty set applies no filter.
     */
    public static Specification<IncidentJpaEntity> hasSlaStatusIn(Collection<SlaStatus> slaStatuses) {
        return (root, query, cb) -> (slaStatuses == null || slaStatuses.isEmpty())
                ? null
                : root.join("slaTracker", JoinType.INNER).get("status").in(slaStatuses);
    }

    /** Matches any of the given priorities; a null or empty set applies no filter. */
    public static Specification<IncidentJpaEntity> hasPriorityIn(Collection<Priority> priorities) {
        return (root, query, cb) -> (priorities == null || priorities.isEmpty())
                ? null
                : root.get("priority").in(priorities);
    }

    /** Matches any of the given categories; a null or empty set applies no filter. */
    public static Specification<IncidentJpaEntity> hasCategoryIn(Collection<IncidentCategory> categories) {
        return (root, query, cb) -> (categories == null || categories.isEmpty())
                ? null
                : root.get("category").in(categories);
    }

    public static Specification<IncidentJpaEntity> hasServiceId(String serviceId) {
        return (root, query, cb) -> (serviceId == null || serviceId.isBlank())
                ? null
                : cb.equal(root.get("serviceId"), serviceId.trim());
    }

    public static Specification<IncidentJpaEntity> hasAssigneeId(String assigneeId) {
        return (root, query, cb) -> (assigneeId == null || assigneeId.isBlank())
                ? null
                : cb.equal(root.get("assigneeId"), assigneeId.trim());
    }

    public static Specification<IncidentJpaEntity> hasReporterId(String reporterId) {
        return (root, query, cb) -> (reporterId == null || reporterId.isBlank())
                ? null
                : cb.equal(root.get("reporterId"), reporterId.trim());
    }

    public static Specification<IncidentJpaEntity> containsText(String text) {
        return (root, query, cb) -> {
            if (text == null || text.isBlank()) {
                return null;
            }
            String pattern = "%" + text.trim().toLowerCase() + "%";
            return cb.or(
                    cb.like(cb.lower(root.get("id")), pattern),
                    cb.like(cb.lower(root.get("title")), pattern),
                    cb.like(cb.lower(root.get("description")), pattern));
        };
    }

    public static Specification<IncidentJpaEntity> createdBetween(Instant from, Instant to) {
        return (root, query, cb) -> {
            if (from == null && to == null) return null;
            if (from != null && to != null) return cb.between(root.get("createdAt"), from, to);
            if (from != null) return cb.greaterThanOrEqualTo(root.get("createdAt"), from);
            return cb.lessThanOrEqualTo(root.get("createdAt"), to);
        };
    }
}

