package com.iitsm.web.dto;

import java.util.List;

/**
 * Staff-only view of one suggested fix (ADR-040, ADR-041). Technical content only: no description, reporter, assignee or
 * notes of the source ticket. {@code sourceDisplayId} is the staff ticket name ({@code P2-NET-2026-000012}) of a source
 * ticket. {@code similarity} is the text similarity, {@code score} the ranking score (similarity times the bounded vote
 * factor), and the two counts are the technicians' votes on this source over all tickets.
 */
public record SuggestedResolutionView(
        String sourceType,
        String sourceId,
        String sourceDisplayId,
        String title,
        String rootCause,
        String stepsTaken,
        String preventiveAction,
        double similarity,
        double score,
        long workedVotes,
        long didNotWorkVotes,
        List<String> matchedTerms) {}
