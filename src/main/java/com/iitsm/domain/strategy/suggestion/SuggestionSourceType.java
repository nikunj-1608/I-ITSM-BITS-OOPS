package com.iitsm.domain.strategy.suggestion;

/**
 * OOP: Where a suggested resolution comes from (ADR-040). A resolved ticket's own resolution report, or an article of
 * the small knowledge base (added in Phase K3).
 */
public enum SuggestionSourceType {
    RESOLVED_TICKET,
    KB_ARTICLE
}
