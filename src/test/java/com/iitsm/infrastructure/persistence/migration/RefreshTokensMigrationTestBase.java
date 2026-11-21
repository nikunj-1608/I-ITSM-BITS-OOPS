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
 * V12 (ADR-047) creates {@code refresh_tokens}. Each scenario migrates an empty database to the newest version and checks
 * the table's rules directly with SQL. The same scenarios run on H2 and on a real PostgreSQL (two subclasses).
 */
abstract class RefreshTokensMigrationTestBase {

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 11, 20, 10, 0, 0, 0, ZoneOffset.UTC);
    private static final String INSERT = "INSERT INTO refresh_tokens (user_id, family_id, token_hash, token_version, "
            + "issued_at, expires_at) VALUES (?, ?, ?, ?, ?, ?)";

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

    private static void token(JdbcTemplate jdbc, String user, String family, String hash) {
        jdbc.update(INSERT, user, family, hash, 1, NOW, NOW.plusHours(8));
    }

    private static String hash(char c) {
        return String.valueOf(c).repeat(64);
    }

    @Test
    @DisplayName("V12 is applied with V7 and V8 left free, and the table takes a token")
    void appliesAndStores() throws Exception {
        JdbcTemplate jdbc = migrated();

        List<String> applied = Arrays.stream(flyway.info().applied())
                .filter(m -> m.getState().isApplied() && !m.getState().isFailed())
                .map(m -> m.getVersion().getVersion()).toList();
        assertThat(applied).contains("6", "9", "10", "11", "12").doesNotContain("7", "8");
        token(jdbc, "U-ALICE", "family-1", hash('a'));
        assertThat(jdbc.queryForObject("SELECT token_version FROM refresh_tokens", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT revoked_at FROM refresh_tokens", Object.class)).isNull();
    }

    @Test
    @DisplayName("a token hash is unique; the same family, user and version may repeat")
    void hashIsUnique() throws Exception {
        JdbcTemplate jdbc = migrated();
        token(jdbc, "U-ALICE", "family-1", hash('a'));

        assertThatThrownBy(() -> token(jdbc, "U-BOB", "family-2", hash('a')))
                .isInstanceOf(DataIntegrityViolationException.class);
        token(jdbc, "U-ALICE", "family-1", hash('b'));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_tokens", Integer.class)).isEqualTo(2);
    }

    @Test
    @DisplayName("the owner must exist, and deleting the user deletes the user's tokens (cascade)")
    void ownerAndCascade() throws Exception {
        JdbcTemplate jdbc = migrated();

        assertThatThrownBy(() -> token(jdbc, "U-NOBODY", "family-1", hash('a')))
                .isInstanceOf(DataIntegrityViolationException.class);
        token(jdbc, "U-ALICE", "family-1", hash('a'));
        token(jdbc, "U-BOB", "family-2", hash('b'));
        jdbc.update("DELETE FROM employees WHERE user_id = 'U-ALICE'");
        jdbc.update("DELETE FROM users WHERE id = 'U-ALICE'");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_tokens", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("every column the application writes is mandatory except the revocation fields and the replacement")
    void mandatoryColumns() throws Exception {
        JdbcTemplate jdbc = migrated();

        assertThatThrownBy(() -> jdbc.update(INSERT, "U-ALICE", null, hash('a'), 1, NOW, NOW.plusHours(1)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(INSERT, "U-ALICE", "f", null, 1, NOW, NOW.plusHours(1)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(INSERT, "U-ALICE", "f", hash('a'), 1, NOW, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("INSERT INTO refresh_tokens (user_id, family_id, token_hash, token_version, issued_at, expires_at, "
                + "revoked_at, revoked_reason, replaced_by_id) VALUES ('U-ALICE', 'f', ?, 2, ?, ?, ?, 'ROTATED', 7)",
                hash('c'), NOW, NOW.plusHours(1), NOW);
        assertThat(jdbc.queryForObject("SELECT replaced_by_id FROM refresh_tokens", Long.class)).isEqualTo(7L);
    }

    @Test
    @DisplayName("purging expired rows needs no particular order: a replaced_by_id may point to a row that is already gone")
    void replacedByHasNoForeignKey() throws Exception {
        JdbcTemplate jdbc = migrated();
        jdbc.update("INSERT INTO refresh_tokens (user_id, family_id, token_hash, token_version, issued_at, expires_at, "
                + "replaced_by_id) VALUES ('U-ALICE', 'f', ?, 1, ?, ?, 999999)", hash('d'), NOW, NOW.plusHours(1));

        assertThat(jdbc.update("DELETE FROM refresh_tokens WHERE expires_at < ?", NOW.plusDays(1))).isEqualTo(1);
    }
}
