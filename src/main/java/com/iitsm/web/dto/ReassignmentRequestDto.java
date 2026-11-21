package com.iitsm.web.dto;

import com.iitsm.domain.model.ReassignmentRequest;
import java.time.Instant;

// OOP (Immutability): ReassignmentRequestDto is a record, so it is a read-only data carrier with no setters.
/**
 * DTO representing an out-of-scope / wrong-domain reassignment request.
 */
public record ReassignmentRequestDto(
        String id,
        String incidentId,
        String requestingTechnicianId,
        String reason,
        String status,
        Instant requestedAt,
        Instant resolvedAt,
        String reviewedByAdminId
) {
    public static ReassignmentRequestDto fromDomain(ReassignmentRequest req) {
        if (req == null) return null;
        return new ReassignmentRequestDto(
                req.getId(),
                req.getIncidentId(),
                req.getRequestingTechnicianId(),
                req.getReason(),
                req.getStatus() != null ? req.getStatus().name() : null,
                req.getRequestedAt(),
                req.getResolvedAt(),
                req.getReviewedByAdminId()
        );
    }
}

