package db.migration;

import com.iitsm.infrastructure.persistence.migration.OverrideDeadlineRepairer;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * Flyway Migration V6: give tickets whose priority was overridden before ADR-024 the SLA deadlines of their
 * current priority. The logic lives in {@link OverrideDeadlineRepairer} so it can be tested on its own.
 * Flyway requires this class name and package (db.migration) to discover a Java migration.
 */
public class V6__repair_override_deadlines extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        new OverrideDeadlineRepairer(context.getConnection()).run();
    }
}
