package com.iitsm.domain.simulation.assignment;

import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.Priority;
import java.time.Duration;
import java.util.Objects;

/**
 * OOP: Immutable value object. One ticket of a synthetic stream (ADR-044, Phase L3): when it arrives (an offset from the
 * start of the virtual time), its priority and category, the text the strategies read, and the noise factor drawn
 * for it once, so that every strategy meets the same ticket with the same bad or good luck. Since Phase L4 it also
 * names the IT service of the ticket (one shared {@link #DEFAULT_SERVICE_ID} unless the stream was given services).
 */
public record SyntheticTicket(
        int index,
        Duration arrivalOffset,
        Priority priority,
        IncidentCategory category,
        String title,
        String description,
        double noise,
        String serviceId) {

    /** The service of a ticket in a stream without services. */
    public static final String DEFAULT_SERVICE_ID = "SIM-SERVICE";

    /** A ticket of the default service. */
    public SyntheticTicket(int index, Duration arrivalOffset, Priority priority, IncidentCategory category, String title,
            String description, double noise) {
        this(index, arrivalOffset, priority, category, title, description, noise, DEFAULT_SERVICE_ID);
    }

    public SyntheticTicket {
        if (index < 0) {
            throw new IllegalArgumentException("index must not be negative");
        }
        Objects.requireNonNull(arrivalOffset, "arrivalOffset cannot be null");
        if (arrivalOffset.isNegative()) {
            throw new IllegalArgumentException("arrivalOffset must not be negative");
        }
        Objects.requireNonNull(priority, "priority cannot be null");
        Objects.requireNonNull(category, "category cannot be null");
        Objects.requireNonNull(title, "title cannot be null");
        Objects.requireNonNull(description, "description cannot be null");
        if (!(noise > 0.0) || !Double.isFinite(noise)) {
            throw new IllegalArgumentException("noise must be a positive finite number");
        }
        if (serviceId == null || serviceId.isBlank()) {
            throw new IllegalArgumentException("serviceId must not be blank");
        }
    }
}
