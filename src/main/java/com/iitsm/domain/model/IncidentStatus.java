package com.iitsm.domain.model;

/**
 * OOP: Represents the internal lifecycle states of an Incident.
 * Mapped to behavior implementations via the State Pattern.
 */
public enum IncidentStatus {
    NEW("New"),
    CLASSIFIED("Classified"),
    ASSIGNED("Assigned"),
    ACKNOWLEDGED("Acknowledged"),
    IN_PROGRESS("In Progress"),
    PENDING_INFO("Pending Info"),
    RESOLVED("Resolved"),
    VERIFIED("Verified"),
    CLOSED("Closed");

    private final String displayName;

    IncidentStatus(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * True while the assignee holds a capacity slot for a ticket in this state. The slot is taken on assignment (and
     * again when a rejected fix reopens the ticket) and released on resolve, close, flag-wrong-domain and reassignment.
     */
    public boolean holdsTechnicianSlot() {
        return this == ASSIGNED || this == ACKNOWLEDGED || this == IN_PROGRESS || this == PENDING_INFO;
    }

    /**
     * True when a reclassify into another team may take the ticket from its technician and route it again: the states in
     * which a technician works on it. A PENDING_INFO ticket waits for the employee with its SLA clock paused and is left
     * alone (ADR-021); a finished ticket only changes its label.
     */
    public boolean canBeRerouted() {
        return this == ASSIGNED || this == ACKNOWLEDGED || this == IN_PROGRESS;
    }

    /**
     * True when a team lead may give the ticket to another technician of the team (ADR-045): a waiting ticket
     * ({@code CLASSIFIED}) or one a technician works on (the {@link #canBeRerouted()} states). A {@code PENDING_INFO}
     * ticket waits for the employee with its SLA clock paused, and a finished ticket is history.
     */
    public boolean canBeReassignedByTeamLead() {
        return this == CLASSIFIED || canBeRerouted();
    }

    /**
     * True when the ticket's stored resolution is the one that ended it: RESOLVED, VERIFIED or CLOSED. A rejected fix
     * returns the ticket to IN_PROGRESS with the old resolution still stored, so "has a resolution" alone is not enough
     * (ADR-040: only these tickets are suggested as past fixes).
     */
    public boolean hasConfirmedResolution() {
        return this == RESOLVED || this == VERIFIED || this == CLOSED;
    }

    /** The statuses for which {@link #hasConfirmedResolution()} is true. */
    public static java.util.Set<IncidentStatus> withConfirmedResolution() {
        java.util.Set<IncidentStatus> statuses = java.util.EnumSet.noneOf(IncidentStatus.class);
        for (IncidentStatus status : values()) {
            if (status.hasConfirmedResolution()) {
                statuses.add(status);
            }
        }
        return java.util.Collections.unmodifiableSet(statuses);
    }
}

