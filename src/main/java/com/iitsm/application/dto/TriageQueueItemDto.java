package com.iitsm.application.dto;

import java.time.Instant;
import java.util.List;

/**
 * Unified DTO for the Administrator triage queue: one entry per ticket, either held back by the intake for low
 * classification confidence or flagged by a technician as the wrong domain (then it carries the technician, the reason
 * and the id of the reassignment request, ADR-029). Both carry the classifier's suggestion.
 */
public record TriageQueueItemDto(
        String triageType, // "LOW_CONFIDENCE" or "WRONG_DOMAIN"
        String incidentId,
        String displayId, // staff ticket name: priority and id, e.g. P2-FIN-2026-000142 (ADR-013)
        String title,
        String description,
        String category,
        String serviceId,
        String serviceName,
        Double confidence,
        List<CategorySuggestionDto> suggestions,
        String requestingTechnicianId,
        String reason,
        Instant createdAt,
        String requestId // the pending reassignment request of a WRONG_DOMAIN entry; null for LOW_CONFIDENCE
) {
    public record CategorySuggestionDto(String category, double confidence) {}
}

