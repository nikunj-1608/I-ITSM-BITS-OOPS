package com.iitsm.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Promote a resolved ticket's fix into the knowledge base (ADR-042); a blank title means "use the ticket's title". */
public record PromoteArticleRequest(
        @NotBlank @Size(max = 64) String sourceIncidentId,
        @Size(max = 255) String title) {}
