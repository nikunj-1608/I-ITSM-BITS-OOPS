package com.iitsm.web.dto;

import com.iitsm.domain.model.EscalationRecord;
import java.util.List;
import java.util.Map;

// OOP (Immutability): EscalationReportDto is a record, so it is a read-only data carrier with no setters.
public record EscalationReportDto(
        long totalEscalations,
        Map<String, Long> escalationsByTier,
        List<EscalationRecord> recentEscalations) {}

