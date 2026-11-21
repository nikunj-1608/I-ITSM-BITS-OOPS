package com.iitsm.domain.model;

import java.util.Objects;

/**
 * OOP: immutable value object. One difference between a technician's stored capacity slots and the tickets that name
 * the technician as assignee (ADR-026). Each {@link Kind} knows how (and whether) it can be repaired, so callers never
 * switch on the kind.
 *
 * @param kind         what kind of difference it is
 * @param technicianId the technician whose slots are affected
 * @param incidentId   the ticket involved
 * @param detail       a short human readable reason
 */
public record CapacityDiscrepancy(Kind kind, String technicianId, String incidentId, String detail) {

    public enum Kind {
        /** A slot for a ticket that does not exist, is not open or names another technician: a capacity leak. */
        PHANTOM_SLOT(true) {
            @Override
            boolean repair(Technician technician, String incidentId) {
                technician.releaseIncident(incidentId);
                return true;
            }
        },
        /** An open, unlinked ticket naming the technician that holds no slot: unrecorded load. */
        MISSING_SLOT(true) {
            @Override
            boolean repair(Technician technician, String incidentId) {
                if (!technician.hasCapacity()) {
                    return false;
                }
                technician.assignIncident(incidentId);
                return true;
            }
        },
        /**
         * An open linked ticket whose cluster holds no slot of the technician (the parent was resolved first). Linked
         * duplicates ride their parent's technician without a slot by design (ADR-016, F4), so this is reported but
         * never repaired.
         */
        ORPHANED_DUPLICATE(false) {
            @Override
            boolean repair(Technician technician, String incidentId) {
                return false;
            }
        };

        private final boolean repairable;

        Kind(boolean repairable) {
            this.repairable = repairable;
        }

        /** False for differences that exist by design and are only reported. */
        public boolean isRepairable() {
            return repairable;
        }

        abstract boolean repair(Technician technician, String incidentId);
    }

    public CapacityDiscrepancy {
        Objects.requireNonNull(kind, "kind cannot be null");
        Objects.requireNonNull(technicianId, "technicianId cannot be null");
        Objects.requireNonNull(incidentId, "incidentId cannot be null");
        Objects.requireNonNull(detail, "detail cannot be null");
    }

    /**
     * Changes the technician's slots so they match the ticket. Returns false (and changes nothing) when it cannot be
     * repaired: the difference exists by design, or the technician has no free capacity for the missing slot.
     */
    public boolean repairOn(Technician technician) {
        Objects.requireNonNull(technician, "technician cannot be null");
        if (!technician.getId().equals(technicianId)) {
            throw new IllegalArgumentException("Discrepancy belongs to technician " + technicianId + ", not " + technician.getId());
        }
        return kind.repair(technician, incidentId);
    }

    /** The same difference with a different explanation (used to say why a repair was not made). */
    public CapacityDiscrepancy withDetail(String newDetail) {
        return new CapacityDiscrepancy(kind, technicianId, incidentId, newDetail);
    }
}
