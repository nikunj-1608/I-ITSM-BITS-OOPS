package com.iitsm.domain.strategy.nlp;

/**
 * Pattern: Strategy / port.
 * Tells whether a normalised token (as produced by {@link TextNormalizer#tokenize}) is a domain term, i.e. a
 * word that names something technical (vpn, keyboard, postgres) rather than filler ("problem", "morning").
 * Lets duplicate detection ask for content evidence without depending on a concrete classifier.
 */
public interface DomainVocabulary {

    boolean isDomainTerm(String normalizedToken);
}
