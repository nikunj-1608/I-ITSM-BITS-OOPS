package com.iitsm.web.dto;

import com.iitsm.domain.model.AvailabilityStatus;
import com.iitsm.domain.model.Skill;
import com.iitsm.domain.model.Technician;
import java.util.Set;

// OOP (Immutability): TechnicianSummaryDto is a record, so it is a read-only data carrier with no setters.
/**
 * Safe summary projection for technicians in administrative rosters.
 * Guarantees no sensitive security fields (e.g. passwordHash) are ever serialized to JSON.
 *
 * <p>{@code activeIncidentCount} is the number of capacity slots in use; {@code carriedDuplicateCount} counts open
 * linked duplicates the technician carries on top of that without a slot (ADR-016, F4; ADR-026).
 */
public record TechnicianSummaryDto(
        String id,
        String name,
        String email,
        String role,
        boolean active,
        boolean locked,
        int experienceYears,
        int maxCapacity,
        int activeIncidentCount,
        int carriedDuplicateCount,
        double loadRatio,
        AvailabilityStatus availabilityStatus,
        String supportTeamId,
        Set<Skill> skills,
        boolean availableForAssignment
) {
    public static TechnicianSummaryDto fromDomain(Technician t, int carriedDuplicateCount, java.time.Instant now) {
        if (t == null) return null;
        return new TechnicianSummaryDto(
                t.getId(),
                t.getName(),
                t.getEmail().value(),
                t.getRole().name(),
                t.isActive(),
                t.isCurrentlyLocked(now),
                t.getExperienceYears(),
                t.getMaxCapacity(),
                t.getActiveIncidentIds().size(),
                carriedDuplicateCount,
                t.getLoadRatio(),
                t.getAvailabilityStatus(),
                t.getSupportTeamId(),
                t.getSkills(),
                t.isAvailableForAssignment()
        );
    }
}

