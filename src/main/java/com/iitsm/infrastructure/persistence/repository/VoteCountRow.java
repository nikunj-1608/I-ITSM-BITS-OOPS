package com.iitsm.infrastructure.persistence.repository;

import com.iitsm.domain.strategy.suggestion.SuggestionSourceType;

/** One row of the vote-count query (ADR-041). */
public record VoteCountRow(SuggestionSourceType sourceType, String sourceId, Long worked, Long didNotWork) {}
