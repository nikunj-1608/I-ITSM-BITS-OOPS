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
 * V11 (ADR-044) creates {@code assignment_decisions}. Each scenario migrates an empty database to the newest version and
 * checks the table's rules with SQL; the same scenarios run on H2 and on a real PostgreSQL (two subclasses).
 */
abstract class AssignmentDecisionsMigrationTestBase {

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 11, 8, 10, 0, 0, 0, ZoneOffset.UTC);
    private static final String INSERT = "INSERT INTO assignment_decisions (id, incident_id, pool_kind, strategy_name, "
            + "chosen_technician_id, actor_id, related_incident_id, candidate_count, candidates_json, decided_at) "
            + "VALUES (?, ?, ?, ?, 'U-TECH', 'SYSTEM', NULL, ?, ?, ?)";
    private static final String RANKING = "{\"format\":1,\"candidates\":[{\"technicianId\":\"U-TECH\",\"total\":0.5,"
            + "\"rank\":1,\"chosen\":true,\"factors\":[]}]}";

    private Flyway flyway;

    abstract DataSource newDatabase() throws Exception;

    private JdbcTemplate migrated() throws Exception {
        DataSource ds = newDatabase();
        flyway = Flyway.configure().dataSource(ds).locations("classpath:db/migration").load();
        flyway.migrate();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        LegacyTicketMigrationTestBase.seedUsers(jdbc);
        LegacyTicketMigrationTestBase.incident(jdbc, "FIN-2026-000001", "U-ALICE", NOW);
        return jdbc;
    }

    private static void decision(JdbcTemplate jdbc, String id, String incident, String pool, int count) {
        jdbc.update(INSERT, id, incident, pool, "WEIGHTED_COMPOSITE", count, RANKING, NOW);
    }

    @Test
    @DisplayName("V11 is applied after V10 with V7 and V8 left free, and the table takes a decision with its JSON ranking")
    void appliesAndStores() throws Exception {
        JdbcTemplate jdbc = migrated();

        List<String> applied = Arrays.stream(flyway.info().applied())
                .filter(m -> m.getState().isApplied() && !m.getState().isFailed())
                .map(m -> m.getVersion().getVersion()).toList();
        assertThat(applied).contains("10", "11").doesNotContain("7", "8");
        decision(jdbc, "ASG-1", "FIN-2026-000001", "OWNING_TEAM", 1);
        assertThat(jdbc.queryForObject("SELECT candidates_json FROM assignment_decisions", String.class)).isEqualTo(RANKING);
    }

    @Test
    @DisplayName("Only the five pool kinds and a non-negative candidate count are accepted")
    void checksPoolKindAndCount() throws Exception {
        JdbcTemplate jdbc = migrated();

        assertThatThrownBy(() -> decision(jdbc, "ASG-X", "FIN-2026-000001", "SOMEWHERE", 1))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> decision(jdbc, "ASG-Y", "FIN-2026-000001", "MANUAL", -1))
                .isInstanceOf(DataIntegrityViolationException.class);
        for (String pool : List.of("OWNING_TEAM", "SKILLED_OVERFLOW", "MANUAL", "INHERITED_FROM_PARENT",
                "REQUEUED_TO_SAME_TECHNICIAN")) {
            decision(jdbc, "ASG-" + pool, "FIN-2026-000001", pool, 0);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM assignment_decisions", Integer.class)).isEqualTo(5);
    }

    @Test
    @DisplayName("The ticket must exist; the technician and the actor are plain text (SYSTEM is a valid actor)")
    void references() throws Exception {
        JdbcTemplate jdbc = migrated();

        assertThatThrownBy(() -> decision(jdbc, "ASG-X", "FIN-2099-999999", "MANUAL", 0))
                .isInstanceOf(DataIntegrityViolationException.class);
        decision(jdbc, "ASG-1", "FIN-2026-000001", "MANUAL", 0);
        assertThat(jdbc.queryForObject("SELECT actor_id FROM assignment_decisions", String.class)).isEqualTo("SYSTEM");
    }

    @Test
    @DisplayName("Renaming or deleting the ticket carries its decisions along (cascade)")
    void cascades() throws Exception {
        JdbcTemplate jdbc = migrated();
        decision(jdbc, "ASG-1", "FIN-2026-000001", "OWNING_TEAM", 1);

        jdbc.update("UPDATE incidents SET id = 'FIN-2026-000099' WHERE id = 'FIN-2026-000001'");
        assertThat(jdbc.queryForObject("SELECT incident_id FROM assignment_decisions", String.class))
                .isEqualTo("FIN-2026-000099");
        jdbc.update("DELETE FROM incidents WHERE id = 'FIN-2026-000099'");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM assignment_decisions", Integer.class)).isZero();
    }
}
