package com.iitsm.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.iitsm.domain.model.CapacityDiscrepancy;
import com.iitsm.domain.model.CapacityDiscrepancy.Kind;
import com.iitsm.domain.model.Email;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.model.IncidentStatus;
import com.iitsm.domain.model.Priority;
import com.iitsm.domain.model.PriorityDecision;
import com.iitsm.domain.model.Resolution;
import com.iitsm.domain.model.Skill;
import com.iitsm.domain.model.SlaPolicy;
import com.iitsm.domain.model.Technician;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CapacityAuditTest {

    private static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");

    private static Technician technician(String id, int maxCapacity) {
        return new Technician(id, "Tech " + id, new Email(id.toLowerCase() + "@test.com"), "hash",
                Set.of(new Skill("BGP", IncidentCategory.NETWORK)), 5, maxCapacity, "TEAM-NET");
    }

    private static Incident classified(String id) {
        Incident incident = new Incident(id, "VPN keeps dropping", "The VPN client drops every hour for all of us.", "EMP-1", NOW);
        PriorityDecision decision = new PriorityDecision(Priority.P3, 0.4, List.of(), List.of(), NOW, null, null);
        incident.classify(IncidentCategory.NETWORK, "SVC-VPN", decision, SlaPolicy.defaultFor(Priority.P3), NOW);
        return incident;
    }

    private static Incident assignedTo(String id, String technicianId) {
        Incident incident = classified(id);
        incident.assign(technicianId, NOW);
        return incident;
    }

    private static Incident resolvedBy(String id, String technicianId) {
        Incident incident = assignedTo(id, technicianId);
        incident.acknowledge(technicianId, NOW);
        incident.startInvestigation(technicianId, NOW);
        incident.resolve(new Resolution("cause", "steps", "prevention", "NETWORK", technicianId, NOW), NOW);
        return incident;
    }

    @Test
    @DisplayName("Only the states between assignment and resolution hold a slot")
    void slotHoldingStates() {
        assertThat(IncidentStatus.values()).filteredOn(IncidentStatus::holdsTechnicianSlot)
                .containsExactlyInAnyOrder(IncidentStatus.ASSIGNED, IncidentStatus.ACKNOWLEDGED,
                        IncidentStatus.IN_PROGRESS, IncidentStatus.PENDING_INFO);
    }

    @Test
    @DisplayName("A slot that matches an assigned open ticket is consistent")
    void consistentAssignmentIsClean() {
        Technician tech = technician("TECH-1", 3);
        tech.assignIncident("INC-1");

        CapacityAudit audit = CapacityAudit.of(List.of(tech), List.of(assignedTo("INC-1", "TECH-1")));

        assertThat(audit.discrepancies()).isEmpty();
        assertThat(audit.carriedDuplicatesByTechnician()).isEmpty();
    }

    @Test
    @DisplayName("A slot for a ticket that does not exist is a phantom slot")
    void slotForUnknownTicket() {
        Technician tech = technician("TECH-1", 3);
        tech.assignIncident("INC-GHOST");

        assertThat(CapacityAudit.of(List.of(tech), List.of()).discrepancies())
                .containsExactly(new CapacityDiscrepancy(Kind.PHANTOM_SLOT, "TECH-1", "INC-GHOST", "ticket does not exist"));
    }

    @Test
    @DisplayName("A slot for a ticket that names another technician, nobody or a resolved status is a phantom slot")
    void slotForTicketOfSomeoneElseOrNotOpen() {
        Technician tech = technician("TECH-1", 5);
        tech.assignIncident("INC-OTHER");
        tech.assignIncident("INC-NOBODY");
        tech.assignIncident("INC-DONE");
        List<Incident> tickets = List.of(
                assignedTo("INC-OTHER", "TECH-2"),
                classified("INC-NOBODY"),
                resolvedBy("INC-DONE", "TECH-1"));

        assertThat(CapacityAudit.of(List.of(tech), tickets).discrepancies())
                .extracting(CapacityDiscrepancy::incidentId, CapacityDiscrepancy::detail)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("INC-DONE", "status is RESOLVED"),
                        org.assertj.core.groups.Tuple.tuple("INC-NOBODY", "ticket has no assignee"),
                        org.assertj.core.groups.Tuple.tuple("INC-OTHER", "assignee is TECH-2"));
    }

    @Test
    @DisplayName("An open unlinked ticket naming a technician who holds no slot for it is a missing slot")
    void ticketWithoutSlot() {
        Technician tech = technician("TECH-1", 3);

        assertThat(CapacityAudit.of(List.of(tech), List.of(assignedTo("INC-1", "TECH-1"))).discrepancies())
                .containsExactly(new CapacityDiscrepancy(Kind.MISSING_SLOT, "TECH-1", "INC-1", "no slot held"));
    }

    @Test
    @DisplayName("A resolved ticket without a slot is fine: the slot is released on resolve")
    void resolvedTicketNeedsNoSlot() {
        Technician tech = technician("TECH-1", 3);

        assertThat(CapacityAudit.of(List.of(tech), List.of(resolvedBy("INC-1", "TECH-1"))).discrepancies()).isEmpty();
    }

    @Test
    @DisplayName("F4: a linked duplicate riding its parent's technician is carried load, not a discrepancy")
    void duplicateRidesItsParent() {
        Technician tech = technician("TECH-1", 3);
        tech.assignIncident("INC-PARENT");
        Incident parent = assignedTo("INC-PARENT", "TECH-1");
        Incident child = assignedTo("INC-CHILD", "TECH-1");
        parent.linkIncident("INC-CHILD");
        child.linkIncident("INC-PARENT");

        CapacityAudit audit = CapacityAudit.of(List.of(tech), List.of(parent, child));

        assertThat(audit.discrepancies()).isEmpty();
        assertThat(audit.carriedDuplicates("TECH-1")).isEqualTo(1);
        assertThat(audit.carriedDuplicates("TECH-2")).isZero();
    }

    @Test
    @DisplayName("A linked ticket whose cluster holds no slot of the technician is an orphaned duplicate")
    void orphanedDuplicate() {
        Technician tech = technician("TECH-1", 3);
        Incident child = assignedTo("INC-CHILD", "TECH-1");
        child.linkIncident("INC-PARENT");

        CapacityAudit audit = CapacityAudit.of(List.of(tech), List.of(child));

        assertThat(audit.discrepancies()).extracting(CapacityDiscrepancy::kind).containsExactly(Kind.ORPHANED_DUPLICATE);
        assertThat(audit.carriedDuplicates("TECH-1")).isEqualTo(1);
    }

    @Test
    @DisplayName("The result is ordered by technician, ticket and kind, and cannot be modified")
    void orderedAndImmutable() {
        Technician second = technician("TECH-2", 3);
        Technician first = technician("TECH-1", 3);
        second.assignIncident("INC-B");
        first.assignIncident("INC-Z");
        first.assignIncident("INC-A");

        CapacityAudit audit = CapacityAudit.of(List.of(second, first), List.of());

        assertThat(audit.discrepancies()).extracting(d -> d.technicianId() + "/" + d.incidentId())
                .containsExactly("TECH-1/INC-A", "TECH-1/INC-Z", "TECH-2/INC-B");
        assertThatThrownBy(() -> audit.discrepancies().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> audit.carriedDuplicatesByTechnician().put("X", 1)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("A phantom slot is repaired by releasing it, which also frees a technician marked busy")
    void repairReleasesAPhantomSlot() {
        Technician tech = technician("TECH-1", 1);
        tech.assignIncident("INC-GHOST");
        assertThat(tech.hasCapacity()).isFalse();
        CapacityDiscrepancy phantom = CapacityAudit.of(List.of(tech), List.of()).discrepancies().get(0);

        assertThat(phantom.repairOn(tech)).isTrue();

        assertThat(tech.getActiveIncidentIds()).isEmpty();
        assertThat(tech.hasCapacity()).isTrue();
    }

    @Test
    @DisplayName("A missing slot is repaired by taking it, but only when the technician has capacity")
    void repairTakesAMissingSlotWhenThereIsRoom() {
        Technician roomy = technician("TECH-1", 2);
        CapacityDiscrepancy missing = new CapacityDiscrepancy(Kind.MISSING_SLOT, "TECH-1", "INC-1", "no slot held");
        assertThat(missing.repairOn(roomy)).isTrue();
        assertThat(roomy.getActiveIncidentIds()).containsExactly("INC-1");

        Technician full = technician("TECH-1", 1);
        full.assignIncident("INC-OTHER");
        assertThat(missing.repairOn(full)).as("no room: left as it is").isFalse();
        assertThat(full.getActiveIncidentIds()).containsExactly("INC-OTHER");
    }

    @Test
    @DisplayName("An orphaned duplicate is never repaired, and a repair for another technician is refused")
    void orphansAndWrongTechnicianAreNotRepaired() {
        Technician tech = technician("TECH-1", 3);
        CapacityDiscrepancy orphan = new CapacityDiscrepancy(Kind.ORPHANED_DUPLICATE, "TECH-1", "INC-1", "linked");

        assertThat(orphan.repairOn(tech)).isFalse();
        assertThat(Kind.ORPHANED_DUPLICATE.isRepairable()).isFalse();
        assertThat(Kind.PHANTOM_SLOT.isRepairable()).isTrue();
        assertThatThrownBy(() -> orphan.repairOn(technician("TECH-2", 3))).isInstanceOf(IllegalArgumentException.class);
    }
}
