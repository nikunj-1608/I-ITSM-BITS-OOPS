package com.iitsm.application.service;

import com.iitsm.domain.model.CapacityDiscrepancy;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.Technician;
import com.iitsm.domain.policy.CapacityAudit;
import com.iitsm.domain.repository.IncidentRepository;
import com.iitsm.domain.repository.TechnicianRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Checks and repairs the technicians' capacity slots (ADR-026): every stored slot must belong to an open ticket that
 * names the technician, and every open unlinked ticket must hold its slot. The check reads technicians and tickets in
 * one consistent snapshot (REPEATABLE_READ); without it, a ticket resolved between the two reads would look like a
 * missing slot and a repair would create a leak instead of removing one.
 *
 * <p>Repairing is an explicit administrator action (never automatic, so the cause of a leak stays visible): a phantom
 * slot is released, a missing slot is added when the technician has capacity, and orphaned duplicates are only
 * reported (linked duplicates carry no slot by design, F4). A technician changed by someone else while the repair runs
 * makes it fail with a concurrent-update conflict (HTTP 409) and nothing is changed.
 */
@Service
public class CapacityReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(CapacityReconciliationService.class);

    /** What a repair run did: the differences it fixed and those it left (with the reason in the detail). */
    public record RepairResult(List<CapacityDiscrepancy> repaired, List<CapacityDiscrepancy> notRepaired) {}

    private final TechnicianRepository technicianRepository;
    private final IncidentRepository incidentRepository;

    public CapacityReconciliationService(TechnicianRepository technicianRepository, IncidentRepository incidentRepository) {
        this.technicianRepository = Objects.requireNonNull(technicianRepository, "technicianRepository cannot be null");
        this.incidentRepository = Objects.requireNonNull(incidentRepository, "incidentRepository cannot be null");
    }

    /** The differences between stored slots and tickets right now (read only). */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CapacityAudit audit() {
        return snapshot(technicianRepository.findAll());
    }

    /** Open linked tickets each technician carries without a slot (technicians that carry none are absent). */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Integer> carriedDuplicatesByTechnician() {
        return snapshot(technicianRepository.findAll()).carriedDuplicatesByTechnician();
    }

    /** Fixes what can be fixed in one transaction and reports the rest. */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public RepairResult repair() {
        List<Technician> technicians = technicianRepository.findAll();
        Map<String, Technician> byId = technicians.stream().collect(Collectors.toMap(Technician::getId, t -> t));
        Map<String, List<CapacityDiscrepancy>> byTechnician = new LinkedHashMap<>();
        snapshot(technicians).discrepancies().forEach(d ->
                byTechnician.computeIfAbsent(d.technicianId(), k -> new ArrayList<>()).add(d));

        List<CapacityDiscrepancy> repaired = new ArrayList<>();
        List<CapacityDiscrepancy> notRepaired = new ArrayList<>();
        byTechnician.forEach((technicianId, differences) -> {
            Technician technician = byId.get(technicianId);
            boolean changed = false;
            for (CapacityDiscrepancy difference : differences) {
                if (difference.repairOn(technician)) {
                    repaired.add(difference);
                    changed = true;
                } else {
                    notRepaired.add(difference.withDetail(difference.detail() + " (not repaired: " + reasonNotRepaired(difference) + ")"));
                }
            }
            if (changed) {
                technicianRepository.save(technician);
            }
        });
        repaired.forEach(d -> log.warn("Capacity repair: {} for technician {}, ticket {} ({})",
                d.kind(), d.technicianId(), d.incidentId(), d.detail()));
        return new RepairResult(repaired, notRepaired);
    }

    private static String reasonNotRepaired(CapacityDiscrepancy difference) {
        return difference.kind().isRepairable()
                ? "the technician has no free capacity"
                : "linked duplicates carry no slot by design";
    }

    /** Open tickets plus any ticket a slot refers to (a slot whose ticket cannot be found is a phantom slot). */
    private CapacityAudit snapshot(List<Technician> technicians) {
        List<Incident> tickets = new ArrayList<>(incidentRepository.findActiveIncidents());
        Set<String> known = tickets.stream().map(Incident::getId).collect(Collectors.toSet());
        for (Technician technician : technicians) {
            for (String incidentId : technician.getActiveIncidentIds()) {
                if (known.add(incidentId)) {
                    incidentRepository.findById(incidentId).ifPresent(tickets::add);
                }
            }
        }
        return CapacityAudit.of(technicians, tickets);
    }
}
