package com.iitsm.domain.strategy.assignment;

import com.iitsm.domain.model.Technician;
import java.util.Locale;

/**
 * Pattern: Strategy (an {@link AssignmentFactor}). The technician's past experience with the ticket's IT service
 * (ADR-044, Phase L4): the number of tickets of that service they have resolved, scaled so that
 * {@code fullScoreTickets} of them give the full raw score of 1. A technician who has fixed this service's problems
 * before is more likely to fix the next one quickly.
 *
 * <p>The counts come from the {@link AssignmentContext} (read once per ranking through a {@link ServiceExperienceSource}
 * port), never from a repository. A ticket without a specific service, or a candidate with no history, scores 0; if
 * nobody has history the factor adds the same to every candidate and changes no ranking.
 */
public final class ServiceExperienceFactor implements AssignmentFactor {

    public static final String KEY = "SERVICE_EXPERIENCE";
    /** Resolved tickets of the service that give the full raw score. */
    public static final int DEFAULT_FULL_SCORE_TICKETS = 5;

    private final int fullScoreTickets;

    public ServiceExperienceFactor(int fullScoreTickets) {
        if (fullScoreTickets <= 0) {
            throw new IllegalArgumentException("fullScoreTickets must be positive");
        }
        this.fullScoreTickets = fullScoreTickets;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String label() {
        return "Service";
    }

    @Override
    public Reading read(AssignmentContext context, Technician technician) {
        ServiceExperience experience = context.serviceExperience();
        if (!experience.applies()) {
            return new Reading(0.0, "no specific IT service on this ticket");
        }
        int resolved = experience.resolvedBy(technician.getId());
        double raw = Math.min(1.0, resolved / (double) fullScoreTickets);
        String detail = String.format(Locale.ROOT, "%d ticket(s) of this service resolved (full score at %d)",
                resolved, fullScoreTickets);
        return new Reading(raw, detail);
    }
}
