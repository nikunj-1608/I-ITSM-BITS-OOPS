package com.iitsm.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.iitsm.domain.model.AdminLevel;
import com.iitsm.domain.model.Administrator;
import com.iitsm.domain.model.Designation;
import com.iitsm.domain.model.Email;
import com.iitsm.domain.model.Employee;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.policy.RegistryDepartmentCodeResolver;
import com.iitsm.domain.repository.TicketSequenceRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TicketNumberGeneratorTest {

    private static final Clock IN_2026 = Clock.fixed(Instant.parse("2026-12-31T23:59:59Z"), ZoneOffset.UTC);
    private static final Clock IN_2027 = Clock.fixed(Instant.parse("2027-01-01T00:00:00Z"), ZoneOffset.UTC);

    private final Map<String, Long> counters = new HashMap<>();
    private final TicketSequenceRepository inMemorySequences =
            (code, year) -> counters.merge(code + "|" + year, 1L, Long::sum);
    private final RegistryDepartmentCodeResolver resolver = new RegistryDepartmentCodeResolver();

    private Employee finance;
    private Employee engineering;
    private Administrator admin;

    @BeforeEach
    void setUp() {
        finance = new Employee("E1", "Alice", new Email("alice@company.com"), "hash",
                Designation.MANAGER, "Finance", 5);
        engineering = new Employee("E2", "Bob", new Email("bob@company.com"), "hash",
                Designation.ASSOCIATE, "Engineering", 2);
        admin = new Administrator("A1", "Root", new Email("root@company.com"), "hash",
                AdminLevel.SYSTEM_ADMIN);
    }

    private TicketNumberGenerator generator(Clock clock) {
        return new TicketNumberGenerator(resolver, inMemorySequences, clock);
    }

    @Test
    @DisplayName("numbers continue within a department and start independently per department")
    void sequencesAreIndependentPerDepartment() {
        TicketNumberGenerator generator = generator(IN_2026);

        assertThat(generator.nextFor(finance)).hasToString("FIN-2026-000001");
        assertThat(generator.nextFor(finance)).hasToString("FIN-2026-000002");
        assertThat(generator.nextFor(engineering)).hasToString("ENG-2026-000001");
        assertThat(generator.nextFor(finance)).hasToString("FIN-2026-000003");
    }

    @Test
    @DisplayName("the sequence restarts at 1 when the clock moves into a new year")
    void sequenceRestartsEachYear() {
        assertThat(generator(IN_2026).nextFor(finance)).hasToString("FIN-2026-000001");
        assertThat(generator(IN_2026).nextFor(finance)).hasToString("FIN-2026-000002");
        assertThat(generator(IN_2027).nextFor(finance)).hasToString("FIN-2027-000001");
        assertThat(generator(IN_2026).nextFor(finance)).hasToString("FIN-2026-000003");
    }

    @Test
    @DisplayName("a reporter without a department (not an employee) gets the MISC code")
    void reporterWithoutDepartmentGetsMisc() {
        assertThat(generator(IN_2026).nextFor(admin)).hasToString("MISC-2026-000001");
    }

    @Test
    @DisplayName("a null reporter is rejected")
    void nullReporterRejected() {
        assertThatThrownBy(() -> generator(IN_2026).nextFor(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("IncidentFactory uses the ticket number as the incident id")
    void factoryUsesTicketNumberAsId() {
        IncidentFactory factory = new IncidentFactory(IN_2026, generator(IN_2026));

        Incident first = factory.createIncident("VPN down", "VPN is down for everyone", finance);
        Incident second = factory.createIncident("VPN down again", "VPN is down once more", finance);

        assertThat(first.getId()).isEqualTo("FIN-2026-000001");
        assertThat(second.getId()).isEqualTo("FIN-2026-000002");
        assertThat(first.getReporterId()).isEqualTo("E1");
        assertThat(first.getCreatedAt()).isEqualTo(IN_2026.instant());
    }

    @Test
    @DisplayName("IncidentFactory rejects a null reporter")
    void factoryRejectsNullReporter() {
        IncidentFactory factory = new IncidentFactory(IN_2026, generator(IN_2026));
        assertThatThrownBy(() -> factory.createIncident("t", "d", null))
                .isInstanceOf(NullPointerException.class);
    }
}
