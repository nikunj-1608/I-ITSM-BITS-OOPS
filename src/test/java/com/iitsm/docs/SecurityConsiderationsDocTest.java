package com.iitsm.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Authentication hardening, Phase S1: the security-considerations document for the course evaluators exists and has
 * one section for each security item the project specification lists, plus the threat table and the known gaps.
 * Nothing here starts an application.
 */
class SecurityConsiderationsDocTest {

    private static final Path DOC = Path.of("docs", "security-considerations.md");

    private static final List<String> SPEC_SECTIONS = List.of(
            "## Authentication",
            "## Role-based authorization",
            "## Password protection",
            "## Input validation",
            "## Secure API access",
            "## Protection against unauthorized operations");

    private static final List<String> SUPPORTING_SECTIONS = List.of(
            "## Threat table",
            "## Configuration reference",
            "## Known gaps",
            "## Implemented hardening");

    private static List<String> headings() throws IOException {
        return Files.readAllLines(DOC, StandardCharsets.UTF_8).stream()
                .map(String::strip)
                .filter(line -> line.startsWith("## "))
                .toList();
    }

    @Test
    @DisplayName("S1: the document exists")
    void documentExists() {
        assertThat(DOC).isRegularFile();
    }

    @Test
    @DisplayName("S1: one section per security item of the project specification")
    void hasEverySpecificationSection() throws IOException {
        assertThat(headings()).containsAll(SPEC_SECTIONS);
    }

    @Test
    @DisplayName("S1: threat table, configuration reference, implemented hardening and known gaps are present")
    void hasSupportingSections() throws IOException {
        assertThat(headings()).containsAll(SUPPORTING_SECTIONS);
    }
}
