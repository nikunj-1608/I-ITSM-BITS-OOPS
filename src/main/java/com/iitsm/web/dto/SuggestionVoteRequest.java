package com.iitsm.web.dto;

import com.iitsm.domain.strategy.suggestion.SuggestionSourceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// OOP (Immutability): SuggestionVoteRequest is a record, so it is a read-only data carrier with no setters.
/**
 * A technician's verdict on a suggested fix (ADR-041). An unknown {@code sourceType} is 400 {@code ERR_MALFORMED_JSON};
 * a missing field 400 {@code ERR_VALIDATION_FAILED}.
 */
public record SuggestionVoteRequest(
        @NotNull SuggestionSourceType sourceType,
        @NotBlank @Size(max = 64) String sourceId,
        @NotNull Boolean worked) {}
