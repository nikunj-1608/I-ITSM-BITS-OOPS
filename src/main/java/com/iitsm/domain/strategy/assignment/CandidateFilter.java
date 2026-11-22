package com.iitsm.domain.strategy.assignment;

import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.Technician;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * OOP: Candidate technician filter applying operational constraints before strategy scoring:
 * capacity check, exclusion of a rejecting technician, the owning team first, and cross-team overflow only for
 * technicians who have the category's skill (ADR-036, decision D18): an unskilled technician of another team never
 * receives the ticket; when nobody qualifies the ticket waits. Since ADR-044 it also says which pool the candidates
 * came from ({@link #route}), so an assignment decision can record it.
 */
public class CandidateFilter {

    public CandidateFilter() {
    }

    /**
     * Filters available candidates enforcing capacity and rejector exclusion. Technicians of {@code owningTeamId} come
     * first; when none has free capacity, technicians of other teams who have the {@code category} skill; otherwise the
     * list is empty.
     */
    public List<Technician> filterCandidates(
            List<Technician> allTechnicians,
            String owningTeamId,
            IncidentCategory category,
            Set<String> excludedTechnicianIds) {
        return route(allTechnicians, owningTeamId, category, excludedTechnicianIds).technicians();
    }

    /** The same rule as {@link #filterCandidates}, with the pool the candidates came from. */
    public RoutedCandidates route(
            List<Technician> allTechnicians,
            String owningTeamId,
            IncidentCategory category,
            Set<String> excludedTechnicianIds) {
        if (allTechnicians == null || allTechnicians.isEmpty()) {
            return RoutedCandidates.none();
        }
        Set<String> exclusions = (excludedTechnicianIds != null) ? excludedTechnicianIds : Collections.emptySet();
        List<Technician> teamCandidates = new ArrayList<>();
        List<Technician> skilledOverflow = new ArrayList<>();
        for (Technician tech : allTechnicians) {
            // Must have capacity and not be excluded
            if (!tech.hasCapacity() || exclusions.contains(tech.getId())) {
                continue;
            }
            if (owningTeamId != null && owningTeamId.equalsIgnoreCase(tech.getSupportTeamId())) {
                teamCandidates.add(tech);
            } else if (tech.hasCategorySkill(category)) {
                skilledOverflow.add(tech);
            }
        }
        // The owning team first; skilled technicians of other teams only when the team has nobody free
        if (!teamCandidates.isEmpty()) {
            return new RoutedCandidates(PoolKind.OWNING_TEAM, teamCandidates);
        }
        if (!skilledOverflow.isEmpty()) {
            return new RoutedCandidates(PoolKind.SKILLED_OVERFLOW, skilledOverflow);
        }
        return RoutedCandidates.none();
    }
}
