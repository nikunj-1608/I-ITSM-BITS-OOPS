package com.iitsm.domain.strategy.assignment;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.Technician;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The characterization suite of the weighted composite after the service-experience factor (ADR-044, Phase L4).
 *
 * <p>Two pins. (1) With the factor off (weight 0) the composite must reproduce every recorded line of the pre-L4
 * golden file {@code strategy-characterization.txt}, bit for bit: switching the feature off is switching production
 * assignment back to what it always did. (2) With the factor on at the production weight 0.10, the choice, the exact score
 * and the rationale for the whole grid are recorded in {@code service-experience-characterization.txt} from a fixed
 * deterministic experience (a technician's count is the low three bits of the hash of their id), so any later change of the
 * factor, of the weight scaling or of the tie-breaking shows up as a diff. Re-record only for an approved change, with the
 * environment variable {@code CHARACTERIZATION_RECORD=true}, and review the diff.
 */
class ServiceExperienceCharacterizationTest {

    private static final Path PRE_L4_GOLDEN = Path.of("src/test/resources/assignment/strategy-characterization.txt");
    private static final Path L4_GOLDEN = Path.of("src/test/resources/assignment/service-experience-characterization.txt");
    private static final double PRODUCTION_WEIGHT = 0.10;
    private static final int FULL_SCORE_TICKETS = 5;
    private static final int COUNT_MASK = 7;

    /** The same counts every time, in every JVM: 0 to 7 resolved tickets per technician, whatever the service. */
    private static final ServiceExperienceSource DETERMINISTIC = (serviceId, ids) -> {
        Map<String, Integer> counts = new HashMap<>();
        for (String id : ids) {
            counts.put(id, id.hashCode() & COUNT_MASK);
        }
        return counts;
    };

    @Test
    @DisplayName("with the factor off the composite makes exactly the recorded pre-L4 choices, scores and rationales")
    void factorOffIsTheRecordedComposite() throws IOException {
        ServiceExperienceSource mustNotBeAsked = (service, ids) -> {
            throw new AssertionError("a zero weight must never ask for experience");
        };
        List<String> actual = observe(WeightedCompositeStrategy.withServiceExperience(mustNotBeAsked, 0.0, FULL_SCORE_TICKETS));
        List<String> expected = Files.readAllLines(PRE_L4_GOLDEN, StandardCharsets.UTF_8).stream()
                .filter(line -> line.startsWith("WEIGHTED_COMPOSITE|") && !line.contains("|EMPTY|") && !line.contains("|NULL|")).toList();

        assertThat(expected).hasSizeGreaterThan(80);
        assertThat(actual).containsExactlyElementsOf(expected);
    }

    @Test
    @DisplayName("with the factor on at 0.10 the choices, scores and rationales are the recorded ones")
    void factorOnIsTheRecordedL4Composite() throws IOException {
        List<String> actual = observe(WeightedCompositeStrategy.withServiceExperience(DETERMINISTIC, PRODUCTION_WEIGHT, FULL_SCORE_TICKETS));
        if (Boolean.getBoolean("characterization.record") || "true".equals(System.getenv("CHARACTERIZATION_RECORD"))) {
            Files.createDirectories(L4_GOLDEN.getParent());
            Files.write(L4_GOLDEN, actual, StandardCharsets.UTF_8);
        }
        List<String> expected = Files.readAllLines(L4_GOLDEN, StandardCharsets.UTF_8);

        assertThat(actual).containsExactlyElementsOf(expected);
        assertThat(expected).hasSizeGreaterThan(80);
    }

    @Test
    @DisplayName("the recorded L4 behaviour differs from the pre-L4 one exactly where history matters, and nowhere else")
    void historyChangesSomeChoices() throws IOException {
        List<String> before = Files.readAllLines(PRE_L4_GOLDEN, StandardCharsets.UTF_8).stream()
                .filter(line -> line.startsWith("WEIGHTED_COMPOSITE|") && !line.contains("|EMPTY|") && !line.contains("|NULL|")).toList();
        List<String> after = Files.readAllLines(L4_GOLDEN, StandardCharsets.UTF_8);

        assertThat(after).hasSameSizeAs(before);
        long changedChoices = 0;
        for (int i = 0; i < before.size(); i++) {
            String key = before.get(i).substring(0, before.get(i).indexOf('|', before.get(i).indexOf('|', before.get(i).indexOf('|') + 1) + 1));
            assertThat(after.get(i)).startsWith(key);
            String[] b = before.get(i).split("\\|");
            String[] a = after.get(i).split("\\|");
            if (b.length > 3 && a.length > 3 && !b[3].equals(a[3])) {
                changedChoices++;
            }
            if (key.contains("|RAW_")) {
                assertThat(a[3]).as("a ticket without a specific service is unaffected: %s", key).isEqualTo(b[3]);
            }
        }
        assertThat(changedChoices).as("history must change some choices, or the factor does nothing").isPositive();
    }

    private static List<String> observe(AssignmentStrategy strategy) {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, Incident> ticket : AssignmentGrid.incidents().entrySet()) {
            for (Map.Entry<String, List<Technician>> list : AssignmentGrid.candidateLists().entrySet()) {
                String key = strategy.getStrategyName() + "|" + ticket.getKey() + "|" + list.getKey();
                AssignmentRecommendation rec = strategy.recommend(ticket.getValue(), list.getValue());
                lines.add(key + "|" + rec.technician().getId() + "|" + rec.score() + "|" + rec.strategyName() + "|" + rec.rationale());
            }
        }
        return lines;
    }

}
