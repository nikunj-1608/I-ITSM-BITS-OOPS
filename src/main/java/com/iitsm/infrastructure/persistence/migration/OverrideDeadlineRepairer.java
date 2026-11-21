package com.iitsm.infrastructure.persistence.migration;

import com.iitsm.domain.model.Priority;
import com.iitsm.domain.model.SlaPolicy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pattern: Transaction Script (one-off data repair, ADR-024).
 * Before the H6 fix a priority override changed the stored priority but left the SLA deadlines of the
 * original priority. This repairs exactly those tickets: unresolved, overridden, and whose stored deadlines
 * still equal {@code created + target of the priority they had before the first override + paused time}.
 * The deadlines are then set to the policy of the current priority, as {@code SlaTracker.updatePolicy} does.
 * Anything else (a deadline that was changed by something else, a reclassified ticket, no audit entry of the
 * override) is left alone, so the repair never rewrites a deadline it cannot explain. The status column is
 * not touched: the next SLA scan re-evaluates it and announces any breach.
 * It is idempotent: a repaired ticket no longer matches its old policy.
 */
public class OverrideDeadlineRepairer {

    private static final Logger log = LoggerFactory.getLogger(OverrideDeadlineRepairer.class);

    /** Stored deadlines may differ from the exact sum by the paused time kept in whole seconds. */
    private static final Duration TOLERANCE = Duration.ofSeconds(5);

    private static final Pattern ORIGINAL_PRIORITY = Pattern.compile("Priority changed from (P[1-4]) to (P[1-4])");

    private static final String CANDIDATES = "SELECT t.incident_id, t.created_at, t.response_due_at, "
            + "t.resolution_due_at, t.paused_duration_seconds, i.priority "
            + "FROM sla_trackers t JOIN incidents i ON i.id = t.incident_id "
            + "WHERE i.priority_overridden_by IS NOT NULL AND t.resolved_at IS NULL AND i.priority IS NOT NULL";

    private static final String FIRST_OVERRIDE = "SELECT description FROM audit_records "
            + "WHERE incident_id = ? AND action = 'PriorityOverridden' ORDER BY timestamp ASC";

    private static final String POLICY = "SELECT response_target_seconds, resolution_target_seconds "
            + "FROM sla_policies WHERE priority = ? AND service_id_override IS NULL";

    private static final String UPDATE = "UPDATE sla_trackers SET response_due_at = ?, resolution_due_at = ? "
            + "WHERE incident_id = ?";

    private record Candidate(String incidentId, Instant createdAt, Instant responseDueAt, Instant resolutionDueAt,
                             Duration paused, Priority current) {}

    private record Targets(Duration response, Duration resolution) {}

    private final Connection connection;

    public OverrideDeadlineRepairer(Connection connection) {
        this.connection = connection;
    }

    /** Repairs every matching ticket and returns how many were changed. */
    public int run() throws SQLException {
        int repaired = 0;
        for (Candidate candidate : candidates()) {
            Optional<Priority> original = originalPriority(candidate.incidentId());
            if (original.isEmpty() || original.get() == candidate.current()) {
                continue;
            }
            Targets before = targetsFor(original.get());
            if (!hasStaleDeadlines(candidate, before)) {
                continue;
            }
            Targets after = targetsFor(candidate.current());
            update(candidate, after);
            repaired++;
        }
        if (repaired > 0) {
            log.info("Repaired the SLA deadlines of {} ticket(s) whose priority was overridden before ADR-024", repaired);
        }
        return repaired;
    }

    private List<Candidate> candidates() throws SQLException {
        List<Candidate> found = new ArrayList<>();
        try (PreparedStatement st = connection.prepareStatement(CANDIDATES); ResultSet rs = st.executeQuery()) {
            while (rs.next()) {
                found.add(new Candidate(
                        rs.getString("incident_id"),
                        instant(rs, "created_at"),
                        instant(rs, "response_due_at"),
                        instant(rs, "resolution_due_at"),
                        Duration.ofSeconds(rs.getLong("paused_duration_seconds")),
                        Priority.valueOf(rs.getString("priority"))));
            }
        }
        return found;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }

    /** The priority the ticket had before its first override, read from the audit entry of that override. */
    private Optional<Priority> originalPriority(String incidentId) throws SQLException {
        try (PreparedStatement st = connection.prepareStatement(FIRST_OVERRIDE)) {
            st.setString(1, incidentId);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    Matcher matcher = ORIGINAL_PRIORITY.matcher(rs.getString(1));
                    if (matcher.find()) {
                        return Optional.of(Priority.valueOf(matcher.group(1)));
                    }
                }
            }
        }
        return Optional.empty();
    }

    private Targets targetsFor(Priority priority) throws SQLException {
        try (PreparedStatement st = connection.prepareStatement(POLICY)) {
            st.setString(1, priority.name());
            try (ResultSet rs = st.executeQuery()) {
                if (rs.next()) {
                    return new Targets(Duration.ofSeconds(rs.getLong(1)), Duration.ofSeconds(rs.getLong(2)));
                }
            }
        }
        SlaPolicy fallback = SlaPolicy.defaultFor(priority);
        return new Targets(fallback.getResponseTarget(), fallback.getResolutionTarget());
    }

    private static boolean hasStaleDeadlines(Candidate c, Targets old) {
        Instant response = c.createdAt().plus(old.response()).plus(c.paused());
        Instant resolution = c.createdAt().plus(old.resolution()).plus(c.paused());
        return within(c.responseDueAt(), response) && within(c.resolutionDueAt(), resolution);
    }

    private static boolean within(Instant stored, Instant expected) {
        return Duration.between(stored, expected).abs().compareTo(TOLERANCE) <= 0;
    }

    private void update(Candidate c, Targets target) throws SQLException {
        Instant response = c.createdAt().plus(target.response()).plus(c.paused());
        Instant resolution = c.createdAt().plus(target.resolution()).plus(c.paused());
        try (PreparedStatement st = connection.prepareStatement(UPDATE)) {
            st.setObject(1, OffsetDateTime.ofInstant(response, ZoneOffset.UTC));
            st.setObject(2, OffsetDateTime.ofInstant(resolution, ZoneOffset.UTC));
            st.setString(3, c.incidentId());
            st.executeUpdate();
        }
    }
}
