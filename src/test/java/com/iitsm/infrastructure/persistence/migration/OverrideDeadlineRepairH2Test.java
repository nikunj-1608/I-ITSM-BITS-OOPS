package com.iitsm.infrastructure.persistence.migration;

import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;

/** The V5/V6 scenarios on H2 in PostgreSQL mode (the dev and test database). */
class OverrideDeadlineRepairH2Test extends OverrideDeadlineRepairTestBase {

    private static final AtomicInteger DATABASES = new AtomicInteger();

    @Override
    DataSource newDatabase() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:overriderepair" + DATABASES.incrementAndGet() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        return ds;
    }
}
