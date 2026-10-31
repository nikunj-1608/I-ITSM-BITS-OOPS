package com.iitsm.infrastructure.persistence.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitsm.domain.policy.RegistryDepartmentCodeResolver;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Behaviour of the V4 migration that renames old {@code INC-XXXXXXXX} tickets. The same scenarios run
 * on H2 (the dev/test database) and on a real PostgreSQL (prod/demo), see the two subclasses.
 * Each scenario starts from an empty database migrated to V3, seeds old-style data, then migrates on.
 */
abstract class LegacyTicketMigrationTestBase {

    static final OffsetDateTime Y2025 = OffsetDateTime.parse("2025-03-01T10:00:00Z");
    static final OffsetDateTime JAN_2026 = OffsetDateTime.parse("2026-01-10T10:00:00Z");
    static final OffsetDateTime FEB_2026 = OffsetDateTime.parse("2026-02-01T10:00:00Z");

    /** A fresh, empty database. */
    abstract DataSource newDatabase() throws Exception;

    // ---------------------------------------------------------------- fixture

    DataSource migratedToV3() throws Exception {
        DataSource ds = newDatabase();
        flyway(ds, "3").migrate();
        return ds;
    }

    static Flyway flyway(DataSource ds, String target) {
        return Flyway.configure().dataSource(ds).locations("classpath:db/migration").target(target).load();
    }

    static void seedUsers(JdbcTemplate jdbc) {
        user(jdbc, "U-ALICE", "alice@example.com", "ROLE_EMPLOYEE", "EMPLOYEE", "Finance");
        user(jdbc, "U-BOB", "bob@example.com", "ROLE_EMPLOYEE", "EMPLOYEE", "Engineering");
        user(jdbc, "U-CAROL", "carol@example.com", "ROLE_EMPLOYEE", "EMPLOYEE", "Security");
        user(jdbc, "U-TECH", "tech@example.com", "ROLE_TECHNICIAN", "TECHNICIAN", null);
    }

    private static void user(JdbcTemplate jdbc, String id, String email, String role, String type, String dept) {
        jdbc.update("INSERT INTO users (id, name, email, password_hash, role, user_type, department) "
                + "VALUES (?, ?, ?, 'hash', ?, ?, ?)", id, id, email, role, type, dept);
        if (dept != null) {
            jdbc.update("INSERT INTO employees (user_id, designation, department, seniority_years) "
                    + "VALUES (?, 'ASSOCIATE', ?, 1)", id, dept);
        }
    }

    static void incident(JdbcTemplate jdbc, String id, String reporter, OffsetDateTime createdAt) {
        jdbc.update("INSERT INTO incidents (id, title, description, reporter_id, created_at) "
                + "VALUES (?, 'Some title', 'Some description that is long enough', ?, ?)", id, reporter, createdAt);
    }

    /** Five old tickets (Finance 2025, Finance 2026 x2, Engineering 2026, Security 2026) with dependants. */
    static void seedLegacyTickets(JdbcTemplate jdbc) {
        seedUsers(jdbc);
        incident(jdbc, "INC-AAAA0001", "U-ALICE", Y2025);
        incident(jdbc, "INC-AAAA0002", "U-ALICE", JAN_2026);
        incident(jdbc, "INC-AAAA0003", "U-BOB", JAN_2026.plusDays(1));
        incident(jdbc, "INC-AAAA0004", "U-ALICE", FEB_2026);
        incident(jdbc, "INC-AAAA0005", "U-CAROL", FEB_2026.plusDays(1));
        seedDependants(jdbc);
    }

