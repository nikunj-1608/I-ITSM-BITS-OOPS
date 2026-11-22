package com.iitsm.domain.strategy.assignment;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitsm.domain.exception.TechnicianUnavailableException;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.Technician;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Characterization of the five assignment strategies as they behaved before the explainable ranking (ADR-044).
 *
 * <p>The golden file {@code assignment/strategy-characterization.txt} was recorded from the code before the refactor
 * (run once with the environment variable {@code CHARACTERIZATION_RECORD=true}); every line holds the strategy, the ticket, the candidate
 * list, the chosen technician, the exact score and the rationale. The refactor must reproduce every line: the same
 * choice, the same score to the last bit, the same text. Round robin is recorded as a call sequence on one instance,
 * because its answer depends on how often it was asked before.
 */
class AssignmentStrategyCharacterizationTest {

    private static final Path GOLDEN = Path.of("src/test/resources/assignment/strategy-characterization.txt");

    @Test
    @DisplayName("Every strategy makes the recorded choice with the recorded score and rationale")
    void strategiesMatchTheRecordedBehaviour() throws IOException {
        List<String> actual = observe();
        if (Boolean.getBoolean("characterization.record") || "true".equals(System.getenv("CHARACTERIZATION_RECORD"))) {
            Files.createDirectories(GOLDEN.getParent());
            Files.write(GOLDEN, actual, StandardCharsets.UTF_8);
        }
        List<String> expected = Files.readAllLines(GOLDEN, StandardCharsets.UTF_8);
        assertThat(actual).containsExactlyElementsOf(expected);
    }

    @Test
    @DisplayName("The golden file covers every strategy, priority, tie, the one-element and the empty list")
    void goldenFileCoversTheGrid() throws IOException {
        List<String> expected = Files.readAllLines(GOLDEN, StandardCharsets.UTF_8);
        for (String name : List.of("WEIGHTED_COMPOSITE", "SKILL_BASED", "LOAD_BASED", "EXPERIENCE_BASED", "ROUND_ROBIN")) {
            assertThat(expected).anyMatch(line -> line.startsWith(name + "|"));
        }
        assertThat(expected).anyMatch(line -> line.contains("|NET_P1|TIE|"));
        assertThat(expected).anyMatch(line -> line.contains("|RAW_VAGUE|ONE|"));
        assertThat(expected).anyMatch(line -> line.contains("|EMPTY|") && line.contains("TechnicianUnavailableException"));
        assertThat(expected).hasSizeGreaterThan(400);
    }

    static List<String> observe() {
        List<String> lines = new ArrayList<>();
        List<AssignmentStrategy> stateless = List.of(new WeightedCompositeStrategy(), new SkillBasedStrategy(),
                new LoadBasedStrategy(), new ExperienceBasedStrategy());
        for (AssignmentStrategy strategy : stateless) {
            observeGrid(strategy, lines);
        }
        observeRoundRobin(lines);
        return lines;
    }

    private static void observeGrid(AssignmentStrategy strategy, List<String> lines) {
        for (Map.Entry<String, Incident> ticket : AssignmentGrid.incidents().entrySet()) {
            for (Map.Entry<String, List<Technician>> list : AssignmentGrid.candidateLists().entrySet()) {
                String key = strategy.getStrategyName() + "|" + ticket.getKey() + "|" + list.getKey();
                lines.add(key + "|" + describe(() -> strategy.recommend(ticket.getValue(), list.getValue())));
            }
            String key = strategy.getStrategyName() + "|" + ticket.getKey();
            lines.add(key + "|EMPTY|" + describe(() -> strategy.recommend(ticket.getValue(), List.of())));
            lines.add(key + "|NULL|" + describe(() -> strategy.recommend(ticket.getValue(), null)));
        }
    }

    private static void observeRoundRobin(List<String> lines) {
        RoundRobinStrategy strategy = new RoundRobinStrategy();
        Incident ticket = AssignmentGrid.incidents().get("NET_P3");
        List<String> sequence = List.of("ALL", "ALL", "ALL", "ONE", "TIE", "TIE", "MIXED", "ALL_REVERSED", "MIXED",
                "ALL", "EXPERIENCE_TIE", "ALL", "MIXED", "ONE", "ALL");
        for (int call = 0; call < sequence.size(); call++) {
            List<Technician> list = AssignmentGrid.candidateLists().get(sequence.get(call));
            lines.add("ROUND_ROBIN|call" + call + "|" + sequence.get(call) + "|"
                    + describe(() -> strategy.recommend(ticket, list)));
        }
        lines.add("ROUND_ROBIN|after|EMPTY|" + describe(() -> strategy.recommend(ticket, List.of())));
    }

    private static String describe(Supplier<AssignmentRecommendation> call) {
        try {
            AssignmentRecommendation rec = call.get();
            return rec.technician().getId() + "|" + rec.score() + "|" + rec.strategyName() + "|" + rec.rationale();
        } catch (TechnicianUnavailableException ex) {
            return ex.getClass().getSimpleName() + "|" + ex.getMessage();
        }
    }
}
