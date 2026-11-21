package com.iitsm.domain.strategy.suggestion;

import java.util.Objects;

/** OOP: Immutable value object. Names one suggestion source: its type and its id (ADR-041). */
public record SuggestionSourceKey(SuggestionSourceType type, String id) {

    public SuggestionSourceKey {
        Objects.requireNonNull(type, "type cannot be null");
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("source id cannot be blank");
        }
        id = id.trim();
    }
}
