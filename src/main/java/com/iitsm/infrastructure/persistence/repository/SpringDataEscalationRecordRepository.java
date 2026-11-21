package com.iitsm.infrastructure.persistence.repository;

import com.iitsm.infrastructure.persistence.entity.EscalationRecordJpaEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

// OOP (Interface inheritance): extends the Spring Data interface, so CRUD methods are inherited and the framework writes the implementation.
@Repository
public interface SpringDataEscalationRecordRepository extends JpaRepository<EscalationRecordJpaEntity, String> {
    List<EscalationRecordJpaEntity> findByIncidentIdOrderByEscalatedAtDesc(String incidentId);
    List<EscalationRecordJpaEntity> findAllByOrderByEscalatedAtDesc();
}

