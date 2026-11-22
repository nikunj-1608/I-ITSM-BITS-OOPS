package com.iitsm.domain.strategy.assignment;

import com.iitsm.domain.model.ItService;
import java.util.Map;
import java.util.Objects;

/**
 * OOP: Immutable value object. How many tickets of one IT service each candidate technician has resolved (ADR-044,
 * Phase L4), read once per ranking from a {@link ServiceExperienceSource} and carried in the {@link AssignmentContext}.
 *
 * <p>It {@link #applies()} only to a ticket with a specific service: the general service stands for "no specific
 * service matched", so experience with it says nothing, and the factor then scores every candidate 0.
 */
public record ServiceExperience(String serviceId, Map<String, Integer> resolvedByTechnician) {

    private static final ServiceExperience NONE = new ServiceExperience(null, Map.of());

    public ServiceExperience {
        resolvedByTechnician = Map.copyOf(Objects.requireNonNull(resolvedByTechnician, "resolvedByTechnician cannot be null"));
        resolvedByTechnician.values().forEach(count -> {
            if (count == null || count < 0) {
                throw new IllegalArgumentException("a resolved-ticket count cannot be negative");
            }
        });
    }

    /** No knowledge: every candidate has 0. */
    public static ServiceExperience none() {
        return NONE;
    }

    /** Whether the ticket has a specific IT service, so that experience with it means something. */
    public boolean applies() {
        return serviceId != null && !serviceId.isBlank() && !ItService.GENERAL_SERVICE_ID.equals(serviceId);
    }

    /** Tickets of the service this technician has resolved; 0 when unknown. */
    public int resolvedBy(String technicianId) {
        return resolvedByTechnician.getOrDefault(technicianId, 0);
    }
}
