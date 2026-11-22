package com.iitsm.domain.simulation.assignment;

import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.Skill;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * OOP: Immutable value object. The technicians of an offline comparison and which team owns each category (ADR-044,
 * Phase L3), the same inputs the live routing rule of ADR-036 uses: the owning team's technicians with free capacity
 * first, then skilled technicians of other teams, otherwise the ticket waits.
 *
 * <p>The pool is copied from the roster by value (see {@link TechnicianProfile}) or built from
 * {@link #syntheticDefault()}; a pool that could serve no category at all is refused.
 */
public record SimulationPool(List<TechnicianProfile> technicians, Map<IncidentCategory, String> owningTeamByCategory) {

    public SimulationPool {
        Objects.requireNonNull(technicians, "technicians cannot be null");
        if (technicians.isEmpty()) {
            throw new IllegalArgumentException("a simulation pool needs at least one technician");
        }
        Set<String> ids = new HashSet<>();
        for (TechnicianProfile profile : technicians) {
            if (!ids.add(profile.id())) {
                throw new IllegalArgumentException("duplicate technician id " + profile.id());
            }
        }
        technicians = List.copyOf(technicians);
        Map<IncidentCategory, String> owners = new EnumMap<>(IncidentCategory.class);
        owners.putAll(Objects.requireNonNull(owningTeamByCategory, "owningTeamByCategory cannot be null"));
        owningTeamByCategory = Collections.unmodifiableMap(owners);
        if (servableCategories(technicians, owningTeamByCategory).isEmpty()) {
            throw new IllegalArgumentException("the pool can serve no category");
        }
    }

    /**
     * The pool of a roster, or empty when the roster has no technician or could serve no category (the caller then falls
     * back to {@link #syntheticDefault()}).
     */
    public static java.util.Optional<SimulationPool> fromRoster(
            List<TechnicianProfile> technicians, Map<IncidentCategory, String> owningTeamByCategory) {
        if (technicians.isEmpty() || servableCategories(technicians, owningTeamByCategory).isEmpty()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new SimulationPool(technicians, owningTeamByCategory));
    }

    /** The team that owns tickets of the category, or null when no team does. */
    public String owningTeamOf(IncidentCategory category) {
        return owningTeamByCategory.get(category);
    }

    /** The categories some technician can take: the owning team has a member, or a technician has the skill. */
    public Set<IncidentCategory> servableCategories() {
        return servableCategories(technicians, owningTeamByCategory);
    }

    private static Set<IncidentCategory> servableCategories(
            List<TechnicianProfile> technicians, Map<IncidentCategory, String> owners) {
        Set<IncidentCategory> servable = EnumSet.noneOf(IncidentCategory.class);
        for (IncidentCategory category : IncidentCategory.values()) {
            String owner = owners.get(category);
            boolean ownerHasMember = owner != null && technicians.stream().anyMatch(t -> owner.equals(t.supportTeamId()));
            if (ownerHasMember || technicians.stream().anyMatch(t -> t.hasCategorySkill(category))) {
                servable.add(category);
            }
        }
        return Collections.unmodifiableSet(servable);
    }

    /**
     * A small invented pool for when there is no roster (a fresh database) and for tests: three teams of four, each
     * team owning three categories, with mixed experience, capacity and skills (some members lack a skill of one of
     * their team's categories, so a strategy can hand a ticket to a technician without the skill).
     */
    public static SimulationPool syntheticDefault() {
        List<TechnicianProfile> pool = new ArrayList<>();
        addTeam(pool, "SIM-NET", new int[] {2, 5, 9, 14}, new int[] {6, 8, 9, 12},
                new Skill("BGP", IncidentCategory.NETWORK), new Skill("Cisco Routing", IncidentCategory.NETWORK),
                new Skill("Firewall Rules", IncidentCategory.SECURITY),
                new Skill("Single Sign-On", IncidentCategory.AUTHENTICATION));
        addTeam(pool, "SIM-DATA", new int[] {3, 6, 10, 12}, new int[] {6, 7, 9, 10},
                new Skill("PostgreSQL", IncidentCategory.DATABASE), new Skill("SQL Tuning", IncidentCategory.DATABASE),
                new Skill("Storage Arrays", IncidentCategory.STORAGE), new Skill("Terraform", IncidentCategory.CLOUD));
        addTeam(pool, "SIM-APP", new int[] {1, 4, 8, 11}, new int[] {7, 9, 10, 12},
                new Skill("Java", IncidentCategory.APPLICATION), new Skill("Spring Boot", IncidentCategory.APPLICATION),
                new Skill("Desktop Support", IncidentCategory.SOFTWARE),
                new Skill("Hardware Repair", IncidentCategory.HARDWARE));
        Map<IncidentCategory, String> owners = new EnumMap<>(IncidentCategory.class);
        for (IncidentCategory c : List.of(IncidentCategory.NETWORK, IncidentCategory.SECURITY, IncidentCategory.AUTHENTICATION)) {
            owners.put(c, "SIM-NET");
        }
        for (IncidentCategory c : List.of(IncidentCategory.DATABASE, IncidentCategory.STORAGE, IncidentCategory.CLOUD)) {
            owners.put(c, "SIM-DATA");
        }
        for (IncidentCategory c : List.of(IncidentCategory.APPLICATION, IncidentCategory.SOFTWARE, IncidentCategory.HARDWARE)) {
            owners.put(c, "SIM-APP");
        }
        return new SimulationPool(pool, owners);
    }

    private static void addTeam(List<TechnicianProfile> pool, String team, int[] years, int[] capacity, Skill... skills) {
        for (int i = 0; i < skills.length; i++) {
            pool.add(new TechnicianProfile(team + "-" + (i + 1), team, years[i], capacity[i], Set.of(skills[i])));
        }
    }
}
