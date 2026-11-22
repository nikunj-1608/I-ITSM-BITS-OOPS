package com.iitsm.web.dto;

import com.iitsm.domain.model.CapacityDiscrepancy;

// OOP (Immutability): CapacityDiscrepancyView is a record, so it is a read-only data carrier with no setters.
/**
 * One difference between a technician's stored capacity slots and the tickets that name the technician.
 *
 * @param kind       PHANTOM_SLOT, MISSING_SLOT or ORPHANED_DUPLICATE
 * @param repairable false for differences that exist by design and are only reported
 */
public record CapacityDiscrepancyView(String kind, String technicianId, String incidentId, String detail, boolean repairable) {

    public static CapacityDiscrepancyView fromDomain(CapacityDiscrepancy d) {
        return new CapacityDiscrepancyView(d.kind().name(), d.technicianId(), d.incidentId(), d.detail(), d.kind().isRepairable());
    }
}
