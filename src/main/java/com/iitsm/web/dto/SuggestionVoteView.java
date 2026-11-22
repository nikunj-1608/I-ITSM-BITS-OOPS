package com.iitsm.web.dto;

// OOP (Immutability): SuggestionVoteView is a record, so it is a read-only data carrier with no setters.
/** The stored vote and the source's vote counts after it (ADR-041). */
public record SuggestionVoteView(
        String sourceType,
        String sourceId,
        String targetIncidentId,
        boolean worked,
        long workedVotes,
        long didNotWorkVotes) {}
