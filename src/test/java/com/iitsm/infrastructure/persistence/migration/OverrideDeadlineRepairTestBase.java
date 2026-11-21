package com.iitsm.infrastructure.persistence.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V6 (ADR-024) repairs tickets whose priority was overridden before the SLA deadlines followed the override,
 * and only those. Each scenario starts from an empty database migrated to V5, seeds tickets, then migrates on.
 * The same scenarios run on H2 and on a real PostgreSQL, see the two subclasses.
 */
abstract class OverrideDeadlineRepairTestBase {

    private static final Instant CREATED = Instant.parse("2026-11-03T08:00:00Z");

    abstract DataSource newDatabase() throws Exception;

    // ---------------------------------------------------------------- fixture

    private DataSource migratedToV5() throws Exception {
        DataSource ds = newDatabase();
        flyway(ds, "5").migrate();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        LegacyTicketMigrationTestBase.seedUsers(jdbc);
        return ds;
    }

    private static Flyway flyway(DataSource ds, String target) {
        return Flyway.configure().dataSource(ds).locations("classpath:db/migration").target(target).load();
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /** A ticket currently at {@code current} whose tracker still holds the deadlines of {@code deadlinesOf}. */
    private static void ticket(JdbcTemplate jdbc, String id, String current, String overriddenBy, String deadlinesOf,
                               long pausedSeconds, OffsetDateTime resolvedAt) {
        LegacyTicketMigrationTestBase.incident(jdbc, id, "U-ALICE", at(CREATED));
        jdbc.update("UPDATE incidents SET priority = ?, priority_overridden_by = ? WHERE id = ?", current, overriddenBy, id);
        long[] targets = targets(deadlinesOf);
        jdbc.update("INSERT INTO sla_trackers (incident_id, created_at, response_due_at, resolution_due_at, "
                        + "resolved_at, paused_duration_seconds) VALUES (?, ?, ?, ?, ?, ?)", id, at(CREATED),
                at(CREATED.plusSeconds(targets[0] + pausedSeconds)), at(CREATED.plusSeconds(targets[1] + pausedSeconds)),
                resolvedAt, pausedSeconds);
    }

    private static void overrideAudit(JdbcTemplate jdbc, String auditId, String incidentId, String from, String to,
                                      Instant when) {
        jdbc.update("INSERT INTO audit_records (id, timestamp, actor_id, action, incident_id, description) "
                        + "VALUES (?, ?, 'ADMIN-1', 'PriorityOverridden', ?, ?)", auditId, at(when), incidentId,
                "Priority changed from " + from + " to " + to + ". Reason: test");
    }

    private static void policy(JdbcTemplate jdbc, String priority, long responseSeconds, long resolutionSeconds) {
        jdbc.update("INSERT INTO sla_policies (id, priority, response_target_seconds, resolution_target_seconds) "
                + "VALUES (?, ?, ?, ?)", "SLA-DEF-" + priority, priority, responseSeconds, resolutionSeconds);
    }

    /** Factory targets in seconds: response, resolution. */
    private static long[] targets(String priority) {
        return switch (priority) {
            case "P1" -> new long[] {300, 1800};
            case "P2" -> new long[] {900, 7200};
            case "P3" -> new long[] {3600, 28800};
            default -> new long[] {14400, 86400};
        };
    }

    private static Instant resolutionDue(JdbcTemplate jdbc, String id) {
        return jdbc.queryForObject("SELECT resolution_due_at FROM sla_trackers WHERE incident_id = ?",
                OffsetDateTime.class, id).toInstant();
    }

    private static Instant responseDue(JdbcTemplate jdbc, String id) {
        return jdbc.queryForObject("SELECT response_due_at FROM sla_trackers WHERE incident_id = ?",
                OffsetDateTime.class, id).toInstant();
    }

    private static int repair(DataSource ds) throws Exception {
        try (Connection c = ds.getConnection()) {
            return new OverrideDeadlineRepairer(c).run();
        }
    }

    // ---------------------------------------------------------------- scenarios

    @Test
    @DisplayName("a ticket overridden P4 -> P1 that still has the P4 deadlines gets the P1 deadlines")
    void staleTicketIsRepaired() throws Exception {
        DataSource ds = migratedToV5();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        ticket(jdbc, "ENG-2026-000001", "P1", "ADMIN-1", "P4", 0, null);
        overrideAudit(jdbc, "A1", "ENG-2026-000001", "P4", "P1", CREATED.plusSeconds(60));

        flyway(ds, "6").migrate();

        assertThat(resolutionDue(jdbc, "ENG-2026-000001")).isEqualTo(CREATED.plusSeconds(1800));
        assertThat(responseDue(jdbc, "ENG-2026-000001")).isEqualTo(CREATED.plusSeconds(300));
    }

    @Test
    @DisplayName("lowering is repaired too: P1 -> P3 gets the longer P3 deadlines")
    void loweredTicketIsRepaired() throws Exception {
        DataSource ds = migratedToV5();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        ticket(jdbc, "ENG-2026-000002", "P3", "ADMIN-1", "P1", 0, null);
        overrideAudit(jdbc, "A2", "ENG-2026-000002", "P1", "P3", CREATED.plusSeconds(60));

        flyway(ds, "6").migrate();

        assertThat(resolutionDue(jdbc, "ENG-2026-000002")).isEqualTo(CREATED.plusSeconds(28800));
    }

    @Test
    @DisplayName("time already spent paused is kept in the repaired deadlines")
    void pausedTimeIsKept() throws Exception {
        DataSource ds = migratedToV5();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        ticket(jdbc, "ENG-2026-000003", "P1", "ADMIN-1", "P4", 1200, null);
        overrideAudit(jdbc, "A3", "ENG-2026-000003", "P4", "P1", CREATED.plusSeconds(60));

        flyway(ds, "6").migrate();

        assertThat(resolutionDue(jdbc, "ENG-2026-000003")).isEqualTo(CREATED.plusSeconds(1800 + 1200));
    }

    @Test
    @DisplayName("a ticket whose deadlines already match its current priority is left alone")
    void alreadyCorrectTicketIsUntouched() throws Exception {
        DataSource ds = migratedToV5();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        ticket(jdbc, "ENG-2026-000004", "P1", "ADMIN-1", "P1", 0, null);
        overrideAudit(jdbc, "A4", "ENG-2026-000004", "P4", "P1", CREATED.plusSeconds(60));

        flyway(ds, "6").migrate();

        assertThat(resolutionDue(jdbc, "ENG-2026-000004")).isEqualTo(CREATED.plusSeconds(1800));
    }

    @Test
    @DisplayName("a resolved ticket keeps its deadlines and verdict")
    void resolvedTicketIsUntouched() throws Exception {
        DataSource ds = migratedToV5();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        ticket(jdbc, "ENG-2026-000005", "P1", "ADMIN-1", "P4", 0, at(CREATED.plusSeconds(600)));
        overrideAudit(jdbc, "A5", "ENG-2026-000005", "P4", "P1", CREATED.plusSeconds(60));

        flyway(ds, "6").migrate();

        assertThat(resolutionDue(jdbc, "ENG-2026-000005")).isEqualTo(CREATED.plusSeconds(86400));
    }

    @Test
    @DisplayName("a deadline that is not explained by the old priority (extra credit time) is not rewritten")
    void unexplainedDeadlineIsUntouched() throws Exception {
        DataSource ds = migratedToV5();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        ticket(jdbc, "ENG-2026-000006", "P1", "ADMIN-1", "P4", 0, null);
        jdbc.update("UPDATE sla_trackers SET resolution_due_at = ? WHERE incident_id = 'ENG-2026-000006'",
                at(CREATED.plusSeconds(86400 + 1500)));
        overrideAudit(jdbc, "A6", "ENG-2026-000006", "P4", "P1", CREATED.plusSeconds(60));

        flyway(ds, "6").migrate();

        assertThat(resolutionDue(jdbc, "ENG-2026-000006")).isEqualTo(CREATED.plusSeconds(86400 + 1500));
    }

    @Test
    @DisplayName("an overridden ticket with no audit entry of the override is left alone")
    void noAuditEntryMeansNoRepair() throws Exception {
        DataSource ds = migratedToV5();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        ticket(jdbc, "ENG-2026-000007", "P1", "ADMIN-1", "P4", 0, null);

        flyway(ds, "6").migrate();

        assertThat(resolutionDue(jdbc, "ENG-2026-000007")).isEqualTo(CREATED.plusSeconds(86400));
    }

    @Test
    @DisplayName("a ticket that was never overridden is left alone")
    void notOverriddenIsUntouched() throws Exception {
        DataSource ds = migratedToV5();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        ticket(jdbc, "ENG-2026-000008", "P1", null, "P4", 0, null);
        overrideAudit(jdbc, "A8", "ENG-2026-000008", "P4", "P1", CREATED.plusSeconds(60));

        flyway(ds, "6").migrate();

        assertThat(resolutionDue(jdbc, "ENG-2026-000008")).isEqualTo(CREATED.plusSeconds(86400));
    }

    @Test
    @DisplayName("an override to the same priority needs no repair")
    void samePriorityOverrideIsUntouched() throws Exception {
        DataSource ds = migratedToV5();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        ticket(jdbc, "ENG-2026-000009", "P3", "ADMIN-1", "P3", 0, null);
        overrideAudit(jdbc, "A9", "ENG-2026-000009", "P3", "P3", CREATED.plusSeconds(60));

        assertThat(repairAfterV6(ds)).isZero();
        assertThat(resolutionDue(jdbc, "ENG-2026-000009")).isEqualTo(CREATED.plusSeconds(28800));
    }

    @Test
    @DisplayName("with two overrides the first audit entry tells which priority the deadlines belong to")
    void firstOverrideDecides() throws Exception {
        DataSource ds = migratedToV5();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        ticket(jdbc, "ENG-2026-000010", "P2", "ADMIN-1", "P4", 0, null);
        overrideAudit(jdbc, "A10a", "ENG-2026-000010", "P4", "P1", CREATED.plusSeconds(60));
        overrideAudit(jdbc, "A10b", "ENG-2026-000010", "P1", "P2", CREATED.plusSeconds(120));

        flyway(ds, "6").migrate();

        assertThat(resolutionDue(jdbc, "ENG-2026-000010")).isEqualTo(CREATED.plusSeconds(7200));
    }

    @Test
    @DisplayName("targets stored in sla_policies win over the factory defaults")
    void storedPolicyIsUsed() throws Exception {
        DataSource ds = migratedToV5();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        policy(jdbc, "P1", 120, 720);
        ticket(jdbc, "ENG-2026-000011", "P1", "ADMIN-1", "P4", 0, null);
        overrideAudit(jdbc, "A11", "ENG-2026-000011", "P4", "P1", CREATED.plusSeconds(60));

        flyway(ds, "6").migrate();

        assertThat(resolutionDue(jdbc, "ENG-2026-000011")).isEqualTo(CREATED.plusSeconds(720));
        assertThat(responseDue(jdbc, "ENG-2026-000011")).isEqualTo(CREATED.plusSeconds(120));
    }

    @Test
    @DisplayName("running the repair twice changes nothing the second time")
    void repairIsIdempotent() throws Exception {
        DataSource ds = migratedToV5();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        ticket(jdbc, "ENG-2026-000012", "P1", "ADMIN-1", "P4", 0, null);
        overrideAudit(jdbc, "A12", "ENG-2026-000012", "P4", "P1", CREATED.plusSeconds(60));
        flyway(ds, "6").migrate();

        assertThat(repair(ds)).isZero();
        assertThat(resolutionDue(jdbc, "ENG-2026-000012")).isEqualTo(CREATED.plusSeconds(1800));
    }

    @Test
    @DisplayName("the status column is not touched, so the next scan announces the breach")
    void statusIsLeftForTheScan() throws Exception {
        DataSource ds = migratedToV5();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        ticket(jdbc, "ENG-2026-000013", "P1", "ADMIN-1", "P4", 0, null);
        overrideAudit(jdbc, "A13", "ENG-2026-000013", "P4", "P1", CREATED.plusSeconds(60));

        flyway(ds, "6").migrate();

        assertThat(jdbc.queryForObject("SELECT status FROM sla_trackers WHERE incident_id = 'ENG-2026-000013'",
                String.class)).isEqualTo("ON_TRACK");
    }

    @Test
    @DisplayName("a mixed database: only the stale tickets change")
    void mixedDatabase() throws Exception {
        DataSource ds = migratedToV5();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        ticket(jdbc, "ENG-2026-000020", "P1", "ADMIN-1", "P4", 0, null);
        overrideAudit(jdbc, "A20", "ENG-2026-000020", "P4", "P1", CREATED.plusSeconds(60));
        ticket(jdbc, "ENG-2026-000021", "P2", "ADMIN-1", "P2", 0, null);
        overrideAudit(jdbc, "A21", "ENG-2026-000021", "P3", "P2", CREATED.plusSeconds(60));
        ticket(jdbc, "ENG-2026-000022", "P3", null, "P3", 0, null);

        assertThat(repairAfterV6(ds)).as("nothing left to repair after V6").isZero();
        assertThat(resolutionDue(jdbc, "ENG-2026-000020")).isEqualTo(CREATED.plusSeconds(1800));
        assertThat(resolutionDue(jdbc, "ENG-2026-000021")).isEqualTo(CREATED.plusSeconds(7200));
        assertThat(resolutionDue(jdbc, "ENG-2026-000022")).isEqualTo(CREATED.plusSeconds(28800));
    }

    @Test
    @DisplayName("V5 adds the credit column with 0 for existing trackers")
    void creditColumnDefaultsToZero() throws Exception {
        DataSource ds = migratedToV5();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        ticket(jdbc, "ENG-2026-000030", "P3", null, "P3", 0, null);

        assertThat(jdbc.queryForObject("SELECT credited_duration_seconds FROM sla_trackers "
                + "WHERE incident_id = 'ENG-2026-000030'", Long.class)).isZero();
    }

    @Test
    @DisplayName("the repaired deadline is exactly the policy target, not rounded")
    void repairedDeadlineIsExact() throws Exception {
        DataSource ds = migratedToV5();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        ticket(jdbc, "ENG-2026-000031", "P2", "ADMIN-1", "P3", 0, null);
        overrideAudit(jdbc, "A31", "ENG-2026-000031", "P3", "P2", CREATED.plusSeconds(60));

        flyway(ds, "6").migrate();

        assertThat(Duration.between(CREATED, resolutionDue(jdbc, "ENG-2026-000031"))).isEqualTo(Duration.ofHours(2));
    }

    /** Runs V6 through Flyway, then runs the repairer once more and returns what that second run changed. */
    private int repairAfterV6(DataSource ds) throws Exception {
        flyway(ds, "6").migrate();
        return repair(ds);
    }
}
