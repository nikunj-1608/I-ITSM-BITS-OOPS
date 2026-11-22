package com.iitsm.regression;

import static org.assertj.core.api.Assertions.assertThat;

import com.iitsm.application.service.IncidentService;
import com.iitsm.application.service.UnassignedIncidentDispatcher;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.IncidentStatus;
import com.iitsm.domain.model.Technician;
import com.iitsm.domain.repository.AuditRepository;
import com.iitsm.domain.repository.IncidentRepository;
import com.iitsm.domain.repository.TechnicianRepository;
import com.iitsm.domain.repository.UserRepository;
import com.iitsm.domain.strategy.classification.IncidentClassifier;
import com.iitsm.infrastructure.persistence.CompanySeeder;
import java.util.HashSet;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * End-to-end guard for ADR-009 applied to duplicates: the classification-confidence gate must run for every new
 * ticket, including one that is linked to a parent as a duplicate. Humans own low-confidence tickets, so a vague
 * duplicate must be flagged for triage and must never be handed to a technician, either by inheriting the parent's
 * assignee or by the dispatcher.
 *
 * <p>Regression for: duplicates skipped the gate (audit finding H2), were saved with needsTriage=false and were
 * then auto-assigned by the dispatcher.
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("regression")
class DuplicateTriageGateRegressionTest {

    private static final String EMPLOYEE = "employee@nimbusfinserve.com";

    @Value("${iitsm.classification.min-confidence:0.60}")
    private double minConfidence;

