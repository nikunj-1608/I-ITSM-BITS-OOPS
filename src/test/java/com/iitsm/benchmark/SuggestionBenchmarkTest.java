package com.iitsm.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.Priority;
import com.iitsm.domain.strategy.nlp.TfIdfVectorizer;
import com.iitsm.domain.strategy.nlp.WordListDomainVocabulary;
import com.iitsm.domain.strategy.similarity.CosineTfIdfSimilarity;
import com.iitsm.domain.strategy.suggestion.ResolutionSuggestion;
import com.iitsm.domain.strategy.suggestion.SuggestionCandidate;
import com.iitsm.domain.strategy.suggestion.SuggestionPolicy;
import com.iitsm.domain.strategy.suggestion.SuggestionQuery;
import com.iitsm.domain.strategy.suggestion.SuggestionSourceType;
import com.iitsm.domain.strategy.suggestion.TfIdfResolutionSuggester;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Suggestion quality and cost (ADR-040). Past fixes and new tickets come from {@code src/test/resources/suggestions};
 * the vectorizer is trained on the production corpus and the vocabulary is the production duplicate vocabulary, as the
 * application wires them. Records the top-3 hit rate, the top-1 rate, how many "no fit" tickets still get a suggestion,
 * a threshold sweep, and the time of one request over a full 200-candidate window. The assertions are loose floors.
 */
@Tag("benchmark")
class SuggestionBenchmarkTest {

    /** Production defaults (application.yml iitsm.suggestions.*). */
    private static final int MAX_RESULTS = 3;
    private static final double MIN_SIMILARITY = 0.20;
    private static final int MIN_SHARED_TERMS = 1;
    private static final int CANDIDATE_WINDOW = 200;
    private static final int EXPLANATION_TERMS = 5;

    private static final double HIT_AT_3_FLOOR = 0.80;
    private static final double NO_FIT_SUGGESTED_CEILING = 0.40;
    private static final double[] SWEEP = {0.10, 0.15, 0.20, 0.25, 0.30, 0.35, 0.40};
    private static final int TIMING_RUNS = 50;
    private static final String NONE = "NONE";
    private static final String REPORT_FILE = "suggestion-benchmark.txt";

    private record NewTicket(String id, IncidentCategory category, String text, String expected) {
        boolean hasFit() {
            return !NONE.equals(expected);
        }
    }

    private record Outcome(int hits, int topOne, int fits, int noFitSuggested, int noFits) {
        double hitRate() {
            return (double) hits / fits;
        }

        double noFitRate() {
            return (double) noFitSuggested / noFits;
        }
    }

    @Test
    @DisplayName("Benchmark: top-3 hit rate, no-fit suggestions, threshold sweep and request time are recorded")
    void recordsSuggestionQuality() throws IOException {
        TfIdfVectorizer vectorizer = new TfIdfVectorizer();
        vectorizer.train(BenchmarkData.corpusDocuments());
        CosineTfIdfSimilarity cosine = new CosineTfIdfSimilarity(vectorizer);
        WordListDomainVocabulary vocabulary = WordListDomainVocabulary.duplicateVocabulary();
        List<SuggestionCandidate> past = pastResolutions();
        List<NewTicket> tickets = newTickets();

        List<String> report = new ArrayList<>();
        report.add("Suggestion benchmark: " + past.size() + " past fixes, " + tickets.size() + " new tickets");
        Outcome atDefault = evaluate(new TfIdfResolutionSuggester(cosine, vocabulary, policy(MIN_SIMILARITY)), past, tickets, report);
        report.add("");
        report.add("== threshold sweep (min-similarity: hit@3, no-fit tickets with a suggestion)");
        for (double threshold : SWEEP) {
            Outcome o = evaluate(new TfIdfResolutionSuggester(cosine, vocabulary, policy(threshold)), past, tickets, null);
            report.add(String.format(Locale.ROOT, "  %.2f: %d/%d, %d/%d", threshold, o.hits(), o.fits(), o.noFitSuggested(), o.noFits()));
        }
        report.add("");
        report.add(timing(new TfIdfResolutionSuggester(cosine, vocabulary, policy(MIN_SIMILARITY)), past, tickets));
        BenchmarkReport.write(REPORT_FILE, report);

        assertThat(atDefault.hitRate()).as("top-3 hit rate at the default threshold").isGreaterThanOrEqualTo(HIT_AT_3_FLOOR);
        assertThat(atDefault.noFitRate()).as("no-fit tickets that still get a suggestion").isLessThanOrEqualTo(NO_FIT_SUGGESTED_CEILING);
    }

    private static SuggestionPolicy policy(double minSimilarity) {
        return new SuggestionPolicy(MAX_RESULTS, minSimilarity, MIN_SHARED_TERMS, true, CANDIDATE_WINDOW, EXPLANATION_TERMS);
    }

