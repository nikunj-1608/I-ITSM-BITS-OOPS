package com.iitsm.infrastructure.persistence.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.Priority;
import com.iitsm.domain.repository.ResolvedCaseRepository;
import com.iitsm.domain.repository.UserRepository;
import com.iitsm.domain.strategy.suggestion.SuggestionCandidate;
import com.iitsm.domain.strategy.suggestion.SuggestionSourceType;
import com.iitsm.infrastructure.persistence.CompanySeeder;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * ADR-040: the resolved-case projection on H2. Rows are written with SQL and resolved in 2099, so they are the newest
 * of the shared test database whatever other classes left behind; they are deleted afterwards.
 */
@SpringBootTest
@ActiveProfiles("test")
class ResolvedCaseRepositoryAdapterTest {

    private static final String PREFIX = "SUGT-";
    private static final String REPORTER = "EMP-DEMO-01";
    private static final Instant BASE = Instant.parse("2099-01-01T00:00:00Z");

    @Autowired private ResolvedCaseRepository repository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private CompanySeeder companySeeder;

    @BeforeEach
    void setUp() {
        if (userRepository.count() == 0) {
            companySeeder.seedCompanyData();
        }
        cleanUp();
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM resolutions WHERE incident_id LIKE ?", PREFIX + "%");
        jdbc.update("DELETE FROM incidents WHERE id LIKE ?", PREFIX + "%");
    }

    private void insert(String suffix, IncidentCategory category, String status, int minutesAfterBase, boolean withResolution) {
        String id = PREFIX + suffix;
        jdbc.update("INSERT INTO incidents (id, title, description, reporter_id, category, priority, status, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                id, "Title " + suffix, "Description of " + suffix, REPORTER, category.name(), Priority.P2.name(), status,
                Timestamp.from(BASE));
        if (withResolution) {
            jdbc.update("INSERT INTO resolutions (incident_id, root_cause, steps_taken, preventive_action, confirmed_category, "
                            + "resolved_by, resolved_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    id, "Root " + suffix, "Steps " + suffix, "Prevent " + suffix, category.name(), "TECH-NET-1",
                    Timestamp.from(BASE.plusSeconds(60L * minutesAfterBase)));
        }
    }

    private List<String> ours(List<SuggestionCandidate> candidates) {
        return candidates.stream().map(SuggestionCandidate::sourceId).filter(id -> id.startsWith(PREFIX)).toList();
    }

    @Test
    @DisplayName("Only RESOLVED, VERIFIED and CLOSED tickets with a resolution are read; a rejected fix is not")
    void confirmedResolutionsOnly() {
        insert("R", IncidentCategory.STORAGE, "RESOLVED", 1, true);
        insert("V", IncidentCategory.STORAGE, "VERIFIED", 2, true);
        insert("C", IncidentCategory.STORAGE, "CLOSED", 3, true);
        insert("REOPENED", IncidentCategory.STORAGE, "IN_PROGRESS", 4, true);
        insert("NORES", IncidentCategory.STORAGE, "CLOSED", 5, false);

        assertThat(ours(repository.findNewestResolvedCases(IncidentCategory.STORAGE, 50)))
                .containsExactly(PREFIX + "C", PREFIX + "V", PREFIX + "R");
    }

    @Test
    @DisplayName("The window is newest first by resolution time and cut to the limit")
    void newestFirstAndLimited() {
        insert("OLD", IncidentCategory.STORAGE, "CLOSED", 1, true);
        insert("MID", IncidentCategory.STORAGE, "CLOSED", 2, true);
        insert("NEW", IncidentCategory.STORAGE, "CLOSED", 3, true);

        assertThat(ours(repository.findNewestResolvedCases(IncidentCategory.STORAGE, 2)))
                .containsExactly(PREFIX + "NEW", PREFIX + "MID");
        assertThat(repository.findNewestResolvedCases(IncidentCategory.STORAGE, 0)).isEmpty();
    }

    @Test
    @DisplayName("A category keeps that category; null reads every category")
    void categoryFilter() {
        insert("S", IncidentCategory.STORAGE, "CLOSED", 1, true);
        insert("D", IncidentCategory.DATABASE, "CLOSED", 2, true);

        assertThat(ours(repository.findNewestResolvedCases(IncidentCategory.STORAGE, 50))).containsExactly(PREFIX + "S");
        assertThat(ours(repository.findNewestResolvedCases(null, 50))).containsExactly(PREFIX + "D", PREFIX + "S");
    }

    @Test
    @DisplayName("A row carries the ticket's technical content and its priority, as a resolved-ticket candidate")
    void projectionContent() {
        insert("X", IncidentCategory.STORAGE, "RESOLVED", 1, true);

        SuggestionCandidate candidate = repository.findNewestResolvedCases(IncidentCategory.STORAGE, 1).get(0);

        assertThat(candidate.sourceType()).isEqualTo(SuggestionSourceType.RESOLVED_TICKET);
        assertThat(candidate.sourceId()).isEqualTo(PREFIX + "X");
        assertThat(candidate.category()).isEqualTo(IncidentCategory.STORAGE);
        assertThat(candidate.priority()).isEqualTo(Priority.P2);
        assertThat(candidate.title()).isEqualTo("Title X");
        assertThat(candidate.problemText()).isEqualTo("Description of X");
        assertThat(candidate.rootCause()).isEqualTo("Root X");
        assertThat(candidate.stepsTaken()).isEqualTo("Steps X");
        assertThat(candidate.preventiveAction()).isEqualTo("Prevent X");
    }
}
