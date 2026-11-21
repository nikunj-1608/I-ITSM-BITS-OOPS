package com.iitsm.domain.simulation.assignment;

import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.Priority;
import java.time.Duration;
import java.time.Instant;

/**
 * OOP: Immutable value object. What happened to one ticket in a replay (ADR-044, Phase L3): when it arrived, when a
 * technician took it, when it was resolved, who, whether that technician lacked the category skill, and how long
 * the work took.
 */
public record ReplayedTicket(
        int index,
        Priority priority,
        IncidentCategory category,
        Instant arrivedAt,
        Instant assignedAt,
        Instant completedAt,
        String technicianId,
        boolean unskilled,
        Duration serviceTime) {

    private static final double MILLIS_PER_MINUTE = 60_000.0;

    /** Minutes the ticket waited for a technician. */
    public double waitMinutes() {
        return minutes(Duration.between(arrivedAt, assignedAt));
    }

    /** Minutes from arrival to resolution, waiting included. */
    public double timeToResolveMinutes() {
        return minutes(Duration.between(arrivedAt, completedAt));
    }

    private static double minutes(Duration duration) {
        return duration.toMillis() / MILLIS_PER_MINUTE;
    }
}
