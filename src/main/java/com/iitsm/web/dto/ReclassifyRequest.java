package com.iitsm.web.dto;

import com.iitsm.domain.model.IncidentCategory;
import jakarta.validation.constraints.NotNull;

// OOP (Immutability): ReclassifyRequest is a record, so it is a read-only data carrier with no setters.
/**
 * Request payload for administrative incident reclassification.
 */
public record ReclassifyRequest(
        @NotNull(message = "Category cannot be null")
        IncidentCategory category,
        String serviceId,
        String reason
) {}

