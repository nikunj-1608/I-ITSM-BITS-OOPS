package com.iitsm.infrastructure.persistence.repository;

import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.Priority;

/** One row of the resolved-case projection (ADR-040): ticket text and its resolution report, nothing personal. */
public record ResolvedCaseRow(
        String incidentId,
        IncidentCategory category,
        Priority priority,
        String title,
        String description,
        String rootCause,
        String stepsTaken,
        String preventiveAction) {}
