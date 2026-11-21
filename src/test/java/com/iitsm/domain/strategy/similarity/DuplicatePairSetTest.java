package com.iitsm.domain.strategy.similarity;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitsm.domain.strategy.nlp.WordListDomainVocabulary;
import com.iitsm.domain.strategy.nlp.TfIdfVectorizer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ADR-016 acceptance test: the production vectorizer, vocabulary and default policy (threshold 0.30, one shared
 * domain term) over the labelled pair set from the H1 investigation. The pairs are synthetic, written for that
 * investigation, plus pairs adjudicated by hand from a live replay (group {@code adjudicated_live}); see
 * {@code docs/duplicate-detection-fix-summary.md}.
 */
class DuplicatePairSetTest {

    /** The one adjudicated pair the policy still links although the faults differ (monitors vs Ethernet port). */
    private static final Set<String> KNOWN_FALSE_LINKS = Set.of("L02");

    private record Pair(String id, String group, boolean duplicate, String textA, String textB) {}

    private static DuplicateMatchPolicy policy;
    private static List<Pair> pairs;

    @BeforeAll
    static void load() throws IOException {
        TfIdfVectorizer vectorizer = new TfIdfVectorizer();
        vectorizer.train(readCorpus());
        policy = new DuplicateMatchPolicy(new CosineTfIdfSimilarity(vectorizer), WordListDomainVocabulary.duplicateVocabulary(), 0.30, 1);
        pairs = readPairs();
    }

    private static List<String> readCorpus() throws IOException {
        List<String> documents = new ArrayList<>();
        try (BufferedReader reader = open("/sim/corpus.csv")) {
            reader.readLine(); // header
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",", 3); // same parsing as IntelligenceConfig
                if (parts.length >= 2) {
                    documents.add(parts.length == 3 ? parts[1] + " " + parts[2] : parts[1]);
                }
            }
        }
        return documents;
    }

    private static List<Pair> readPairs() throws IOException {
        List<Pair> rows = new ArrayList<>();
        try (BufferedReader reader = open("/duplicates/labelled_pairs.tsv")) {
            reader.readLine();
            String line;
            while ((line = reader.readLine()) != null) {
                String[] f = line.split("\\t", -1);
                rows.add(new Pair(f[0], f[1], "1".equals(f[2]), f[3], f[4]));
            }
        }
        return rows;
    }

    private static BufferedReader open(String resource) throws IOException {
        InputStream in = DuplicatePairSetTest.class.getResourceAsStream(resource);
        assertThat(in).as("classpath resource " + resource).isNotNull();
        return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    private static boolean links(Pair pair) {
        return policy.match(pair.textA(), pair.textB()).isPresent();
    }

    private static List<Pair> group(String name) {
        return pairs.stream().filter(p -> p.group().equals(name)).collect(Collectors.toList());
    }

    @Test
    @DisplayName("Identical text and the S4 demo paraphrases always link")
    void identicalAndDemoPairsAlwaysLink() {
        assertThat(group("identical")).isNotEmpty().allMatch(DuplicatePairSetTest::links);
        assertThat(group("s4_demo")).isNotEmpty().allMatch(DuplicatePairSetTest::links);
    }

    @Test
    @DisplayName("Different faults, different categories, filler-only tickets and random-word templates never link")
    void unrelatedPairsNeverLink() {
        for (String name : List.of("hard_negative", "easy_negative", "audit_observed", "audit_template")) {
            assertThat(group(name)).as(name).isNotEmpty().noneMatch(DuplicatePairSetTest::links);
        }
    }

    @Test
    @DisplayName("At least 59 % of natural paraphrases link (ADR-016 target)")
    void naturalParaphraseRecall() {
        List<Pair> paraphrases = group("paraphrase");
        long linked = paraphrases.stream().filter(DuplicatePairSetTest::links).count();

        assertThat((double) linked / paraphrases.size()).isGreaterThanOrEqualTo(0.59);
    }

    @Test
    @DisplayName("Overall precision is at least 95 % and the only false link is the documented one")
    void precisionAndKnownFalseLinks() {
        List<Pair> linked = pairs.stream().filter(DuplicatePairSetTest::links).collect(Collectors.toList());
        List<String> falseLinks = linked.stream().filter(p -> !p.duplicate()).map(Pair::id).collect(Collectors.toList());

        assertThat((double) (linked.size() - falseLinks.size()) / linked.size()).isGreaterThanOrEqualTo(0.95);
        assertThat(falseLinks).isSubsetOf(KNOWN_FALSE_LINKS);
    }
}
