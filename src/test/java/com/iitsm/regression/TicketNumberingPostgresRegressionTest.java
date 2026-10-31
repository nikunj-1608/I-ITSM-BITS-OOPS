package com.iitsm.regression;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitsm.application.service.IncidentService;
import com.iitsm.domain.model.AuditRecord;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.TicketNumber;
import com.iitsm.domain.policy.DepartmentCodeResolver;
import com.iitsm.domain.repository.AuditRepository;
import com.iitsm.domain.repository.IncidentRepository;
import com.iitsm.domain.repository.UserRepository;
import com.iitsm.infrastructure.persistence.CompanySeeder;
import com.iitsm.infrastructure.persistence.migration.LegacyTicketRenamer;
import com.iitsm.testsupport.TestPostgres;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.sql.Connection;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.Year;
import java.time.ZoneOffset;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Runs the whole application on a real PostgreSQL (the production database): all Flyway migrations
 * apply, Hibernate validates the schema, new tickets get department based ids and are stored, and
 * old-style tickets are renamed without breaking the application.
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("regression")
class TicketNumberingPostgresRegressionTest {

    private static final String ENGINEERING_EMPLOYEE = "employee@nimbusfinserve.com";
    private static final String ENGINEERING_USER_ID = "EMP-DEMO-01";

    // Started when the class loads, before Spring bootstraps; starting it from inside the
    // @DynamicPropertySource hook never came up on this machine.
    private static final EmbeddedPostgres postgres = startPostgres();

    private static EmbeddedPostgres startPostgres() {
        try {
            return TestPostgres.start();
        } catch (IOException e) {
            throw new IllegalStateException("Could not start the embedded PostgreSQL", e);
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @AfterAll
    static void stopPostgres() throws IOException {
        TestPostgres.stop(postgres);
    }

    @Autowired
    private IncidentService incidentService;
    @Autowired
    private IncidentRepository incidentRepository;
    @Autowired
    private AuditRepository auditRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private CompanySeeder companySeeder;
    @Autowired
    private DepartmentCodeResolver departmentCodeResolver;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private Clock clock;

    @BeforeEach
    void seed() {
        if (userRepository.count() == 0) {
            companySeeder.seedCompanyData();
        }
    }

    @Test
    @DisplayName("PostgreSQL: a new ticket gets a department based id and is stored and readable")
    void newTicketIsStored() {
        assertThat(jdbc.queryForObject("SELECT version()", String.class)).startsWith("PostgreSQL");

        Incident created = incidentService.createIncident(
                ENGINEERING_EMPLOYEE, "VPN keeps dropping", "The VPN drops every few minutes for me today.");

        TicketNumber number = TicketNumber.parse(created.getId());
        assertThat(number.departmentCode()).isEqualTo("ENG");
        assertThat(number.year()).isEqualTo(Year.now(clock).getValue());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM incidents WHERE id = ?", Integer.class,
                created.getId())).isEqualTo(1);
        assertThat(incidentRepository.findById(created.getId())).isPresent();
        assertThat(auditRepository.findByIncidentIdOrderByTimestampAsc(created.getId())).isNotEmpty();
    }

    @Test
    @DisplayName("PostgreSQL: old tickets are renamed, stay fully readable, and new tickets continue after them")
    void legacyTicketsAreRenamedAndNewTicketsContinueAfterThem() throws Exception {
        OffsetDateTime thisYear = Year.now(clock).atDay(2).atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime lastYear = thisYear.minusYears(1);
        insertLegacyTicket("INC-CCCC0001", lastYear);
        insertLegacyTicket("INC-CCCC0002", thisYear);
        jdbc.update("INSERT INTO investigation_notes (id, incident_id, author_id, author_name, note_text, "
                + "created_at) VALUES ('NOTE-CCCC', 'INC-CCCC0002', ?, 'Demo', 'Looked at the logs', ?)",
                ENGINEERING_USER_ID, thisYear);
        jdbc.update("INSERT INTO audit_records (id, timestamp, actor_id, action, incident_id, description) "
                + "VALUES ('AUD-CCCC', ?, 'SYSTEM', 'INCIDENT_CREATED', 'INC-CCCC0002', 'Ticket INC-CCCC0002 created')",
                thisYear);

        try (Connection connection = dataSource.getConnection()) {
            assertThat(new LegacyTicketRenamer(connection, departmentCodeResolver).run()).isEqualTo(2);
        }

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM incidents WHERE id LIKE 'INC-%'", Integer.class))
                .isZero();
        String lastYearId = "ENG-" + (thisYear.getYear() - 1) + "-000001";
        assertThat(incidentRepository.findById(lastYearId)).isPresent();

        Incident renamed = incidentRepository.findById(
                jdbc.queryForObject("SELECT id FROM incidents WHERE reporter_id = ? AND created_at = ? "
                        + "AND id LIKE 'ENG-" + thisYear.getYear() + "-%'", String.class,
                        ENGINEERING_USER_ID, thisYear)).orElseThrow();
        assertThat(renamed.getNotes()).hasSize(1);
        List<AuditRecord> trail = auditRepository.findByIncidentIdOrderByTimestampAsc(renamed.getId());
        assertThat(trail).hasSize(1);
        assertThat(trail.getFirst().description()).isEqualTo("Ticket " + renamed.getId() + " created");

        Incident next = incidentService.createIncident(
                ENGINEERING_EMPLOYEE, "Printer offline again", "The office printer is offline again since today.");
        assertThat(TicketNumber.parse(next.getId()).sequence())
                .isEqualTo(TicketNumber.parse(renamed.getId()).sequence() + 1);
        assertThat(incidentRepository.findById(next.getId())).isPresent();
    }

    private void insertLegacyTicket(String id, OffsetDateTime createdAt) {
        jdbc.update("INSERT INTO incidents (id, title, description, reporter_id, created_at) VALUES (?, "
                + "'Old style ticket', 'Raised before ticket numbers existed, long enough to be valid', ?, ?)",
                id, ENGINEERING_USER_ID, createdAt);
    }
}
