package com.iitsm.benchmark;

import com.iitsm.domain.model.IncidentCategory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Loads the labelled data sets the benchmarks measure. Read-only; every file comes from the test classpath. */
final class BenchmarkData {

    private static final String HELD_OUT = "/sim/test_tickets.csv";
    private static final String AUDIT_EXAMPLES = "/classification/audit_examples.tsv";
    private static final String EVERYDAY_TICKETS = "/classification/everyday_tickets.tsv";
    private static final String EVERYDAY_VALIDATION = "/classification/everyday_validation.tsv";
    private static final String CORPUS = "/sim/corpus.csv";
    private static final String LABELLED_PAIRS = "/duplicates/labelled_pairs.tsv";
    private static final String TRIAGE_LABEL = "TRIAGE";

    /**
     * A ticket with the category it should get; empty when it should go to triage. {@code orTriage} (label
     * {@code CATEGORY|TRIAGE}) also accepts a ticket that ends below the auto-assignment gate whatever its category.
     */
    record LabelledTicket(String id, Optional<IncidentCategory> expectedCategory, String title, String description,
            boolean orTriage) {

        /** Whether the outcome meets the label, given the predicted category and whether the ticket is in triage. */
        boolean isMetBy(IncidentCategory predicted, boolean triaged) {
            if (expectedCategory.isEmpty()) {
                return triaged;
            }
            return expectedCategory.get() == predicted || (orTriage && triaged);
        }
    }

    /** A pair of ticket texts from the ADR-016 labelled set. */
    record LabelledPair(String id, String group, boolean duplicate, String textA, String textB) {}

    private BenchmarkData() { }

    /** The 36 held-out tickets (category,title,description; only the description may be quoted). */
    static List<LabelledTicket> heldOutTickets() {
        List<LabelledTicket> tickets = new ArrayList<>();
        List<String> lines = dataLines(HELD_OUT);
        for (int i = 0; i < lines.size(); i++) {
            String[] parts = lines.get(i).split(",", 3);
            IncidentCategory category = IncidentCategory.valueOf(parts[0].trim());
            tickets.add(new LabelledTicket("H" + (i + 1), Optional.of(category), parts[1], unquote(parts[2]), false));
        }
        return tickets;
    }

    /** The audit examples: id, expected (category or TRIAGE), title, description, source. */
    static List<LabelledTicket> auditExamples() {
        return labelledTsv(AUDIT_EXAMPLES);
    }

    /** The everyday tickets of ADR-052, in the audit-example format. */
    static List<LabelledTicket> everydayTickets() {
        return labelledTsv(EVERYDAY_TICKETS);
    }

    /** The everyday validation tickets of ADR-052, never used for tuning. */
    static List<LabelledTicket> everydayValidationTickets() {
        return labelledTsv(EVERYDAY_VALIDATION);
    }

    private static List<LabelledTicket> labelledTsv(String resource) {
        List<LabelledTicket> tickets = new ArrayList<>();
        for (String line : dataLines(resource)) {
            String[] f = line.split("\t", -1);
            boolean orTriage = f[1].endsWith("|" + TRIAGE_LABEL);
            String label = orTriage ? f[1].substring(0, f[1].indexOf('|')) : f[1];
            Optional<IncidentCategory> expected = TRIAGE_LABEL.equals(label)
                    ? Optional.empty() : Optional.of(IncidentCategory.valueOf(label));
            tickets.add(new LabelledTicket(f[0], expected, f[2], f[3], orTriage));
        }
        return tickets;
    }

    /** Corpus documents parsed exactly as {@code IntelligenceConfig} does (title and description joined). */
    static List<String> corpusDocuments() {
        List<String> documents = new ArrayList<>();
        for (String line : dataLines(CORPUS)) {
            String[] parts = line.split(",", 3);
            if (parts.length >= 2) {
                documents.add(parts.length == 3 ? parts[1] + " " + parts[2] : parts[1]);
            }
        }
        return documents;
    }

    static List<LabelledPair> labelledPairs() {
        List<LabelledPair> pairs = new ArrayList<>();
        for (String line : dataLines(LABELLED_PAIRS)) {
            String[] f = line.split("\t", -1);
            pairs.add(new LabelledPair(f[0], f[1], "1".equals(f[2]), f[3], f[4]));
        }
        return pairs;
    }

    /** Non-empty lines after the header; lines starting with '#' are comments. */
    private static List<String> dataLines(String resource) {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = open(resource)) {
            reader.readLine(); // header, or the first comment line
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.strip();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#") && !trimmed.startsWith("id\t")) {
                    lines.add(trimmed);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + resource, e);
        }
        return lines;
    }

    private static BufferedReader open(String resource) {
        InputStream in = BenchmarkData.class.getResourceAsStream(resource);
        if (in == null) {
            throw new IllegalStateException("Missing classpath resource " + resource);
        }
        return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    private static String unquote(String text) {
        String t = text.strip();
        return (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) ? t.substring(1, t.length() - 1) : t;
    }
}
