package com.iitsm.domain.policy;

import com.iitsm.domain.model.CapacityDiscrepancy;
import com.iitsm.domain.model.CapacityDiscrepancy.Kind;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.Technician;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * OOP: pure domain service, an immutable result computed from technicians and tickets (no framework, no I/O).
 * Compares every technician's stored capacity slots with the tickets that name the technician as assignee (ADR-026).
 *
 * <p>A slot is held while the ticket is in a state with {@code IncidentStatus#holdsTechnicianSlot()}. By design
 * (ADR-016 point 9, F4) a linked duplicate rides its parent's technician without a slot, so an open ticket without a
 * slot is only a discrepancy when it is not linked to anything; linked ones are counted as <em>carried</em> load.
 *
 * <p>The caller supplies every ticket the slots may refer to: the open tickets plus any ticket named by a slot (a
 * slot whose ticket is missing from the input is reported as a phantom slot).
 */
public final class CapacityAudit {

    private static final Comparator<CapacityDiscrepancy> ORDER = Comparator
            .comparing(CapacityDiscrepancy::technicianId)
            .thenComparing(CapacityDiscrepancy::incidentId)
            .thenComparing(CapacityDiscrepancy::kind);

    private final List<CapacityDiscrepancy> discrepancies;
    private final Map<String, Integer> carriedDuplicates;

    private CapacityAudit(List<CapacityDiscrepancy> discrepancies, Map<String, Integer> carriedDuplicates) {
        // OOP (Encapsulation): the constructor wraps the list, so the audit result stays read-only.
        this.discrepancies = Collections.unmodifiableList(discrepancies);
        this.carriedDuplicates = Collections.unmodifiableMap(carriedDuplicates);
    }

    public static CapacityAudit of(Collection<Technician> technicians, Collection<Incident> incidents) {
        Objects.requireNonNull(technicians, "technicians cannot be null");
        Objects.requireNonNull(incidents, "incidents cannot be null");
        Map<String, Incident> byId = new HashMap<>();
        Map<String, List<Incident>> byAssignee = new HashMap<>();
        for (Incident incident : incidents) {
            byId.put(incident.getId(), incident);
            if (incident.getAssigneeId() != null) {
                byAssignee.computeIfAbsent(incident.getAssigneeId(), k -> new ArrayList<>()).add(incident);
            }
        }
        List<CapacityDiscrepancy> found = new ArrayList<>();
        Map<String, Integer> carried = new TreeMap<>();
        for (Technician technician : technicians) {
            found.addAll(phantomSlots(technician, byId));
            ticketsWithoutSlot(technician, byAssignee.getOrDefault(technician.getId(), List.of()), found, carried);
        }
        found.sort(ORDER);
        return new CapacityAudit(found, carried);
    }

    /** Every difference found, ordered by technician, ticket and kind. */
    public List<CapacityDiscrepancy> discrepancies() {
        return discrepancies;
    }

    /** Open linked tickets each technician carries without a slot (technicians that carry none are absent). */
    public Map<String, Integer> carriedDuplicatesByTechnician() {
        return carriedDuplicates;
    }

    public int carriedDuplicates(String technicianId) {
        return carriedDuplicates.getOrDefault(technicianId, 0);
    }

    private static List<CapacityDiscrepancy> phantomSlots(Technician technician, Map<String, Incident> byId) {
        List<CapacityDiscrepancy> phantoms = new ArrayList<>();
        for (String incidentId : technician.getActiveIncidentIds()) {
            String problem = slotProblem(technician, byId.get(incidentId));
            if (problem != null) {
                phantoms.add(new CapacityDiscrepancy(Kind.PHANTOM_SLOT, technician.getId(), incidentId, problem));
            }
        }
        return phantoms;
    }

    private static String slotProblem(Technician technician, Incident incident) {
        if (incident == null) {
            return "ticket does not exist";
        }
        if (!technician.getId().equals(incident.getAssigneeId())) {
            return incident.getAssigneeId() == null ? "ticket has no assignee" : "assignee is " + incident.getAssigneeId();
        }
        if (!incident.getStatus().holdsTechnicianSlot()) {
            return "status is " + incident.getStatus();
        }
        return null;
    }

    private static void ticketsWithoutSlot(
            Technician technician, List<Incident> assigned, List<CapacityDiscrepancy> found, Map<String, Integer> carried) {
        Set<String> slots = technician.getActiveIncidentIds();
        for (Incident incident : assigned) {
            if (!incident.getStatus().holdsTechnicianSlot() || slots.contains(incident.getId())) {
                continue;
            }
            Set<String> links = incident.getLinkedIncidentIds();
            if (links.isEmpty()) {
                found.add(new CapacityDiscrepancy(Kind.MISSING_SLOT, technician.getId(), incident.getId(), "no slot held"));
                continue;
            }
            carried.merge(technician.getId(), 1, Integer::sum);
            if (links.stream().noneMatch(slots::contains)) {
                found.add(new CapacityDiscrepancy(Kind.ORPHANED_DUPLICATE, technician.getId(), incident.getId(),
                        "linked to " + links + " but none holds a slot"));
            }
        }
    }
}
