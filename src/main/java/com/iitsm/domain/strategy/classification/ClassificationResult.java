package com.iitsm.domain.strategy.classification;

import com.iitsm.domain.model.IncidentCategory;
import java.util.Collections;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * OOP: Value object representing the outcome of an automated incident classification.
 */
public record ClassificationResult(
        IncidentCategory category,
        double confidence,
        String classifierName,
        List<String> rationale,
        boolean requiresTriage,
        Map<IncidentCategory, Integer> keywordSupport,
        boolean securityAlert) {

    public static final double TRIAGE_THRESHOLD = 0.60;

    public ClassificationResult {
        Objects.requireNonNull(category, "category cannot be null");
        Objects.requireNonNull(classifierName, "classifierName cannot be null");
        rationale = (rationale != null) ? List.copyOf(rationale) : Collections.emptyList();
        keywordSupport = (keywordSupport != null) ? Map.copyOf(keywordSupport) : Map.of();
        confidence = Math.max(0.0, Math.min(1.0, confidence));
    }

    public static ClassificationResult of(
            IncidentCategory category,
            double confidence,
            String classifierName,
            List<String> rationale) {
        return of(category, confidence, classifierName, rationale, Map.of(), false);
    }

    /**
     * Like {@link #of(IncidentCategory, double, String, List)} for the keyword classifier, which reports how many
     * distinct lexicon terms every category has ({@code keywordSupport}); the composite classifier uses it to see whether
     * another model's answer is backed by keywords and whether the keyword evidence is split. {@code securityAlert} says
     * that a strong security term was found, whichever category the keywords chose.
     */
    public static ClassificationResult of(
            IncidentCategory category,
            double confidence,
            String classifierName,
            List<String> rationale,
            Map<IncidentCategory, Integer> keywordSupport,
            boolean securityAlert) {
        boolean triage = confidence < TRIAGE_THRESHOLD;
        return new ClassificationResult(category, confidence, classifierName, rationale, triage, keywordSupport,
                securityAlert);
    }

    /** The number of distinct keyword terms found for a category; 0 when none, or when the classifier reports none. */
    public int supportFor(IncidentCategory other) {
        return keywordSupport.getOrDefault(other, 0);
    }

    /**
     * The keyword support of {@link #category()} is larger than that of every other category: no tie and no stronger
     * rival (ADR-052). False when the classifier reports no keyword support at all.
     */
    public boolean leadsAllOtherCategories() {
        int own = supportFor(category);
        return own > 0 && keywordSupport.entrySet().stream()
                .filter(e -> e.getKey() != category)
                .allMatch(e -> e.getValue() < own);
    }

    /** The other categories that have exactly as much keyword support as {@link #category()} (split evidence). */
    public Set<IncidentCategory> tiedWith() {
        int own = supportFor(category);
        if (own == 0) {
            return Set.of();
        }
        return keywordSupport.entrySet().stream()
                .filter(e -> e.getKey() != category && e.getValue() == own)
                .map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
    }
}

