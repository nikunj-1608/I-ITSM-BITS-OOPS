package com.iitsm.infrastructure.persistence.repository;

import com.iitsm.infrastructure.persistence.entity.AssignmentDecisionJpaEntity;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data access to {@code assignment_decisions} (ADR-044). */
public interface SpringDataAssignmentDecisionRepository extends JpaRepository<AssignmentDecisionJpaEntity, String> {

    List<AssignmentDecisionJpaEntity> findByIncidentIdOrderByDecidedAtDescIdDesc(String incidentId, Pageable page);
}
