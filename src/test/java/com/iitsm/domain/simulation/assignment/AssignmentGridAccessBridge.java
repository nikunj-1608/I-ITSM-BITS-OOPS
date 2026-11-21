package com.iitsm.domain.simulation.assignment;

import com.iitsm.domain.model.Incident;
import com.iitsm.domain.strategy.assignment.AssignmentGridAccess;

/** One ticket for tests of this package, taken from the public grid access of the assignment strategy tests. */
final class AssignmentGridAccessBridge {

    private AssignmentGridAccessBridge() {
    }

    static Incident ticket() {
        return AssignmentGridAccess.ticket();
    }
}
