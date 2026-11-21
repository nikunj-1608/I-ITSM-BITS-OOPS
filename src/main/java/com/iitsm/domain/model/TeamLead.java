package com.iitsm.domain.model;

import java.util.Set;

/**
 * OOP: Specialised Technician with team leadership and escalation privileges.
 * Inherits all Technician capabilities and represents real is-a hierarchy.
 */
public class TeamLead extends Technician {

    public TeamLead(
            String id,
            String name,
            Email email,
            String passwordHash,
            Set<Skill> skills,
            int experienceYears,
            int maxCapacity,
            String supportTeamId) {
        super(id, name, email, passwordHash, UserRole.ROLE_TEAM_LEAD, skills, experienceYears, maxCapacity, supportTeamId);
    }

    @Override
    public boolean canViewIncident(Incident incident, String owningTeamId) {
        if (incident == null) return false;
        if (super.canViewIncident(incident, owningTeamId)) {
            return true;
        }
        // Team lead can inspect any ticket in their team queue
        return getSupportTeamId() != null && getSupportTeamId().equals(owningTeamId);
    }

    @Override
    public boolean canExecuteAction(Incident incident, IncidentAction action, String owningTeamId) {
        if (incident == null || action == null) return false;
        if (action == IncidentAction.VIEW) {
            return canViewIncident(incident, owningTeamId);
        }
        boolean inTeam = getSupportTeamId() != null && getSupportTeamId().equals(owningTeamId);
        if (getId().equals(incident.getAssigneeId()) || inTeam) {
            return action == IncidentAction.ACKNOWLEDGE
                    || action == IncidentAction.START_INVESTIGATION
                    || action == IncidentAction.REQUEST_INFO
                    || action == IncidentAction.RESOLVE
                    || action == IncidentAction.FLAG_WRONG_DOMAIN
                    || action == IncidentAction.REASSIGN;
        }
        return false;
    }

    /** A team lead may move the tickets of their team between the team's technicians (ADR-045). */
    @Override
    public boolean canReassignTeamTickets() {
        return true;
    }

    /** A team lead may promote any resolution they can see (ADR-042); the ticket's access rules still apply. */
    @Override
    public boolean canPromoteResolution(Resolution resolution) {
        return resolution != null;
    }
}

