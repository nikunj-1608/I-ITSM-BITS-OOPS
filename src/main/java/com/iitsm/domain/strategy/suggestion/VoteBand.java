package com.iitsm.domain.strategy.suggestion;

/**
 * OOP: Immutable value object. How far votes may move a suggestion's score (ADR-041): the score is the similarity times a
 * factor in the band (1 - weight, 1 + weight), exactly 1 without votes. With the default weight 0.2 the factor stays
 * strictly between 0.8 and 1.2 whatever the counts, so votes reorder close suggestions and never override similarity.
 *
 * @param weight half the width of the band, in [0, 1); 0 switches votes off
 */
public record VoteBand(double weight) {

    public VoteBand {
        if (weight < 0.0 || weight >= 1.0 || Double.isNaN(weight)) {
            throw new IllegalArgumentException("Suggestion vote-weight must be in [0, 1): " + weight);
        }
    }

    /** No influence: every factor is 1. */
    public static VoteBand neutral() {
        return new VoteBand(0.0);
    }

    /** 1 - weight + 2 * weight * smoothed share: 1 for no votes or an even split, never reaching either bound. */
    public double factor(VoteTally tally) {
        return 1.0 - weight + 2.0 * weight * tally.smoothedWorkedShare();
    }
}
