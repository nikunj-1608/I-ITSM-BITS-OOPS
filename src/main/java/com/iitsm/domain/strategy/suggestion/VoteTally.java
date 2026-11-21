package com.iitsm.domain.strategy.suggestion;

/**
 * OOP: Immutable value object. How many technicians said a suggested fix worked and how many said it did not, over all
 * the tickets it was voted on (ADR-041).
 */
public record VoteTally(long worked, long didNotWork) {

    private static final VoteTally NONE = new VoteTally(0, 0);

    public VoteTally {
        if (worked < 0 || didNotWork < 0) {
            throw new IllegalArgumentException("vote counts cannot be negative: " + worked + ", " + didNotWork);
        }
    }

    public static VoteTally none() {
        return NONE;
    }

    /**
     * The smoothed share of "worked" votes, (worked + 1) / (worked + didNotWork + 2): 0.5 without votes, strictly
     * between 0 and 1 for any count, so a single vote never decides.
     */
    public double smoothedWorkedShare() {
        return (worked + 1.0) / (worked + didNotWork + 2.0);
    }
}
