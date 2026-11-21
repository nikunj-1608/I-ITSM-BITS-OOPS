package com.iitsm.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitsm.domain.strategy.nlp.DomainVocabulary;
import com.iitsm.domain.strategy.nlp.WordListDomainVocabulary;
import com.iitsm.domain.strategy.nlp.TfIdfVectorizer;
import com.iitsm.domain.strategy.similarity.CosineTfIdfSimilarity;
import com.iitsm.domain.strategy.similarity.DuplicateMatchPolicy;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Records the duplicate-detection numbers that ADR-016 accepts and that a lexicon or corpus change can move:
 * link rate per labelled group, precision, the highest-scoring genuine non-duplicate (the safety margin below
 * the threshold) and how many paraphrase links can be lost before {@code DuplicatePairSetTest} fails.
 * Report only; the acceptance assertions stay in {@code DuplicatePairSetTest}.
 */
@Tag("benchmark")
class DuplicateBenchmarkTest {

    /** Same production defaults as {@code DuplicatePairSetTest} (application.yml {@code iitsm.duplicates.*}). */
    private static final double THRESHOLD = 0.30;
    private static final int MIN_SHARED_TERMS = 1;
    private static final double PARAPHRASE_RECALL_TARGET = 0.59;
    private static final String KNOWN_FALSE_LINK = "L02";
    private static final String PARAPHRASE_GROUP = "paraphrase";
    private static final int TOP_NON_DUPLICATES = 5;
    private static final String REPORT_FILE = "duplicate-benchmark.txt";

    /** Smallest threshold the policy accepts: a policy with it applies the content guard and nothing else. */
    private static final double GUARD_ONLY_THRESHOLD = Double.MIN_VALUE;

    /**
     * {@code passesGuard}: the pair shares enough domain terms, so only the similarity threshold keeps it apart.
     * ADR-016's 0.05 safety margin is measured over these pairs.
     */
    private record Scored(BenchmarkData.LabelledPair pair, double cosine, boolean linked, boolean passesGuard) {}

    @Test
    @DisplayName("Benchmark: duplicate link rates, precision and safety margin are recorded (report only)")
    void recordsDuplicateBaseline() throws IOException {
        TfIdfVectorizer vectorizer = new TfIdfVectorizer();
        vectorizer.train(BenchmarkData.corpusDocuments());
        CosineTfIdfSimilarity cosine = new CosineTfIdfSimilarity(vectorizer);
        DomainVocabulary vocabulary = WordListDomainVocabulary.duplicateVocabulary();
        DuplicateMatchPolicy policy = new DuplicateMatchPolicy(cosine, vocabulary, THRESHOLD, MIN_SHARED_TERMS);
        DuplicateMatchPolicy guardOnly = new DuplicateMatchPolicy(cosine, vocabulary, GUARD_ONLY_THRESHOLD, MIN_SHARED_TERMS);

        List<Scored> scored = BenchmarkData.labelledPairs().stream()
                .map(p -> new Scored(p, cosine.calculateSimilarity(p.textA(), p.textB()),
                        policy.match(p.textA(), p.textB()).isPresent(),
                        guardOnly.match(p.textA(), p.textB()).isPresent()))
                .collect(Collectors.toList());
        assertThat(scored).as("labelled pairs").isNotEmpty();

        List<String> report = new ArrayList<>();
        report.add("Duplicate benchmark (threshold " + THRESHOLD + ", min shared terms " + MIN_SHARED_TERMS + ")");
        report.addAll(groupRates(scored));
        report.addAll(precisionAndMargin(scored));
        report.addAll(paraphraseSlack(scored));
        Path written = BenchmarkReport.write(REPORT_FILE, report);
        assertThat(written).exists();
    }

    private List<String> groupRates(List<Scored> scored) {
        Map<String, List<Scored>> byGroup = scored.stream()
                .collect(Collectors.groupingBy(s -> s.pair().group(), TreeMap::new, Collectors.toList()));
        List<String> lines = new ArrayList<>();
        lines.add("");
        lines.add("== links per group");
        byGroup.forEach((group, rows) -> lines.add(String.format("  %-16s %3d/%3d linked",
                group, rows.stream().filter(Scored::linked).count(), rows.size())));
        return lines;
    }

    private List<String> precisionAndMargin(List<Scored> scored) {
        List<Scored> linked = scored.stream().filter(Scored::linked).collect(Collectors.toList());
        long falseLinks = linked.stream().filter(s -> !s.pair().duplicate()).count();
        List<Scored> guardedNonDuplicates = scored.stream()
                .filter(s -> !s.pair().duplicate() && !KNOWN_FALSE_LINK.equals(s.pair().id()) && s.passesGuard())
                .sorted(Comparator.comparingDouble(Scored::cosine).reversed())
                .collect(Collectors.toList());
        long blockedByGuard = scored.stream()
                .filter(s -> !s.pair().duplicate() && !s.passesGuard() && s.cosine() >= THRESHOLD).count();
        double highest = guardedNonDuplicates.isEmpty() ? 0.0 : guardedNonDuplicates.getFirst().cosine();
        List<String> lines = new ArrayList<>();
        lines.add("");
        lines.add(String.format("== precision %d/%d linked pairs are duplicates (false links: %d)",
                linked.size() - falseLinks, linked.size(), falseLinks));
        scored.stream().filter(s -> KNOWN_FALSE_LINK.equals(s.pair().id())).forEach(s -> lines.add(
                String.format("known false link %s: cosine %.4f, linked=%s", KNOWN_FALSE_LINK, s.cosine(), s.linked())));
        lines.add(String.format("non-duplicates above the threshold that only the content guard blocks: %d", blockedByGuard));
        lines.add(String.format("highest non-duplicate that passes the content guard: cosine %.4f -> margin %.4f "
                + "(ADR-016 requires >= 0.05)", highest, THRESHOLD - highest));
        guardedNonDuplicates.stream().limit(TOP_NON_DUPLICATES).forEach(s -> lines.add(String.format(
                "  %s %-14s cosine %.4f", s.pair().id(), s.pair().group(), s.cosine())));
        return lines;
    }

    private List<String> paraphraseSlack(List<Scored> scored) {
        List<Scored> paraphrases = scored.stream()
                .filter(s -> PARAPHRASE_GROUP.equals(s.pair().group())).collect(Collectors.toList());
        long linked = paraphrases.stream().filter(Scored::linked).count();
        long needed = (long) Math.ceil(PARAPHRASE_RECALL_TARGET * paraphrases.size());
        List<String> lines = new ArrayList<>();
        lines.add("");
        lines.add(String.format("== paraphrase recall %d/%d = %.3f (target %.2f needs %d; links that can be lost: %d)",
                linked, paraphrases.size(), (double) linked / paraphrases.size(), PARAPHRASE_RECALL_TARGET,
                needed, linked - needed));
        return lines;
    }
}
