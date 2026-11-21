package com.iitsm.domain.simulation.assignment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Source-level guard for the offline comparison (ADR-044, Phase L3): it must stay deterministic and pure. The
 * classes of the package may not read the wall clock, use an unseeded random source or reach into the application,
 * infrastructure, web or persistence layers.
 */
class SimulationSourceContractTest {

    private static final Path PACKAGE = Path.of("src/main/java/com/iitsm/domain/simulation/assignment");

    private static List<Path> sources() throws IOException {
        try (Stream<Path> files = Files.list(PACKAGE)) {
            return files.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    @Test
    @DisplayName("no wall clock, no Math.random, no unseeded Random, no thread: the same seed must always give the same result")
    void noNondeterminism() throws IOException {
        assertThat(sources()).hasSizeGreaterThanOrEqualTo(15);
        for (Path source : sources()) {
            String text = Files.readString(source, StandardCharsets.UTF_8);
            assertThat(text).as(source.getFileName().toString())
                    .doesNotContain("Instant.now(", "LocalDateTime.now(", "Clock", "System.currentTimeMillis", "System.nanoTime",
                            "Math.random", "new Random(", "ThreadLocalRandom", "new Thread", "parallelStream", "Executor");
        }
    }

    @Test
    @DisplayName("the engine touches no repository, event publisher, notification, Spring or persistence class")
    void noOutsideWorld() throws IOException {
        for (Path source : sources()) {
            String text = Files.readString(source, StandardCharsets.UTF_8);
            assertThat(text).as(source.getFileName().toString())
                    .doesNotContain("com.iitsm.domain.repository", "com.iitsm.domain.event", "com.iitsm.application",
                            "com.iitsm.infrastructure", "com.iitsm.web", "com.iitsm.simulation", "org.springframework",
                            "jakarta.", "setActiveStrategy", "AdjustableClock");
        }
    }

    @Test
    @DisplayName("the only randomness is a seeded SplittableRandom in the experiment")
    void onlySeededRandomness() throws IOException {
        long usages = 0;
        for (Path source : sources()) {
            if (Files.readString(source, StandardCharsets.UTF_8).contains("new SplittableRandom(")) {
                usages++;
                assertThat(source.getFileName().toString()).isEqualTo("StrategyComparison.java");
            }
        }
        assertThat(usages).isEqualTo(1);
    }
}
