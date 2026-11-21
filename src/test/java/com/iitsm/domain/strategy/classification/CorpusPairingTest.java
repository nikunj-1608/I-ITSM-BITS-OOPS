package com.iitsm.domain.strategy.classification;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.strategy.nlp.TextNormalizer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Remaining-defects plan, Phase F (ADR-035, decision D16-A): the training corpus pairs every title with a description of
 * the same ticket. Before the repair 431 of its 630 rows paired a title with the description of another ticket (the
 * descriptions were cycled in a different order than the titles); {@code scripts/data/repair_corpus.py} fixed that and
 * this test keeps it fixed.
 */
class CorpusPairingTest {

    /**
     * Rows the repair rule could not match to a description that shares a word with the title: 14 after the repair by
     * plain words, 21 when words are compared as stemmed tokens as here (more than 400 before the repair).
     */
    private static final int MAX_UNMATCHED_ROWS = 25;

    private record Row(IncidentCategory category, String title, String description) {}

    private static final List<Row> ROWS = new ArrayList<>();

    @BeforeAll
    static void load() throws IOException {
        try (InputStream in = CorpusPairingTest.class.getResourceAsStream("/sim/corpus.csv");
                BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            reader.readLine(); // header
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    String[] parts = line.split(",", 3);
                    ROWS.add(new Row(IncidentCategory.valueOf(parts[0].trim()), parts[1], parts[2]));
                }
            }
        }
    }

    private static Set<String> contentWords(String text) {
        return new HashSet<>(TextNormalizer.tokenize(text));
    }

    @Test
    @DisplayName("The corpus keeps its shape: 630 rows, 70 per category")
    void shapeIsUnchanged() {
        Map<IncidentCategory, Integer> perCategory = new EnumMap<>(IncidentCategory.class);
        ROWS.forEach(row -> perCategory.merge(row.category(), 1, Integer::sum));

        assertThat(ROWS).hasSize(630);
        assertThat(perCategory.values()).hasSize(9).allMatch(count -> count == 70);
    }

    @Test
    @DisplayName("D16: almost every title shares a content word with its description")
    void titlesAndDescriptionsBelongTogether() {
        long unmatched = ROWS.stream()
                .filter(row -> contentWords(row.title()).stream().noneMatch(contentWords(row.description())::contains))
                .count();

        assertThat(unmatched).as("rows whose title and description share no content word").isLessThanOrEqualTo(MAX_UNMATCHED_ROWS);
    }

    @Test
    @DisplayName("Every row has a title and a description")
    void noEmptyFields() {
        assertThat(ROWS).allMatch(row -> !row.title().isBlank() && !row.description().isBlank());
    }
}
