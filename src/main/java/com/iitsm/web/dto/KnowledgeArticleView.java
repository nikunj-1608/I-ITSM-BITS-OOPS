package com.iitsm.web.dto;

import java.time.Instant;

/** Staff view of a knowledge-base article (ADR-042): technical content, its source ticket and who promoted it. */
public record KnowledgeArticleView(
        String id,
        String title,
        String category,
        String problem,
        String rootCause,
        String stepsTaken,
        String preventiveAction,
        String sourceIncidentId,
        String createdBy,
        Instant createdAt) {}
