package com.iitsm.benchmark;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Writes a benchmark report to {@code target/benchmarks} and echoes it to the test log. */
final class BenchmarkReport {

    private static final Logger log = LoggerFactory.getLogger(BenchmarkReport.class);
    private static final Path REPORT_DIR = Path.of("target", "benchmarks");

    private BenchmarkReport() { }

    static Path write(String fileName, List<String> lines) throws IOException {
        Files.createDirectories(REPORT_DIR);
        Path file = REPORT_DIR.resolve(fileName);
        Files.write(file, lines, StandardCharsets.UTF_8);
        log.info("Benchmark report {}:{}{}", file.toAbsolutePath(), System.lineSeparator(),
                String.join(System.lineSeparator(), lines));
        return file;
    }
}
