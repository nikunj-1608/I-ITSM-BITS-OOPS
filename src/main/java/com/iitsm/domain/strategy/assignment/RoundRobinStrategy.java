package com.iitsm.domain.strategy.assignment;

import com.iitsm.domain.exception.TechnicianUnavailableException;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.Technician;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pattern: Strategy.
 * Deterministic round-robin cyclic rotation among eligible candidates.
 *
 * <p>Explainable without side effects (ADR-044): scoring a rotation position is separate from advancing it.
 * {@link #rank} reads the current position and leaves it alone; {@link #recommend} takes the position and advances it
 * in one atomic step, then explains the choice from that same position, so the explanation always describes the
 * choice that was made. The ranking lists the candidates in the order the rotation would serve them. Every candidate
 * scores {@value #ROUND_ROBIN_SCORE}: the rotation, not a score, decides.
 */
public class RoundRobinStrategy implements AssignmentStrategy {

    public static final String STRATEGY_NAME = "ROUND_ROBIN";
    public static final String ROTATION_KEY = "ROTATION";
    /** The fixed score of a round-robin choice. */
    public static final double ROUND_ROBIN_SCORE = 0.80;

    private final AtomicInteger counter = new AtomicInteger(0);

    @Override
    public AssignmentRecommendation recommend(Incident incident, List<Technician> candidates) {
        requireCandidates(candidates);
        int index = slotOf(counter.getAndIncrement(), candidates.size());
        String rationale = String.format("Assigned via cyclic round-robin distribution (queue slot #%d of %d)",
                index + 1, candidates.size());
        return rotationFrom(index, candidates).toRecommendation(STRATEGY_NAME, rationale);
    }

    @Override
    public List<CandidateScore> rank(Incident incident, List<Technician> candidates) {
        requireCandidates(candidates);
        return rotationFrom(slotOf(counter.get(), candidates.size()), candidates).scores();
    }

    /** A new rotation starting at the first slot; the live rotation is not touched. */
    @Override
    public AssignmentStrategy freshInstance() {
        return new RoundRobinStrategy();
    }

    private static int slotOf(int position, int size) {
        return Math.abs(position % size);
    }

    private static RankedCandidates rotationFrom(int index, List<Technician> candidates) {
        int size = candidates.size();
        List<RankedCandidates.Entry> entries = new ArrayList<>(size);
        for (int turn = 0; turn < size; turn++) {
            int slot = (index + turn) % size;
            String detail = String.format(Locale.ROOT, "queue slot #%d of %d, turn %d in the rotation",
                    slot + 1, size, turn + 1);
            entries.add(new RankedCandidates.Entry(candidates.get(slot),
                    List.of(FactorScore.of(ROTATION_KEY, ROUND_ROBIN_SCORE, 1.0, detail)), ROUND_ROBIN_SCORE));
        }
        return RankedCandidates.inOrder(entries);
    }

    private static void requireCandidates(List<Technician> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            throw new TechnicianUnavailableException("No available technicians found for round-robin assignment");
        }
    }

    @Override
    public String getStrategyName() {
        return STRATEGY_NAME;
    }

    @Override
    public String getDescription() {
        return "Distributes tickets in equal, cyclic rotation among all available technicians.";
    }
}
