package com.iitsm.infrastructure.persistence.migration;

import com.iitsm.testsupport.TestPostgres;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.sql.Connection;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;

/** The V5/V6 scenarios on a real PostgreSQL (the production database), started in-process. */
@Tag("postgres")
class OverrideDeadlineRepairPostgresTest extends OverrideDeadlineRepairTestBase {

    private static final AtomicInteger DATABASES = new AtomicInteger();
    private static EmbeddedPostgres postgres;

    @BeforeAll
    static void startPostgres() throws IOException {
        postgres = TestPostgres.start();
    }

    @AfterAll
    static void stopPostgres() throws IOException {
        TestPostgres.stop(postgres);
    }

    @Override
    DataSource newDatabase() throws Exception {
        String name = "overriderepair_" + DATABASES.incrementAndGet();
        try (Connection c = postgres.getPostgresDatabase().getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE DATABASE " + name);
        }
        return postgres.getDatabase("postgres", name);
    }
}
