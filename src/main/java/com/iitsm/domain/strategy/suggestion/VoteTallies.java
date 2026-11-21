package com.iitsm.domain.strategy.suggestion;

import java.util.Map;
import java.util.Objects;

/** OOP: Immutable value object. The vote counts of a set of suggestion sources; a source without votes has none. */
public final class VoteTallies {

    private static final VoteTallies NONE = new VoteTallies(Map.of());

    private final Map<SuggestionSourceKey, VoteTally> bySource;

    private VoteTallies(Map<SuggestionSourceKey, VoteTally> bySource) {
        this.bySource = Map.copyOf(bySource);
    }

    public static VoteTallies of(Map<SuggestionSourceKey, VoteTally> bySource) {
        return new VoteTallies(Objects.requireNonNull(bySource, "bySource cannot be null"));
    }

    public static VoteTallies none() {
        return NONE;
    }

    public VoteTally of(SuggestionSourceKey source) {
        return bySource.getOrDefault(source, VoteTally.none());
    }
}
