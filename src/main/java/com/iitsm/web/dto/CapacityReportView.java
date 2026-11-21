package com.iitsm.web.dto;

import com.iitsm.domain.policy.CapacityAudit;
import java.util.List;
import java.util.Map;

/**
 * Result of the capacity check: the differences between stored technician slots and tickets, and how many linked
 * duplicates each technician carries without a slot (by design, F4).
 */
public record CapacityReportView(
        int discrepancyCount,
        List<CapacityDiscrepancyView> discrepancies,
        Map<String, Integer> carriedDuplicatesByTechnician) {

    public static CapacityReportView fromDomain(CapacityAudit audit) {
        List<CapacityDiscrepancyView> items = audit.discrepancies().stream().map(CapacityDiscrepancyView::fromDomain).toList();
        return new CapacityReportView(items.size(), items, audit.carriedDuplicatesByTechnician());
    }
}
