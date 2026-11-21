package com.iitsm.web.dto;

/** The stored vote and the source's vote counts after it (ADR-041). */
public record SuggestionVoteView(
        String sourceType,
        String sourceId,
        String targetIncidentId,
        boolean worked,
        long workedVotes,
        long didNotWorkVotes) {}
