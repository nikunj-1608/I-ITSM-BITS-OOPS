package com.iitsm.infrastructure.persistence.migration;

import com.iitsm.domain.model.TicketNumber;
import com.iitsm.domain.policy.DepartmentCodeResolver;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pattern: Transaction Script (one-off data migration).
 * Renames tickets that still carry the old random id ({@code INC-XXXXXXXX}) to the department based
 * format ({@code FIN-2026-000142}) and keeps every reference to them intact. The department is the
 * reporter's, the year is the ticket's creation year (UTC) and the sequence follows creation order.
 *
 * <p>Plain JDBC on purpose: it runs inside Flyway's migration transaction (all or nothing on
 * PostgreSQL) and must behave the same on PostgreSQL and H2. It is a no-op when no old ids exist.
 */
public class LegacyTicketRenamer {

    private static final Logger log = LoggerFactory.getLogger(LegacyTicketRenamer.class);
    private static final Pattern LEGACY_ID = Pattern.compile("INC-[0-9A-Fa-f]{8}");
    private static final String INCIDENTS = "incidents";

    /** Tables that store an incident id without a foreign key: {table, column}. */
    private static final String[][] UNCONSTRAINED_REFERENCES = {
        {"technician_active_incidents", "incident_id"},
        {"incident_linked_ids", "linked_incident_id"},
        {"audit_records", "incident_id"},
    };

    private final Connection connection;
    private final DepartmentCodeResolver departmentCodeResolver;

    public LegacyTicketRenamer(Connection connection, DepartmentCodeResolver departmentCodeResolver) {
        this.connection = Objects.requireNonNull(connection, "connection cannot be null");
        this.departmentCodeResolver =
                Objects.requireNonNull(departmentCodeResolver, "departmentCodeResolver cannot be null");
    }

    /** Renames every old-style ticket; returns how many were renamed. */
    public int run() throws SQLException {
        Plan plan = plan();
        if (plan.renames().isEmpty()) {
            log.info("Ticket numbering migration: no old-style ticket ids found, nothing to rename");
            return 0;
        }
        makeIncidentForeignKeysCascadeUpdates();
        for (String[] reference : UNCONSTRAINED_REFERENCES) {
            renameColumnValues(reference[0], reference[1], plan.renames());
        }
        renameColumnValues(INCIDENTS, "id", plan.renames());
        rewriteText("notifications", "id", List.of("subject", "message"), plan.renames());
        rewriteText("audit_records", "id", List.of("description"), plan.renames());
        saveCounters(plan);
        verifyAllRenamed();
        log.info("Ticket numbering migration: renamed {} ticket(s) to the department based format",
                plan.renames().size());
        return plan.renames().size();
    }

    // ---------------------------------------------------------------- planning

    private record SequenceKey(String departmentCode, int year) { }

    private record Plan(Map<String, String> renames, Map<SequenceKey, Long> counters, Set<SequenceKey> touched) { }

