package com.iitsm.infrastructure.persistence.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V10 (ADR-042) creates {@code knowledge_articles}. Each scenario migrates an empty database to the newest version and
 * checks the table's rules with SQL; the same scenarios run on H2 and on a real PostgreSQL (two subclasses).
 */
abstract class KnowledgeArticlesMigrationTestBase {

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 11, 7, 10, 0, 0, 0, ZoneOffset.UTC);
    private static final String INSERT = "INSERT INTO knowledge_articles (id, title, category, problem, root_cause, "
            + "steps_taken, preventive_action, source_incident_id, created_by, created_at) "
            + "VALUES (?, 'Renew the VPN certificate', 'NETWORK', 'p', 'r', 's', 'a', ?, ?, ?)";

    private Flyway flyway;

    abstract DataSource newDatabase() throws Exception;

    private JdbcTemplate migrated() throws Exception {
        DataSource ds = newDatabase();
        flyway = Flyway.configure().dataSource(ds).locations("classpath:db/migration").load();
        flyway.migrate();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        LegacyTicketMigrationTestBase.seedUsers(jdbc);
        LegacyTicketMigrationTestBase.incident(jdbc, "FIN-2026-000001", "U-ALICE", NOW);
        LegacyTicketMigrationTestBase.incident(jdbc, "FIN-2026-000002", "U-ALICE", NOW);
        return jdbc;
    }

    private static void article(JdbcTemplate jdbc, String id, String source, String createdBy) {
        jdbc.update(INSERT, id, source, createdBy, NOW);
    }

    @Test
    @DisplayName("V10 is applied after V9 with V7 and V8 left free, and the table takes an article")
    void appliesAndStores() throws Exception {
        JdbcTemplate jdbc = migrated();

        List<String> applied = Arrays.stream(flyway.info().applied())
                .filter(m -> m.getState().isApplied() && !m.getState().isFailed())
                .map(m -> m.getVersion().getVersion()).toList();
        assertThat(applied).contains("9", "10").doesNotContain("7", "8");
        article(jdbc, "KB-FIN-2026-000001", "FIN-2026-000001", "U-TECH");
        assertThat(jdbc.queryForObject("SELECT title FROM knowledge_articles", String.class)).isEqualTo("Renew the VPN certificate");
    }

    @Test
    @DisplayName("A ticket is promoted once: a second article with the same source is refused")
    void oneArticlePerTicket() throws Exception {
        JdbcTemplate jdbc = migrated();
        article(jdbc, "KB-FIN-2026-000001", "FIN-2026-000001", "U-TECH");

        assertThatThrownBy(() -> article(jdbc, "KB-OTHER", "FIN-2026-000001", "U-TECH"))
                .isInstanceOf(DataIntegrityViolationException.class);
        article(jdbc, "KB-FIN-2026-000002", "FIN-2026-000002", "U-TECH");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_articles", Integer.class)).isEqualTo(2);
    }

    @Test
    @DisplayName("The source ticket and the promoting user must exist; there is no column for reporter data")
    void referencesAndNoPersonalColumns() throws Exception {
        JdbcTemplate jdbc = migrated();

        assertThatThrownBy(() -> article(jdbc, "KB-X", "FIN-2099-999999", "U-TECH"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> article(jdbc, "KB-Y", "FIN-2026-000001", "U-NOBODY"))
                .isInstanceOf(DataIntegrityViolationException.class);
        List<String> columns = jdbc.queryForList("SELECT LOWER(column_name) FROM information_schema.columns "
                + "WHERE LOWER(table_name) = 'knowledge_articles'", String.class);
        assertThat(columns).doesNotContain("reporter_id", "email", "department", "reporter_name");
    }

    @Test
    @DisplayName("Renaming or deleting the source ticket carries its article along (cascade)")
    void cascades() throws Exception {
        JdbcTemplate jdbc = migrated();
        article(jdbc, "KB-FIN-2026-000002", "FIN-2026-000002", "U-TECH");

        jdbc.update("UPDATE incidents SET id = 'FIN-2026-000099' WHERE id = 'FIN-2026-000002'");
        assertThat(jdbc.queryForObject("SELECT source_incident_id FROM knowledge_articles", String.class))
                .isEqualTo("FIN-2026-000099");
        jdbc.update("DELETE FROM incidents WHERE id = 'FIN-2026-000099'");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_articles", Integer.class)).isZero();
    }
}
