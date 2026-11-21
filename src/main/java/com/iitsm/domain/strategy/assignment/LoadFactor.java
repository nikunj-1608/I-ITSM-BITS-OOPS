package com.iitsm.domain.strategy.assignment;

import com.iitsm.domain.model.Technician;
import java.util.Locale;

// OOP (final class + interface): LoadFactor is one small, immutable scoring part; the composite strategy combines factors it HAS (composition).
/**
 * Pattern: Strategy (an {@link AssignmentFactor}). How much free capacity the technician has (ADR-044):
 * {@code 1 - activeTickets / maxCapacity}, never below 0; 1 is completely free.
 */
public final class LoadFactor implements AssignmentFactor {

    public static final String KEY = "LOAD";

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String label() {
        return "Load";
    }

    @Override
    public Reading read(AssignmentContext context, Technician technician) {
        double raw = Math.max(0.0, 1.0 - technician.getLoadRatio());
        return new Reading(raw, activeTickets(technician));
    }

    /** "2/5 tickets active" for the technician. */
    static String activeTickets(Technician technician) {
        return String.format(Locale.ROOT, "%d/%d tickets active",
                technician.getActiveIncidentCount(), technician.getMaxCapacity());
    }
}
