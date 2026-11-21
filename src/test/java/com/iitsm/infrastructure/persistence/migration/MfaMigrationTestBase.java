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
 * V13 (ADR-048) adds the two-step verification columns to {@code users} and creates {@code mfa_recovery_codes}. Each
 * scenario migrates an empty database to the newest version and checks the rules directly with SQL. The same scenarios
 * run on H2 and on a real PostgreSQL (two subclasses).
 */
abstract class MfaMigrationTestBase {

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 11, 20, 10, 0, 0, 0, ZoneOffset.UTC);

    abstract DataSource newDatabase() throws Exception;

    private Flyway flyway;

    private JdbcTemplate migrated() throws Exception {
        DataSource ds = newDatabase();
        flyway = Flyway.configure().dataSource(ds).locations("classpath:db/migration").load();
        flyway.migrate();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        LegacyTicketMigrationTestBase.seedUsers(jdbc);
        return jdbc;
    }

    private static void code(JdbcTemplate jdbc, String user, String hash) {
        jdbc.update("INSERT INTO mfa_recovery_codes (user_id, code_hash, created_at) VALUES (?, ?, ?)", user, hash, NOW);
    }

    @Test
    @DisplayName("V13 is applied with V7 and V8 left free")
    void applies() throws Exception {
        migrated();

        List<String> applied = Arrays.stream(flyway.info().applied())
                .filter(m -> m.getState().isApplied() && !m.getState().isFailed())
                .map(m -> m.getVersion().getVersion()).toList();
        assertThat(applied).contains("6", "9", "10", "11", "12", "13").doesNotContain("7", "8");
    }

    @Test
    @DisplayName("existing users get two-step verification off, with no secret and no last step")
    void defaultsForExistingUsers() throws Exception {
        JdbcTemplate jdbc = migrated();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE mfa_enabled = FALSE AND mfa_secret IS NULL "
                + "AND mfa_last_step IS NULL", Integer.class)).isEqualTo(4);
    }

    @Test
    @DisplayName("the flag is mandatory (a row without it still gets FALSE), the secret and the step are optional and keep their values")
    void userColumns() throws Exception {
        JdbcTemplate jdbc = migrated();

        jdbc.update("UPDATE users SET mfa_enabled = TRUE, mfa_secret = ?, mfa_last_step = ? WHERE id = 'U-TECH'",
                "x".repeat(255), 59_000_000L);

        assertThat(jdbc.queryForObject("SELECT mfa_last_step FROM users WHERE id = 'U-TECH'", Long.class))
                .isEqualTo(59_000_000L);
        assertThat(jdbc.queryForObject("SELECT LENGTH(mfa_secret) FROM users WHERE id = 'U-TECH'", Integer.class))
                .isEqualTo(255);
        assertThatThrownBy(() -> jdbc.update("UPDATE users SET mfa_enabled = NULL WHERE id = 'U-TECH'"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a recovery code needs an owner that exists, a hash and a creation time; a hash may repeat across users")
    void recoveryCodeTable() throws Exception {
        JdbcTemplate jdbc = migrated();

        code(jdbc, "U-TECH", "$2a$10$" + "a".repeat(53));
        assertThatThrownBy(() -> code(jdbc, "U-NOBODY", "hash"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO mfa_recovery_codes (user_id, code_hash, created_at) "
                + "VALUES ('U-TECH', NULL, ?)", NOW)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO mfa_recovery_codes (user_id, code_hash, created_at) "
                + "VALUES ('U-TECH', 'h', NULL)")).isInstanceOf(DataIntegrityViolationException.class);
        code(jdbc, "U-BOB", "same-hash");
        code(jdbc, "U-ALICE", "same-hash");
        assertThat(jdbc.queryForObject("SELECT used_at FROM mfa_recovery_codes WHERE user_id = 'U-TECH'", Object.class))
                .isNull();
    }

    @Test
    @DisplayName("deleting a user deletes the user's recovery codes (cascade)")
    void cascades() throws Exception {
        JdbcTemplate jdbc = migrated();
        code(jdbc, "U-TECH", "h1");
        code(jdbc, "U-TECH", "h2");
        code(jdbc, "U-BOB", "h3");

        jdbc.update("DELETE FROM users WHERE id = 'U-TECH'");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mfa_recovery_codes", Integer.class)).isEqualTo(1);
    }
}
