package com.iitsm.infrastructure.persistence.migration;

import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;

/** The V9 scenarios on H2 in PostgreSQL mode (the dev and test database). */
class SuggestionVotesMigrationH2Test extends SuggestionVotesMigrationTestBase {

    private static final AtomicInteger DATABASES = new AtomicInteger();

    @Override
    DataSource newDatabase() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:suggestionvotes" + DATABASES.incrementAndGet() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        return ds;
    }
}
