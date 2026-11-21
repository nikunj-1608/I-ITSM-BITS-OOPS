package com.iitsm.infrastructure.persistence.repository;

import com.iitsm.domain.model.AvailabilityStatus;
import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.UserRole;
import com.iitsm.infrastructure.persistence.entity.UserJpaEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface SpringDataUserRepository extends JpaRepository<UserJpaEntity, String> {

    Optional<UserJpaEntity> findByEmail(String email);

    List<UserJpaEntity> findByRole(UserRole role);

    List<UserJpaEntity> findBySupportTeamId(String supportTeamId);

    // findAvailableTechnicians: returns all TECHNICIAN/TEAM_LEAD users who are
    //   - active (not deactivated)
    //   - not OFFLINE (NULL status treated as ONLINE — null-safe IS NULL OR check prevents
    //     SQL three-valued logic from silently dropping newly-seeded technicians)
    //   - below their maximum ticket capacity (NULL maxCapacity treated as uncapped —
    //     prevents newly-seeded technicians with a NULL slot from being invisibly excluded)
    @Query("SELECT u FROM UserJpaEntity u " +
           "WHERE u.role IN ('ROLE_TECHNICIAN', 'ROLE_TEAM_LEAD') " +
           "AND u.active = true " +
           "AND (u.availabilityStatus IS NULL OR u.availabilityStatus != 'OFFLINE') " +
           "AND (u.maxCapacity IS NULL OR SIZE(u.activeIncidentIds) < u.maxCapacity)")
    List<UserJpaEntity> findAvailableTechnicians();

    @Query("SELECT u FROM UserJpaEntity u JOIN u.skills s WHERE u.role IN ('ROLE_TECHNICIAN', 'ROLE_TEAM_LEAD') AND s.category = :category")
    List<UserJpaEntity> findTechniciansBySkillCategory(@Param("category") IncidentCategory category);
}

