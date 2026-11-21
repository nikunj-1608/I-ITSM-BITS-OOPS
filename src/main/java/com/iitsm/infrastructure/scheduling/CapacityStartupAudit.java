package com.iitsm.infrastructure.scheduling;

import com.iitsm.application.service.CapacityReconciliationService;
import com.iitsm.domain.model.CapacityDiscrepancy;
import com.iitsm.domain.policy.CapacityAudit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/**
 * Logs one line at start-up saying whether the technicians' stored capacity slots match the tickets (ADR-026), so a
 * leak that happened while the application was down or before this fix is noticed. It only reports: repairing is an
 * explicit administrator action ({@code POST /api/admin/capacity/reconcile}). A failure here is logged and never
 * stops the application from starting. Switched off with {@code iitsm.capacity.startup-check=false} (the test profile,
 * where tests damage slots on purpose in one shared database).
 */
@Component
@ConditionalOnProperty(name = "iitsm.capacity.startup-check", havingValue = "true", matchIfMissing = true)
public class CapacityStartupAudit implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(CapacityStartupAudit.class);

    private final CapacityReconciliationService reconciliationService;

    public CapacityStartupAudit(CapacityReconciliationService reconciliationService) {
        this.reconciliationService = Objects.requireNonNull(reconciliationService, "reconciliationService cannot be null");
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        try {
            report(reconciliationService.audit());
        } catch (RuntimeException failure) {
            log.error("Capacity check at start-up failed; the application keeps running", failure);
        }
    }

    private void report(CapacityAudit audit) {
        List<CapacityDiscrepancy> differences = audit.discrepancies();
        if (differences.isEmpty()) {
            log.info("Capacity check: technician slots match the open tickets ({} technician(s) carry linked duplicates)",
                    audit.carriedDuplicatesByTechnician().size());
            return;
        }
        Map<CapacityDiscrepancy.Kind, Long> byKind = differences.stream()
                .collect(Collectors.groupingBy(CapacityDiscrepancy::kind, TreeMap::new, Collectors.counting()));
        log.warn("Capacity check: {} difference(s) between technician slots and tickets {}; "
                + "GET /api/admin/capacity/reconcile lists them, POST repairs them", differences.size(), byKind);
    }
}
