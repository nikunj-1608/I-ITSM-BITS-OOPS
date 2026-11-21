package com.iitsm.testsupport;

import com.iitsm.domain.model.CapacityDiscrepancy;
import com.iitsm.domain.policy.CapacityAudit;
import com.iitsm.domain.repository.IncidentRepository;
import com.iitsm.domain.repository.TechnicianRepository;
import java.util.List;

/**
 * Compares every technician's stored capacity slots with all stored tickets, using the production rule
 * ({@link CapacityAudit}, ADR-026): tests assert on the same definition of "consistent" that the admin
 * endpoint reports and repairs. Reports phantom slots, missing slots and orphaned duplicates.
 *
 * <p>The tests share one database and some of them delete slots directly, so assert on the <em>difference</em>
 * between two calls (new violations), not on an empty list.
 */
public final class CapacityInvariant {

    private CapacityInvariant() { }

    public static List<CapacityDiscrepancy> check(TechnicianRepository technicians, IncidentRepository incidents) {
        return CapacityAudit.of(technicians.findAll(), incidents.findAll()).discrepancies();
    }
}
