package com.iitsm.infrastructure.persistence.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.time.ZoneOffset;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V9 (ADR-041) creates {@code suggestion_votes}. Each scenario migrates an empty database to the newest version and
 * checks the table's rules directly with SQL. The same scenarios run on H2 and on a real PostgreSQL (two subclasses).
 */
abstract class SuggestionVotesMigrationTestBase {

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 11, 7, 10, 0, 0, 0, ZoneOffset.UTC);
    private static final String INSERT = "INSERT INTO suggestion_votes (voter_id, source_type, source_id, "
            + "target_incident_id, worked, voted_at) VALUES (?, ?, ?, ?, ?, ?)";

    abstract DataSource newDatabase() throws Exception;

    private Flyway flyway;

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

    private static void vote(JdbcTemplate jdbc, String voter, String type, String source, String target, boolean worked) {
        jdbc.update(INSERT, voter, type, source, target, worked, NOW);
    }

    @Test
    @DisplayName("V9 is applied after V6 with V7 and V8 left free, and the table takes a vote")
    void appliesAndStores() throws Exception {
        JdbcTemplate jdbc = migrated();

        List<String> applied = Arrays.stream(flyway.info().applied())
                .filter(m -> m.getState().isApplied() && !m.getState().isFailed())
                .map(m -> m.getVersion().getVersion()).toList();
        assertThat(applied).contains("6", "9").doesNotContain("7", "8");
        vote(jdbc, "U-TECH", "RESOLVED_TICKET", "FIN-2026-000001", "FIN-2026-000002", true);
        assertThat(jdbc.queryForObject("SELECT worked FROM suggestion_votes", Boolean.class)).isTrue();
    }

    @Test
    @DisplayName("One vote per voter, source and ticket; another ticket, voter or source type is a new row")
    void uniqueKey() throws Exception {
        JdbcTemplate jdbc = migrated();
        vote(jdbc, "U-TECH", "RESOLVED_TICKET", "FIN-2026-000001", "FIN-2026-000002", true);

        assertThatThrownBy(() -> vote(jdbc, "U-TECH", "RESOLVED_TICKET", "FIN-2026-000001", "FIN-2026-000002", false))
                .isInstanceOf(DataIntegrityViolationException.class);
        vote(jdbc, "U-BOB", "RESOLVED_TICKET", "FIN-2026-000001", "FIN-2026-000002", false);
        vote(jdbc, "U-TECH", "KB_ARTICLE", "FIN-2026-000001", "FIN-2026-000002", false);
        vote(jdbc, "U-TECH", "RESOLVED_TICKET", "FIN-2026-000002", "FIN-2026-000001", false);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM suggestion_votes", Integer.class)).isEqualTo(4);
    }

    @Test
    @DisplayName("The voter and the target ticket must exist; the source type must be a known one")
    void references() throws Exception {
        JdbcTemplate jdbc = migrated();

        assertThatThrownBy(() -> vote(jdbc, "U-NOBODY", "RESOLVED_TICKET", "X", "FIN-2026-000002", true))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> vote(jdbc, "U-TECH", "RESOLVED_TICKET", "X", "FIN-2099-999999", true))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> vote(jdbc, "U-TECH", "SOMETHING", "X", "FIN-2026-000002", true))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Deleting or renaming the target ticket carries its votes along (cascade)")
    void cascades() throws Exception {
        JdbcTemplate jdbc = migrated();
        vote(jdbc, "U-TECH", "RESOLVED_TICKET", "FIN-2026-000001", "FIN-2026-000002", true);

        jdbc.update("UPDATE incidents SET id = 'FIN-2026-000099' WHERE id = 'FIN-2026-000002'");
        assertThat(jdbc.queryForObject("SELECT target_incident_id FROM suggestion_votes", String.class))
                .isEqualTo("FIN-2026-000099");
        jdbc.update("DELETE FROM incidents WHERE id = 'FIN-2026-000099'");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM suggestion_votes", Integer.class)).isZero();
    }
}
