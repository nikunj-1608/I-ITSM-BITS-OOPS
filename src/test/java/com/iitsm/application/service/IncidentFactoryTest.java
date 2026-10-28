package com.iitsm.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.iitsm.domain.model.CategoryRegistry;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.repository.IncidentRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IncidentFactoryTest {

    private Clock clock;
    private CategoryRegistry categoryRegistry;
    private IncidentFactory factory;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-10-28T12:00:00Z"), ZoneId.of("UTC"));
        categoryRegistry = new CategoryRegistry();
        factory = new IncidentFactory(clock, categoryRegistry);
    }

    @Test
    @DisplayName("Generates ticket IDs in PREFIX-RANDOM_NUMBER format for specification domains")
    void testDomainSpecificTicketIdFormat() {
        // Network -> NET-xxxx
        Incident netInc = factory.createIncident("VPN tunnel drop", "Workstation cannot reach corporate VPN gateway", "EMP-0001", IncidentCategory.NETWORK);
        assertThat(netInc.getId()).matches("^NET-\\d{4,}$");

        // Software -> SOF-xxxx
        Incident sofInc = factory.createIncident("NPM build failure", "Webpack bundle syntax error in production pipeline", "EMP-0001", IncidentCategory.SOFTWARE);
        assertThat(sofInc.getId()).matches("^SOF-\\d{4,}$");

        // Hardware -> HWD-xxxx
        Incident hwdInc = factory.createIncident("Monitor flickers", "Thunderbolt dock video signal drops intermittently", "EMP-0001", IncidentCategory.HARDWARE);
        assertThat(hwdInc.getId()).matches("^HWD-\\d{4,}$");

        // Database -> DBS-xxxx
        Incident dbsInc = factory.createIncident("Deadlock on SQL pool", "Connection pool exhausted during night batch run", "EMP-0001", IncidentCategory.DATABASE);
        assertThat(dbsInc.getId()).matches("^DBS-\\d{4,}$");

        // Security -> SEC-xxxx
        Incident secInc = factory.createIncident("SSO lock on AD", "Active directory account locked after 5 password attempts", "EMP-0001", IncidentCategory.SECURITY);
        assertThat(secInc.getId()).matches("^SEC-\\d{4,}$");
    }

    @Test
    @DisplayName("Generates ticket IDs for all remaining supported domains")
    void testRemainingDomainsTicketIdFormat() {
        // Cloud -> CLD-xxxx
        Incident cldInc = factory.createIncident("Kubernetes pod crash", "Ingress controller pod in CrashLoopBackOff state", "EMP-0001", IncidentCategory.CLOUD);
        assertThat(cldInc.getId()).matches("^CLD-\\d{4,}$");

        // Authentication -> AUT-xxxx
        Incident autInc = factory.createIncident("MFA token expired", "Hardware RSA security token out of sync with provider", "EMP-0001", IncidentCategory.AUTHENTICATION);
        assertThat(autInc.getId()).matches("^AUT-\\d{4,}$");

        // Application -> APP-xxxx
        Incident appInc = factory.createIncident("Wiki indexing error", "Search returns HTTP 500 when filtering documents across wiki", "EMP-0001", IncidentCategory.APPLICATION);
        assertThat(appInc.getId()).matches("^APP-\\d{4,}$");

        // Storage -> STG-xxxx
        Incident stgInc = factory.createIncident("SAN volume offline", "NFS mount points unreachable from secondary app server", "EMP-0001", IncidentCategory.STORAGE);
        assertThat(stgInc.getId()).matches("^STG-\\d{4,}$");

        // Access -> ACC-xxxx
        Incident accInc = factory.createIncident("Shared folder access", "Requesting permission grant on executive finance shared drive", "EMP-0001", "ACCESS");
        assertThat(accInc.getId()).matches("^ACC-\\d{4,}$");

        // Unspecified / null -> GEN-xxxx
        Incident genInc = factory.createIncident("General query issue", "Need assistance with software catalog request workflow", "EMP-0001");
        assertThat(genInc.getId()).matches("^GEN-\\d{4,}$");
    }

    @Test
    @DisplayName("Generates unique IDs and handles duplicate collisions appropriately")
    void testDuplicateIdHandlingAndUniqueness() {
        Set<String> generated = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            Incident inc = factory.createIncident("Repeated ticket test " + i, "Detailed description for repeated incident ticket creation " + i, "EMP-0001", IncidentCategory.NETWORK);
            assertThat(inc.getId()).startsWith("NET-");
            assertThat(generated.add(inc.getId())).as("Ticket ID must be unique: " + inc.getId()).isTrue();
        }
    }

    @Test
    @DisplayName("Handles collision with existing repository ticket IDs appropriately")
    void testCollisionWithRepositoryAvoided() {
        IncidentRepository mockRepo = mock(IncidentRepository.class);
        IncidentFactory repoFactory = new IncidentFactory(clock, categoryRegistry, mockRepo);

        // Pre-reserve a specific ID
        String preReserved = "NET-5555";
        when(mockRepo.existsById(preReserved)).thenReturn(true);

        // Multiple generations should not return preReserved
        for (int i = 0; i < 100; i++) {
            String id = repoFactory.generateTicketId(IncidentCategory.NETWORK);
            assertThat(id).isNotEqualTo(preReserved);
        }
    }
}
