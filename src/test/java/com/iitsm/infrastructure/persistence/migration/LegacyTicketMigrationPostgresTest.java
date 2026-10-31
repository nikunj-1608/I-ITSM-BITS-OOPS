package com.iitsm.infrastructure.persistence.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitsm.domain.policy.RegistryDepartmentCodeResolver;
import com.iitsm.testsupport.TestPostgres;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.sql.Connection;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The V4 migration scenarios on a real PostgreSQL (the production database), started in-process by
 * the embedded-postgres test library: no Docker or local install needed.
 */
class LegacyTicketMigrationPostgresTest extends LegacyTicketMigrationTestBase {

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
        String name = "legacy_" + DATABASES.incrementAndGet();
        try (Connection c = postgres.getPostgresDatabase().getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE DATABASE " + name);
        }
        return postgres.getDatabase("postgres", name);
    }

    @Test
    @DisplayName("PostgreSQL: if the rename fails half way, everything is rolled back and nothing is lost")
    void rollbackLeavesTheDatabaseUntouched() throws Exception {
        DataSource ds = migratedToV3();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        seedLegacyTickets(jdbc);

        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            assertThat(new LegacyTicketRenamer(c, new RegistryDepartmentCodeResolver()).run()).isEqualTo(5);
            c.rollback();
        }

        assertThat(incidentIds(jdbc)).containsExactly("INC-AAAA0001", "INC-AAAA0002", "INC-AAAA0003",
                "INC-AAAA0004", "INC-AAAA0005");
        assertThat(count(jdbc, "investigation_notes", "incident_id", "INC-AAAA0002")).isEqualTo(1);
        assertThat(count(jdbc, "audit_records", "incident_id", "INC-AAAA0002")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_sequences", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT message FROM notifications WHERE id = 'N-1'", String.class))
                .isEqualTo("Ticket 'INC-AAAA0002' has been created");
    }
}
