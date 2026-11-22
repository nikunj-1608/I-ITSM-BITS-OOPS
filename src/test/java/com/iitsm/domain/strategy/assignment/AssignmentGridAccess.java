package com.iitsm.domain.strategy.assignment;

import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.Technician;
import java.util.List;

/** Public access to the package-private {@link AssignmentGrid} for tests in other packages (ADR-044). */
public final class AssignmentGridAccess {

    private AssignmentGridAccess() {
    }

    /** A classified NETWORK P2 ticket of the grid. */
    public static Incident ticket() {
        return AssignmentGrid.incidents().get("NET_P2");
    }

    /** All eight grid technicians, in grid order. */
    public static List<Technician> allTechnicians() {
        return AssignmentGrid.candidateLists().get("ALL");
    }
}