    private Plan plan() throws SQLException {
        Map<SequenceKey, Long> counters = loadCounters();
        List<Object[]> legacy = new ArrayList<>();
        String sql = "SELECT i.id, i.created_at, COALESCE(e.department, u.department) "
                + "FROM incidents i LEFT JOIN employees e ON e.user_id = i.reporter_id "
                + "LEFT JOIN users u ON u.id = i.reporter_id ORDER BY i.created_at, i.id";
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                String id = rs.getString(1);
                if (TicketNumber.isValid(id)) {
                    TicketNumber existing = TicketNumber.parse(id);
                    counters.merge(new SequenceKey(existing.departmentCode(), existing.year()),
                            existing.sequence(), Math::max);
                } else {
                    legacy.add(new Object[] {id, rs.getObject(2, OffsetDateTime.class), rs.getString(3)});
                }
            }
        }
        return assignNumbers(legacy, counters);
    }

    private Plan assignNumbers(List<Object[]> legacy, Map<SequenceKey, Long> counters) {
        Map<String, String> renames = new LinkedHashMap<>();
        Set<SequenceKey> touched = new HashSet<>();
        for (Object[] row : legacy) {
            String code = departmentCodeResolver.codeFor((String) row[2]);
            int year = ((OffsetDateTime) row[1]).withOffsetSameInstant(ZoneOffset.UTC).getYear();
            SequenceKey key = new SequenceKey(code, year);
            long sequence = counters.merge(key, 1L, Long::sum);
            touched.add(key);
            renames.put((String) row[0], new TicketNumber(code, year, sequence).format());
        }
        return new Plan(renames, counters, touched);
    }

    private Map<SequenceKey, Long> loadCounters() throws SQLException {
        Map<SequenceKey, Long> counters = new HashMap<>();
        String sql = "SELECT department_code, ticket_year, last_sequence FROM ticket_sequences";
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                counters.put(new SequenceKey(rs.getString(1), rs.getInt(2)), rs.getLong(3));
            }
        }
        return counters;
    }

    // ------------------------------------------------------------ foreign keys

    private record ForeignKey(String table, String column, String name, int deleteRule, int updateRule) { }

    /**
     * Re-creates the foreign keys that point at incidents(id) with ON UPDATE CASCADE (keeping their
     * delete rule) so changing an incident id carries over to every dependent row. Constraint names
     * differ per database, so they are read from the JDBC metadata instead of being hard-coded.
     */
    private void makeIncidentForeignKeysCascadeUpdates() throws SQLException {
        for (ForeignKey fk : findForeignKeysToIncidents()) {
            if (fk.updateRule() == DatabaseMetaData.importedKeyCascade) {
                continue;
            }
            try (Statement st = connection.createStatement()) {
                st.execute("ALTER TABLE " + quote(fk.table()) + " DROP CONSTRAINT " + quote(fk.name()));
                st.execute("ALTER TABLE " + quote(fk.table()) + " ADD CONSTRAINT " + quote(fk.name())
                        + " FOREIGN KEY (" + quote(fk.column()) + ") REFERENCES " + quote(storedName(INCIDENTS))
                        + " (" + quote(storedName("id")) + ") ON DELETE " + deleteAction(fk.deleteRule())
                        + " ON UPDATE CASCADE");
            }
        }
    }

    private List<ForeignKey> findForeignKeysToIncidents() throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        List<ForeignKey> keys = new ArrayList<>();
        try (ResultSet rs = meta.getExportedKeys(connection.getCatalog(), connection.getSchema(),
                storedName(INCIDENTS))) {
            while (rs.next()) {
                keys.add(new ForeignKey(rs.getString("FKTABLE_NAME"), rs.getString("FKCOLUMN_NAME"),
                        rs.getString("FK_NAME"), rs.getInt("DELETE_RULE"), rs.getInt("UPDATE_RULE")));
            }
        }
        return keys;
    }

    private static String deleteAction(int rule) {
        return switch (rule) {
            case DatabaseMetaData.importedKeyCascade -> "CASCADE";
            case DatabaseMetaData.importedKeySetNull -> "SET NULL";
            case DatabaseMetaData.importedKeySetDefault -> "SET DEFAULT";
            case DatabaseMetaData.importedKeyRestrict -> "RESTRICT";
            default -> "NO ACTION";
        };
    }

    /** The name as the database stores it (H2 upper-cases unquoted names, PostgreSQL lower-cases). */
    private String storedName(String name) throws SQLException {
        return connection.getMetaData().storesUpperCaseIdentifiers() ? name.toUpperCase() : name.toLowerCase();
    }

    private static String quote(String identifier) {
        if (identifier.indexOf('"') >= 0) {
            throw new IllegalStateException("Unexpected quote in identifier: " + identifier);
        }
        return '"' + identifier + '"';
    }

    // ------------------------------------------------------------- data updates

    private void renameColumnValues(String table, String column, Map<String, String> renames) throws SQLException {
        String sql = "UPDATE " + table + " SET " + column + " = ? WHERE " + column + " = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (Map.Entry<String, String> rename : renames.entrySet()) {
                ps.setString(1, rename.getValue());
                ps.setString(2, rename.getKey());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /** Replaces old ids quoted inside system generated text (notification and audit messages). */
    private void rewriteText(String table, String idColumn, List<String> columns, Map<String, String> renames)
            throws SQLException {
        List<String[]> changed = new ArrayList<>();
        String select = "SELECT " + idColumn + ", " + String.join(", ", columns) + " FROM " + table
                + " WHERE " + String.join(" OR ", columns.stream().map(c -> c + " LIKE '%INC-%'").toList());
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(select)) {
            while (rs.next()) {
                String[] row = new String[columns.size() + 1];
                row[0] = rs.getString(1);
                boolean differs = false;
                for (int i = 0; i < columns.size(); i++) {
                    String original = rs.getString(i + 2);
                    row[i + 1] = replaceLegacyIds(original, renames);
                    differs |= !Objects.equals(original, row[i + 1]);
                }
                if (differs) {
                    changed.add(row);
                }
            }
        }
        writeText(table, idColumn, columns, changed);
    }

    private void writeText(String table, String idColumn, List<String> columns, List<String[]> rows)
            throws SQLException {
        String assignments = String.join(", ", columns.stream().map(c -> c + " = ?").toList());
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE " + table + " SET " + assignments + " WHERE " + idColumn + " = ?")) {
            for (String[] row : rows) {
                for (int i = 0; i < columns.size(); i++) {
                    ps.setString(i + 1, row[i + 1]);
                }
                ps.setString(columns.size() + 1, row[0]);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    static String replaceLegacyIds(String text, Map<String, String> renames) {
        if (text == null) {
            return null;
        }
        Matcher matcher = LEGACY_ID.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String match = matcher.group();
            matcher.appendReplacement(out, Matcher.quoteReplacement(
                    renames.getOrDefault(match.toUpperCase(), match)));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private void saveCounters(Plan plan) throws SQLException {
        String update = "UPDATE ticket_sequences SET last_sequence = ? WHERE department_code = ? AND ticket_year = ?";
        String insert = "INSERT INTO ticket_sequences (last_sequence, department_code, ticket_year) VALUES (?, ?, ?)";
        for (SequenceKey key : plan.touched()) {
            long last = plan.counters().get(key);
            if (!writeCounter(update, last, key)) {
                writeCounter(insert, last, key);
            }
        }
    }

    private boolean writeCounter(String sql, long last, SequenceKey key) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, last);
            ps.setString(2, key.departmentCode());
            ps.setInt(3, key.year());
            return ps.executeUpdate() > 0;
        }
    }

    /** Safety net: fail (and roll the migration back on PostgreSQL) if any old id is left behind. */
    private void verifyAllRenamed() throws SQLException {
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery("SELECT id FROM incidents")) {
            while (rs.next()) {
                if (!TicketNumber.isValid(rs.getString(1))) {
                    throw new IllegalStateException("Ticket id was not renamed: " + rs.getString(1));
                }
            }
        }
    }
}
