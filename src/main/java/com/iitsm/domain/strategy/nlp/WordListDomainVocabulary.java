package com.iitsm.domain.strategy.nlp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

// OOP (final class + interface): a frozen word list behind the DomainVocabulary abstraction.
/**
 * Pattern: Strategy / port implementation. A {@link DomainVocabulary} backed by a plain list of technical words.
 *
 * <p>Duplicate detection asks this vocabulary for content evidence. It is deliberately a list of its own, not the
 * classifier's lexicon: the classifier's word list is tuned for category evidence and changes with it, the duplicate
 * vocabulary is frozen with the duplicate benchmark (ADR-035). Every word and its stem count as a domain term, so
 * "keyboards" matches "keyboard".
 */
public final class WordListDomainVocabulary implements DomainVocabulary {

    /** The classpath resource that holds the vocabulary of duplicate detection. */
    public static final String DUPLICATE_VOCABULARY_RESOURCE = "/sim/duplicate-vocabulary.txt";

    private final Set<String> terms = new HashSet<>();

    public WordListDomainVocabulary(Collection<String> words) {
        Objects.requireNonNull(words, "words cannot be null");
        for (String word : words) {
            String trimmed = word.trim().toLowerCase(java.util.Locale.ROOT);
            if (!trimmed.isEmpty()) {
                terms.add(trimmed);
                terms.add(TextNormalizer.stem(trimmed));
            }
        }
    }

    /** Reads one word per line; blank lines and lines starting with '#' are ignored. */
    public static WordListDomainVocabulary fromStream(InputStream stream) {
        Objects.requireNonNull(stream, "stream cannot be null");
        Set<String> words = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    words.add(trimmed);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the domain vocabulary", e);
        }
        return new WordListDomainVocabulary(words);
    }

    /** The vocabulary of duplicate detection, from {@link #DUPLICATE_VOCABULARY_RESOURCE}. */
    public static WordListDomainVocabulary duplicateVocabulary() {
        InputStream stream = WordListDomainVocabulary.class.getResourceAsStream(DUPLICATE_VOCABULARY_RESOURCE);
        if (stream == null) {
            throw new IllegalStateException("Missing classpath resource " + DUPLICATE_VOCABULARY_RESOURCE);
        }
        return fromStream(stream);
    }

    @Override
    public boolean isDomainTerm(String normalizedToken) {
        return normalizedToken != null && terms.contains(normalizedToken);
    }

    /** The number of words and stems known; for tests and logging. */
    public int size() {
        return terms.size();
    }
}
