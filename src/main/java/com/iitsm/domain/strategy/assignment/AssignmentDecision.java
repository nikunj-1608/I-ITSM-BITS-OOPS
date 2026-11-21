package com.iitsm.domain.strategy.assignment;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * OOP: Immutable value object. Why a ticket got its technician, recorded when the assignee was set (ADR-044, Phase L2).
 *
 * <p>A decision made by a strategy ({@link PoolKind#OWNING_TEAM} or {@link PoolKind#SKILLED_OVERFLOW}) carries the
 * strategy's name and its ranking, best first, cut to the configured number of candidates (the chosen technician is
 * always rank 1, so it is always kept); {@code candidateCount} is the size of the ranking before the cut. Every other
 * kind has no strategy and no ranking: it records who set the assignee ({@code actorId}, a user id since ADR-037, or
 * {@code SYSTEM}) and, for a duplicate riding its parent, the parent ticket ({@code relatedIncidentId}).
 */
public record AssignmentDecision(
        String id,
        String incidentId,
        PoolKind poolKind,
        String strategyName,
        String chosenTechnicianId,
        String actorId,
        String relatedIncidentId,
        Instant decidedAt,
        int candidateCount,
        List<CandidateScore> candidates) {

    public AssignmentDecision {
        requireText(id, "id");
        requireText(incidentId, "incidentId");
        Objects.requireNonNull(poolKind, "poolKind cannot be null");
        requireText(chosenTechnicianId, "chosenTechnicianId");
        requireText(actorId, "actorId");
        Objects.requireNonNull(decidedAt, "decidedAt cannot be null");
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates cannot be null"));
        if (poolKind.isRankedByStrategy()) {
            requireRanking(strategyName, chosenTechnicianId, candidates, candidateCount);
        } else if (strategyName != null || !candidates.isEmpty() || candidateCount != 0) {
            throw new IllegalArgumentException(poolKind + " decisions have no strategy and no ranking");
        }
        if (poolKind == PoolKind.INHERITED_FROM_PARENT) {
            requireText(relatedIncidentId, "relatedIncidentId");
        }
    }

    /** A strategy's choice among routed candidates; keeps the best {@code maxCandidates} of its ranking. */
    public static AssignmentDecision byStrategy(String id, String incidentId, PoolKind pool,
            AssignmentRecommendation recommendation, String actorId, Instant decidedAt, int maxCandidates) {
        if (maxCandidates < 1) {
            throw new IllegalArgumentException("maxCandidates must be at least 1");
        }
        String chosenId = recommendation.technician().getId();
        List<CandidateScore> ranking = recommendation.ranking().isEmpty()
                ? List.of(CandidateScore.opaque(chosenId, recommendation.score()))
                : recommendation.ranking();
        List<CandidateScore> kept = ranking.subList(0, Math.min(maxCandidates, ranking.size()));
        return new AssignmentDecision(id, incidentId, pool, recommendation.strategyName(), chosenId, actorId, null,
                decidedAt, ranking.size(), kept);
    }

    /** An administrator chose the technician by hand. */
    public static AssignmentDecision manual(String id, String incidentId, String technicianId, String actorId, Instant decidedAt) {
        return withoutRanking(id, incidentId, PoolKind.MANUAL, technicianId, actorId, null, decidedAt);
    }

    /** A confident duplicate rides its parent's technician (F4). */
    public static AssignmentDecision inheritedFromParent(String id, String incidentId, String technicianId,
            String parentIncidentId, String actorId, Instant decidedAt) {
        return withoutRanking(id, incidentId, PoolKind.INHERITED_FROM_PARENT, technicianId, actorId, parentIncidentId, decidedAt);
    }

    /** The administrator rejected a wrong-domain request: the ticket returns to the technician who flagged it. */
    public static AssignmentDecision requeuedToSameTechnician(String id, String incidentId, String technicianId,
            String actorId, Instant decidedAt) {
        return withoutRanking(id, incidentId, PoolKind.REQUEUED_TO_SAME_TECHNICIAN, technicianId, actorId, null, decidedAt);
    }

    private static AssignmentDecision withoutRanking(String id, String incidentId, PoolKind pool, String technicianId,
            String actorId, String relatedIncidentId, Instant decidedAt) {
        return new AssignmentDecision(id, incidentId, pool, null, technicianId, actorId, relatedIncidentId, decidedAt, 0, List.of());
    }

    private static void requireRanking(String strategyName, String chosenId, List<CandidateScore> candidates, int count) {
        requireText(strategyName, "strategyName");
        if (candidates.isEmpty() || !candidates.get(0).chosen() || !candidates.get(0).technicianId().equals(chosenId)) {
            throw new IllegalArgumentException("a strategy decision starts its ranking with the chosen technician");
        }
        if (count < candidates.size()) {
            throw new IllegalArgumentException("candidateCount cannot be smaller than the stored ranking");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