    @Autowired private IncidentService incidentService;
    @Autowired private UnassignedIncidentDispatcher dispatcher;
    @Autowired private IncidentClassifier classifier;
    @Autowired private IncidentRepository incidentRepository;
    @Autowired private AuditRepository auditRepository;
    @Autowired private TechnicianRepository technicianRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private CompanySeeder companySeeder;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM technician_active_incidents");
        if (userRepository.count() == 0) {
            companySeeder.seedCompanyData();
        }
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("DELETE FROM technician_active_incidents");
    }

    /** A letters-only token the vectoriser has never seen, so each test builds its own cluster. */
    private static String marker() {
        Random random = new Random(System.nanoTime());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            sb.append((char) ('a' + random.nextInt(26)));
        }
        return sb.toString();
    }

    private double confidenceOf(String title, String description) {
        return classifier.classify(title, description).confidence();
    }

    private static String vagueTitle(String filler, String marker) {
        return "Something " + filler + " happened " + marker;
    }

    /**
     * One domain keyword, only in the description: the classifier caps this at triage confidence. The keyword is "router",
     * which no IT service lists: since ADR-052 a keyword that a matched service of the same team corroborates (for
     * example "vpn" and the Corporate VPN service) is lifted to the gate and is no longer a low-confidence ticket. The
     * filler words differ per test so tests left open in the shared database never cluster with each other: apart from
     * the keyword the text is the filler and the marker only.
     */
    private static String vagueDescription(String filler, String marker) {
        return filler + " " + marker + " router " + marker + " " + filler;
    }

    /** The clear router ticket's wording with the keyword moved out of the title: similar text, triage confidence. */
    private static String overlappingVagueDescription(String marker) {
        return "The router keeps dropping again today and I really cannot work like this " + marker + " " + marker;
    }

    /** A single keyword in the title, which the classifier accepts (0.55 plus Naive Bayes agreement); no service matches. */
    private static String clearRouterTitle(String marker) {
        return "Router keeps dropping again " + marker;
    }

    private static String clearRouterDescription(String marker) {
        return "It keeps dropping again today and I really cannot work like this " + marker + " " + marker;
    }

    /** A single keyword in the title, which the classifier accepts (0.55 plus Naive Bayes agreement). */
    private static String clearVpnTitle(String marker) {
        return "VPN keeps dropping again " + marker;
    }

    private static String clearVpnDescription(String marker) {
        return "It keeps dropping again today and I really cannot work like this " + marker + " " + marker;
    }

    private static String clearLaptopTitle(String marker) {
        return "Laptop screen flickering and keyboard unresponsive " + marker;
    }

    private static String clearLaptopDescription(String marker) {
        return "The laptop display flickers constantly and the keyboard stopped responding after the docking station update " + marker + " " + marker;
    }

    @Test
    @DisplayName("A low-confidence duplicate of a triage parent is linked but flagged for triage, unassigned")
    void lowConfidenceDuplicateOfTriageParentGoesToTriage() {
        String marker = marker();
        assertThat(confidenceOf(vagueTitle("strange", marker), vagueDescription("strange", marker)))
                .as("precondition: the text must be below the gate").isLessThan(minConfidence);

        Incident parent = incidentService.createIncident(EMPLOYEE, vagueTitle("strange", marker), vagueDescription("strange", marker));
        assertThat(parent.isNeedsTriage()).as("precondition: parent is in triage").isTrue();

        Incident child = incidentService.createIncident(EMPLOYEE, vagueTitle("strange", marker), vagueDescription("strange", marker));
        assertThat(child.getLinkedIncidentIds()).as("precondition: it really is a duplicate").contains(parent.getId());

        assertThat(child.isNeedsTriage()).isTrue();
        assertThat(child.getAssigneeId()).isNull();
        assertThat(child.getStatus()).isEqualTo(IncidentStatus.CLASSIFIED);

        dispatcher.dispatchWaitingIncidents();
        Incident reloaded = incidentRepository.findById(child.getId()).orElseThrow();
        assertThat(reloaded.getAssigneeId()).as("dispatcher must skip a triage ticket").isNull();
        assertThat(reloaded.isNeedsTriage()).isTrue();
    }

    @Test
    @DisplayName("A confident duplicate of an assigned parent still rides the parent's technician (F4 unchanged)")
    void confidentDuplicateOfAssignedParentInheritsAssignee() {
        String marker = marker();
        assertThat(confidenceOf(clearVpnTitle(marker), clearVpnDescription(marker)))
                .as("precondition: the text must pass the gate").isGreaterThanOrEqualTo(minConfidence);

        Incident parent = incidentService.createIncident(EMPLOYEE, clearVpnTitle(marker), clearVpnDescription(marker));
        assertThat(parent.getAssigneeId()).as("precondition: parent assigned").isNotNull();

        Incident child = incidentService.createIncident(EMPLOYEE, clearVpnTitle(marker), clearVpnDescription(marker));

        assertThat(child.getLinkedIncidentIds()).contains(parent.getId());
        assertThat(child.isNeedsTriage()).isFalse();
        assertThat(child.getAssigneeId()).isEqualTo(parent.getAssigneeId());
    }

    @Test
    @DisplayName("A low-confidence duplicate of an assigned parent goes to triage and does not inherit the technician")
    void lowConfidenceDuplicateOfAssignedParentGoesToTriage() {
        String marker = marker();
        assertThat(confidenceOf(vagueTitle("odd", marker), overlappingVagueDescription(marker)))
                .as("precondition: the duplicate text must be below the gate").isLessThan(minConfidence);

        Incident parent = incidentService.createIncident(EMPLOYEE, clearRouterTitle(marker), clearRouterDescription(marker));
        assertThat(parent.getAssigneeId()).as("precondition: parent assigned").isNotNull();

        Incident child = incidentService.createIncident(EMPLOYEE, vagueTitle("odd", marker), overlappingVagueDescription(marker));
        assertThat(child.getLinkedIncidentIds()).as("precondition: it really is a duplicate").contains(parent.getId());

        assertThat(child.isNeedsTriage()).isTrue();
        assertThat(child.getAssigneeId()).isNull();
        assertThat(child.getStatus()).isEqualTo(IncidentStatus.CLASSIFIED);
    }

    @Test
    @DisplayName("Triaging a duplicate keeps the linking: the parent cluster is still updated and audited")
    void lowConfidenceDuplicateStillUpdatesParentCluster() {
        String marker = marker();
        Incident parent = incidentService.createIncident(EMPLOYEE, vagueTitle("unusual", marker), vagueDescription("unusual", marker));
        int auditBefore = auditRepository.findByIncidentIdOrderByTimestampAsc(parent.getId()).size();

        Incident child = incidentService.createIncident(EMPLOYEE, vagueTitle("unusual", marker), vagueDescription("unusual", marker));
        assertThat(child.getLinkedIncidentIds()).as("precondition: it really is a duplicate").contains(parent.getId());

        Incident reloadedParent = incidentRepository.findById(parent.getId()).orElseThrow();
        assertThat(reloadedParent.getLinkedIncidentIds()).contains(child.getId());
        assertThat(auditRepository.findByIncidentIdOrderByTimestampAsc(parent.getId()).size())
                .as("the parent's priority is recalculated and audited when a duplicate joins")
                .isGreaterThan(auditBefore);
    }

    @Test
    @DisplayName("A confident duplicate of a waiting (unassigned, non-triage) parent is not flagged for triage")
    void confidentDuplicateOfWaitingParentIsNotTriaged() {
        fillAllTechnicians();
        String marker = marker();
        assertThat(confidenceOf(clearLaptopTitle(marker), clearLaptopDescription(marker)))
                .as("precondition: the text must pass the gate").isGreaterThanOrEqualTo(minConfidence);
        Incident parent = incidentService.createIncident(EMPLOYEE, clearLaptopTitle(marker), clearLaptopDescription(marker));
        assertThat(parent.getLinkedIncidentIds()).as("precondition: parent is not a duplicate of another test's ticket").isEmpty();
        assertThat(parent.getAssigneeId()).as("precondition: no capacity, parent waits").isNull();
        assertThat(parent.isNeedsTriage()).isFalse();

        Incident child = incidentService.createIncident(EMPLOYEE, clearLaptopTitle(marker), clearLaptopDescription(marker));

        assertThat(child.getLinkedIncidentIds()).contains(parent.getId());
        assertThat(child.isNeedsTriage()).isFalse();
    }

    private void fillAllTechnicians() {
        for (Technician technician : technicianRepository.findAll()) {
            new HashSet<>(technician.getActiveIncidentIds()).forEach(technician::releaseIncident);
            for (int slot = 0; slot < technician.getMaxCapacity(); slot++) {
                technician.assignIncident("DUMMY-SLOT-" + slot);
            }
            technicianRepository.save(technician);
        }
    }
}
