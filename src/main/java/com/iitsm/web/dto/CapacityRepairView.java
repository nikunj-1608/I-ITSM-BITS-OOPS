package com.iitsm.web.dto;

import com.iitsm.application.service.CapacityReconciliationService;
import java.util.List;

// OOP (Immutability): CapacityRepairView is a record, so it is a read-only data carrier with no setters.
/** Result of a capacity repair: what was fixed and what was left (the detail says why). */
public record CapacityRepairView(
        int repairedCount,
        List<CapacityDiscrepancyView> repaired,
        int notRepairedCount,
        List<CapacityDiscrepancyView> notRepaired) {

    public static CapacityRepairView fromDomain(CapacityReconciliationService.RepairResult result) {
        List<CapacityDiscrepancyView> fixed = result.repaired().stream().map(CapacityDiscrepancyView::fromDomain).toList();
        List<CapacityDiscrepancyView> left = result.notRepaired().stream().map(CapacityDiscrepancyView::fromDomain).toList();
        return new CapacityRepairView(fixed.size(), fixed, left.size(), left);
    }
}