    private static Outcome evaluate(TfIdfResolutionSuggester suggester, List<SuggestionCandidate> past,
            List<NewTicket> tickets, List<String> report) {
        int hits = 0;
        int topOne = 0;
        int fits = 0;
        int noFitSuggested = 0;
        if (report != null) {
            report.add("");
            report.add(String.format(Locale.ROOT, "== per ticket at min-similarity %.2f (expected: suggestions with score)", MIN_SIMILARITY));
        }
        for (NewTicket ticket : tickets) {
            List<ResolutionSuggestion> result = suggester.suggest(
                    new SuggestionQuery(ticket.id(), ticket.category(), ticket.text(), Set.of(ticket.id())), past);
            List<String> ids = result.stream().map(s -> s.source().sourceId()).toList();
            if (ticket.hasFit()) {
                fits++;
                hits += ids.contains(ticket.expected()) ? 1 : 0;
                topOne += !ids.isEmpty() && ids.get(0).equals(ticket.expected()) ? 1 : 0;
            } else if (!ids.isEmpty()) {
                noFitSuggested++;
            }
            if (report != null) {
                report.add("  " + ticket.id() + " " + ticket.expected() + ": " + describe(result));
            }
        }
        Outcome outcome = new Outcome(hits, topOne, fits, noFitSuggested, tickets.size() - fits);
        if (report != null) {
            report.add(String.format(Locale.ROOT, "  hit@3 %d/%d (%.3f), top-1 %d/%d, no-fit tickets with a suggestion %d/%d",
                    hits, fits, outcome.hitRate(), topOne, fits, noFitSuggested, outcome.noFits()));
        }
        return outcome;
    }

    private static String describe(List<ResolutionSuggestion> result) {
        if (result.isEmpty()) {
            return "(none)";
        }
        return String.join(", ", result.stream()
                .map(s -> String.format(Locale.ROOT, "%s %.3f %s", s.source().sourceId(), s.similarity(), s.matchedTerms()))
                .toList());
    }

    /** One request over a full window: the network fixes copied to 200 same-category candidates. */
    private static String timing(TfIdfResolutionSuggester suggester, List<SuggestionCandidate> past, List<NewTicket> tickets) {
        List<SuggestionCandidate> network = past.stream().filter(c -> c.category() == IncidentCategory.NETWORK).toList();
        List<SuggestionCandidate> window = new ArrayList<>();
        for (int i = 0; window.size() < CANDIDATE_WINDOW; i++) {
            SuggestionCandidate c = network.get(i % network.size());
            window.add(new SuggestionCandidate(c.sourceType(), c.sourceId() + "-" + i, c.category(), c.priority(),
                    c.title(), c.problemText(), c.rootCause(), c.stepsTaken(), c.preventiveAction()));
        }
        NewTicket ticket = tickets.get(0);
        SuggestionQuery query = new SuggestionQuery(ticket.id(), ticket.category(), ticket.text(), Set.of());
        suggester.suggest(query, window); // warm-up
        long[] nanos = new long[TIMING_RUNS];
        for (int run = 0; run < TIMING_RUNS; run++) {
            long start = System.nanoTime();
            suggester.suggest(query, window);
            nanos[run] = System.nanoTime() - start;
        }
        Arrays.sort(nanos);
        return String.format(Locale.ROOT, "== one request over %d candidates: median %.2f ms, worst %.2f ms (%d runs, in-memory ranking only)",
                CANDIDATE_WINDOW, nanos[TIMING_RUNS / 2] / 1e6, nanos[TIMING_RUNS - 1] / 1e6, TIMING_RUNS);
    }

    private static List<SuggestionCandidate> pastResolutions() throws IOException {
        List<SuggestionCandidate> past = new ArrayList<>();
        for (String[] f : rows("/suggestions/past_resolutions.tsv")) {
            past.add(new SuggestionCandidate(SuggestionSourceType.RESOLVED_TICKET, f[0], IncidentCategory.valueOf(f[1]),
                    Priority.P3, f[2], f[3], f[4], f[5], f[6]));
        }
        return past;
    }

    private static List<NewTicket> newTickets() throws IOException {
        List<NewTicket> tickets = new ArrayList<>();
        for (String[] f : rows("/suggestions/new_tickets.tsv")) {
            tickets.add(new NewTicket(f[0], IncidentCategory.valueOf(f[1]), f[2] + " " + f[3], f[4]));
        }
        return tickets;
    }

    private static List<String[]> rows(String resource) throws IOException {
        List<String[]> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                Objects.requireNonNull(SuggestionBenchmarkTest.class.getResourceAsStream(resource), resource),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.strip();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#") && !trimmed.startsWith("id\t")) {
                    rows.add(trimmed.split("\t", -1));
                }
            }
        }
        return rows;
    }
}