    private static void seedDependants(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO factor_contributions (incident_id, factor_name, raw_score, weight, weighted_score) "
                + "VALUES ('INC-AAAA0002', 'Aging', 0.5, 0.2, 0.1)");
        jdbc.update("INSERT INTO investigation_notes (id, incident_id, author_id, author_name, note_text, created_at) "
                + "VALUES ('NOTE-1', 'INC-AAAA0002', 'U-TECH', 'Tech', 'Checked the cable', ?)", JAN_2026);
        jdbc.update("INSERT INTO sla_trackers (incident_id, created_at, response_due_at, resolution_due_at) "
                + "VALUES ('INC-AAAA0003', ?, ?, ?)", JAN_2026, JAN_2026, JAN_2026);
        jdbc.update("INSERT INTO escalation_records (id, incident_id, from_role, to_role, to_user_id, reason, "
                + "escalated_at) VALUES ('ESC-1', 'INC-AAAA0004', 'A', 'B', 'U-TECH', 'late', ?)", FEB_2026);
        jdbc.update("INSERT INTO reassignment_requests (id, incident_id, requesting_technician_id, reason, "
                + "requested_at) VALUES ('RR-1', 'INC-AAAA0002', 'U-TECH', 'wrong team', ?)", JAN_2026);
        jdbc.update("INSERT INTO incident_linked_ids (incident_id, linked_incident_id) "
                + "VALUES ('INC-AAAA0003', 'INC-AAAA0002')");
        jdbc.update("INSERT INTO incident_linked_ids (incident_id, linked_incident_id) "
                + "VALUES ('INC-AAAA0002', 'INC-AAAA0003')");
        jdbc.update("INSERT INTO technician_active_incidents (technician_id, incident_id) "
                + "VALUES ('U-TECH', 'INC-AAAA0002')");
        audit(jdbc, "AUD-1", "INC-AAAA0002", "Ticket INC-AAAA0002 created");
        audit(jdbc, "AUD-2", "INC-AAAA0002", "Linked to INC-AAAA0003, ignored INC-FFFF9999");
        audit(jdbc, "AUD-3", "INC-AAAA0003", "Created");
        notification(jdbc, "N-1", "[Ticket Created: INC-AAAA0002]", "Ticket 'INC-AAAA0002' has been created");
        notification(jdbc, "N-2", "Unrelated", "Mentions INC-FFFF9999 which is not a ticket here");
    }

    private static void audit(JdbcTemplate jdbc, String id, String incidentId, String description) {
        jdbc.update("INSERT INTO audit_records (id, timestamp, actor_id, action, incident_id, description) "
                + "VALUES (?, ?, 'SYSTEM', 'CREATED', ?, ?)", id, JAN_2026, incidentId, description);
    }

    private static void notification(JdbcTemplate jdbc, String id, String subject, String message) {
        jdbc.update("INSERT INTO notifications (id, recipient_id, channel, subject, message, created_at) "
                + "VALUES (?, 'U-ALICE', 'IN_APP', ?, ?, ?)", id, subject, message, JAN_2026);
    }

    static List<String> incidentIds(JdbcTemplate jdbc) {
        return jdbc.queryForList("SELECT id FROM incidents ORDER BY id", String.class);
    }

    // ------------------------------------------------------------------ tests

    @Test
    @DisplayName("old tickets get the reporter's department, the creation year and a sequence in creation order")
    void renamesPerDepartmentYearInCreationOrder() throws Exception {
        DataSource ds = migratedToV3();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        seedLegacyTickets(jdbc);

        flyway(ds, "latest").migrate();

        assertThat(incidentIds(jdbc)).containsExactly("ENG-2026-000001", "FIN-2025-000001",
                "FIN-2026-000001", "FIN-2026-000002", "SEC-2026-000001");
        assertThat(jdbc.queryForObject("SELECT reporter_id FROM incidents WHERE id = 'FIN-2026-000002'",
                String.class)).isEqualTo("U-ALICE");
    }

    @Test
    @DisplayName("every dependant row follows its ticket and no old id is left anywhere")
    void carriesEveryReferenceAlong() throws Exception {
        DataSource ds = migratedToV3();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        seedLegacyTickets(jdbc);

        flyway(ds, "latest").migrate();

        // INC-AAAA0002 -> FIN-2026-000001, INC-AAAA0003 -> ENG-2026-000001, INC-AAAA0004 -> FIN-2026-000002
        assertThat(count(jdbc, "factor_contributions", "incident_id", "FIN-2026-000001")).isEqualTo(1);
        assertThat(count(jdbc, "investigation_notes", "incident_id", "FIN-2026-000001")).isEqualTo(1);
        assertThat(count(jdbc, "sla_trackers", "incident_id", "ENG-2026-000001")).isEqualTo(1);
        assertThat(count(jdbc, "escalation_records", "incident_id", "FIN-2026-000002")).isEqualTo(1);
        assertThat(count(jdbc, "reassignment_requests", "incident_id", "FIN-2026-000001")).isEqualTo(1);
        assertThat(count(jdbc, "incident_linked_ids", "incident_id", "ENG-2026-000001")).isEqualTo(1);
        assertThat(count(jdbc, "incident_linked_ids", "linked_incident_id", "FIN-2026-000001")).isEqualTo(1);
        assertThat(count(jdbc, "incident_linked_ids", "incident_id", "FIN-2026-000001")).isEqualTo(1);
        assertThat(count(jdbc, "incident_linked_ids", "linked_incident_id", "ENG-2026-000001")).isEqualTo(1);
        assertThat(count(jdbc, "technician_active_incidents", "incident_id", "FIN-2026-000001")).isEqualTo(1);
        assertThat(count(jdbc, "audit_records", "incident_id", "FIN-2026-000001")).isEqualTo(2);
        assertThat(count(jdbc, "audit_records", "incident_id", "ENG-2026-000001")).isEqualTo(1);
        for (String[] reference : new String[][] {{"factor_contributions", "incident_id"},
            {"investigation_notes", "incident_id"}, {"sla_trackers", "incident_id"},
            {"escalation_records", "incident_id"}, {"reassignment_requests", "incident_id"},
            {"incident_linked_ids", "incident_id"}, {"incident_linked_ids", "linked_incident_id"},
            {"technician_active_incidents", "incident_id"}, {"audit_records", "incident_id"}}) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + reference[0] + " WHERE " + reference[1]
                    + " LIKE 'INC-%'", Integer.class)).as(reference[0] + "." + reference[1]).isZero();
        }
    }

    @Test
    @DisplayName("ticket ids quoted in system messages are rewritten, unknown ids are left alone")
    void rewritesSystemGeneratedText() throws Exception {
        DataSource ds = migratedToV3();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        seedLegacyTickets(jdbc);

        flyway(ds, "latest").migrate();

        assertThat(jdbc.queryForObject("SELECT description FROM audit_records WHERE id = 'AUD-1'", String.class))
                .isEqualTo("Ticket FIN-2026-000001 created");
        assertThat(jdbc.queryForObject("SELECT description FROM audit_records WHERE id = 'AUD-2'", String.class))
                .isEqualTo("Linked to ENG-2026-000001, ignored INC-FFFF9999");
        assertThat(jdbc.queryForObject("SELECT subject FROM notifications WHERE id = 'N-1'", String.class))
                .isEqualTo("[Ticket Created: FIN-2026-000001]");
        assertThat(jdbc.queryForObject("SELECT message FROM notifications WHERE id = 'N-1'", String.class))
                .isEqualTo("Ticket 'FIN-2026-000001' has been created");
        assertThat(jdbc.queryForObject("SELECT message FROM notifications WHERE id = 'N-2'", String.class))
                .isEqualTo("Mentions INC-FFFF9999 which is not a ticket here");
    }

    @Test
    @DisplayName("the counters continue after the renamed tickets, so the next new ticket does not collide")
    void countersContinueAfterRenamedTickets() throws Exception {
        DataSource ds = migratedToV3();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        seedLegacyTickets(jdbc);

        flyway(ds, "latest").migrate();

        assertThat(lastSequence(jdbc, "FIN", 2025)).isEqualTo(1L);
        assertThat(lastSequence(jdbc, "FIN", 2026)).isEqualTo(2L);
        assertThat(lastSequence(jdbc, "ENG", 2026)).isEqualTo(1L);
        assertThat(lastSequence(jdbc, "SEC", 2026)).isEqualTo(1L);
    }

    @Test
    @DisplayName("tickets already in the new format keep their id; old ones continue after the highest number")
    void coexistsWithTicketsAlreadyInNewFormat() throws Exception {
        DataSource ds = migratedToV3();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        seedUsers(jdbc);
        incident(jdbc, "FIN-2026-000007", "U-ALICE", JAN_2026.plusDays(30));
        jdbc.update("INSERT INTO ticket_sequences (department_code, ticket_year, last_sequence) "
                + "VALUES ('FIN', 2026, 7)");
        incident(jdbc, "INC-BBBB0001", "U-ALICE", JAN_2026);
        incident(jdbc, "INC-BBBB0002", "U-ALICE", FEB_2026);

        flyway(ds, "latest").migrate();

        assertThat(incidentIds(jdbc)).containsExactly("FIN-2026-000007", "FIN-2026-000008", "FIN-2026-000009");
        assertThat(lastSequence(jdbc, "FIN", 2026)).isEqualTo(9L);
    }

    @Test
    @DisplayName("foreign keys now cascade updates and still cascade deletes")
    void foreignKeysKeepDeleteRuleAndGainUpdateCascade() throws Exception {
        DataSource ds = migratedToV3();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        seedLegacyTickets(jdbc);

        flyway(ds, "latest").migrate();

        List<Integer> updateRules = new ArrayList<>();
        List<Integer> deleteRules = new ArrayList<>();
        try (Connection c = ds.getConnection()) {
            DatabaseMetaData meta = c.getMetaData();
            String table = meta.storesUpperCaseIdentifiers() ? "INCIDENTS" : "incidents";
            try (ResultSet rs = meta.getExportedKeys(c.getCatalog(), c.getSchema(), table)) {
                while (rs.next()) {
                    updateRules.add(rs.getInt("UPDATE_RULE"));
                    deleteRules.add(rs.getInt("DELETE_RULE"));
                }
            }
        }
        assertThat(updateRules).hasSize(9).containsOnly(DatabaseMetaData.importedKeyCascade);
        assertThat(deleteRules).hasSize(9).containsOnly(DatabaseMetaData.importedKeyCascade);

        jdbc.update("DELETE FROM incidents WHERE id = 'FIN-2026-000001'");
        assertThat(count(jdbc, "investigation_notes", "incident_id", "FIN-2026-000001")).isZero();
    }

    @Test
    @DisplayName("running the renamer again changes nothing")
    void secondRunIsANoOp() throws Exception {
        DataSource ds = migratedToV3();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        seedLegacyTickets(jdbc);
        flyway(ds, "latest").migrate();
        List<String> before = incidentIds(jdbc);

        try (Connection c = ds.getConnection()) {
            assertThat(new LegacyTicketRenamer(c, new RegistryDepartmentCodeResolver()).run()).isZero();
        }

        assertThat(incidentIds(jdbc)).isEqualTo(before);
        assertThat(lastSequence(jdbc, "FIN", 2026)).isEqualTo(2L);
    }

    @Test
    @DisplayName("a database without old tickets is left exactly as it was")
    void freshDatabaseIsUntouched() throws Exception {
        DataSource ds = newDatabase();

        flyway(ds, "latest").migrate();

        JdbcTemplate jdbc = new JdbcTemplate(ds);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_sequences", Integer.class)).isZero();
        try (Connection c = ds.getConnection()) {
            DatabaseMetaData meta = c.getMetaData();
            String table = meta.storesUpperCaseIdentifiers() ? "INCIDENTS" : "incidents";
            try (ResultSet rs = meta.getExportedKeys(c.getCatalog(), c.getSchema(), table)) {
                while (rs.next()) {
                    assertThat(rs.getInt("UPDATE_RULE")).as("untouched constraint").isNotEqualTo(
                            DatabaseMetaData.importedKeyCascade);
                }
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    static int count(JdbcTemplate jdbc, String table, String column, String value) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?", Integer.class,
                value);
    }

    static long lastSequence(JdbcTemplate jdbc, String code, int year) {
        return jdbc.queryForObject("SELECT last_sequence FROM ticket_sequences WHERE department_code = ? "
                + "AND ticket_year = ?", Long.class, code, year);
    }
}
