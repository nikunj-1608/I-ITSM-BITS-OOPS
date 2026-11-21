package com.iitsm.domain.simulation.assignment;

import com.iitsm.domain.model.Email;
import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.Skill;
import com.iitsm.domain.model.Technician;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * OOP: Immutable value object. The values of one technician that matter for the offline comparison (ADR-044, Phase L3):
 * team, years of experience, capacity and skills. A profile is copied from the live roster by value, so the
 * simulation never holds a live technician; {@link #toTechnician()} builds a fresh in-memory technician (empty load)
 * for each replay.
 */
public record TechnicianProfile(String id, String supportTeamId, int experienceYears, int maxCapacity, Set<Skill> skills) {

    private static final String SIMULATION_MAIL_DOMAIN = "@simulation.invalid";

    public TechnicianProfile {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (experienceYears < 0) {
            throw new IllegalArgumentException("experienceYears must not be negative");
        }
        if (maxCapacity < 1) {
            throw new IllegalArgumentException("maxCapacity must be at least 1");
        }
        skills = Set.copyOf(Objects.requireNonNull(skills, "skills cannot be null"));
    }

    /** Copies the values of a technician; the technician itself is neither kept nor changed. */
    public static TechnicianProfile of(Technician technician) {
        return new TechnicianProfile(technician.getId(), technician.getSupportTeamId(),
                technician.getExperienceYears(), technician.getMaxCapacity(), technician.getSkills());
    }

    /** Whether the technician has a skill of the category. */
    public boolean hasCategorySkill(IncidentCategory category) {
        return category != null && skills.stream().anyMatch(skill -> skill.category() == category);
    }

    /** A new technician with these values and no load. */
    public Technician toTechnician() {
        String mail = id.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._%+-]", "-") + SIMULATION_MAIL_DOMAIN;
        return new Technician(id, "Simulated " + id, new Email(mail), "not-a-login", skills, experienceYears,
                maxCapacity, supportTeamId);
    }
}
