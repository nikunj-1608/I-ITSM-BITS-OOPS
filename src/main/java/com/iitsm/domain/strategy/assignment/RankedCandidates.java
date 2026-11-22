package com.iitsm.domain.strategy.assignment;

import com.iitsm.domain.model.Technician;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * OOP: Immutable ranking of scored candidates, best first (ADR-044). The one place that turns scored technicians into
 * {@link CandidateScore}s and into an {@link AssignmentRecommendation}, so a strategy's recommendation is always the
 * top of its own ranking.
 *
 * <p>Ordering is a stable sort: candidates the strategy's order cannot tell apart keep their input order, so the first
 * of equal candidates wins, as every strategy has always done (strict {@code >} in the loops, {@code Stream.min/max}).
 */
public final class RankedCandidates {

    /**
     * OOP: Immutable value object. One scored technician before ranking.
     */
    public record Entry(Technician technician, List<FactorScore> factorScores, double total) {

        public Entry {
            Objects.requireNonNull(technician, "technician cannot be null");
            factorScores = List.copyOf(factorScores);
        }
    }

    private final List<Entry> ordered;

    private RankedCandidates(List<Entry> ordered) {
        if (ordered.isEmpty()) {
            throw new IllegalArgumentException("a ranking needs at least one candidate");
        }
        this.ordered = List.copyOf(ordered);
    }

    /** Ranks the entries by {@code bestFirst}; equal entries keep their input order. */
    public static RankedCandidates rank(List<Entry> entries, Comparator<Entry> bestFirst) {
        List<Entry> sorted = new ArrayList<>(entries);
        sorted.sort(bestFirst);
        return new RankedCandidates(sorted);
    }

    /** Entries already in rank order (round robin's rotation). */
    public static RankedCandidates inOrder(List<Entry> entries) {
        return new RankedCandidates(entries);
    }

    /** The best entry: the strategy's choice. */
    public Entry top() {
        return ordered.get(0);
    }

    /** The ranking as candidate scores: rank 1 is the chosen one. */
    public List<CandidateScore> scores() {
        List<CandidateScore> scores = new ArrayList<>(ordered.size());
        for (int i = 0; i < ordered.size(); i++) {
            Entry entry = ordered.get(i);
            scores.add(new CandidateScore(entry.technician().getId(), entry.factorScores(), entry.total(), i + 1, i == 0));
        }
        return List.copyOf(scores);
    }

    /** The recommendation for the top entry, carrying this ranking as its explanation. */
    public AssignmentRecommendation toRecommendation(String strategyName, String rationale) {
        return new AssignmentRecommendation(top().technician(), top().total(), strategyName, rationale, scores());
    }
}
