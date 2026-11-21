package db.migration;

import com.iitsm.domain.policy.RegistryDepartmentCodeResolver;
import com.iitsm.infrastructure.persistence.migration.LegacyTicketRenamer;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * Flyway Migration V4: rename tickets that still have the old random id ({@code INC-XXXXXXXX}) to
 * the department based format ({@code FIN-2026-000142}), carrying every reference along.
 * The logic lives in {@link LegacyTicketRenamer} so it can be tested on its own. Flyway requires
 * this class name and package (db.migration) to discover a Java migration.
 */
public class V4__rename_legacy_tickets extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        new LegacyTicketRenamer(context.getConnection(), new RegistryDepartmentCodeResolver()).run();
    }
}
