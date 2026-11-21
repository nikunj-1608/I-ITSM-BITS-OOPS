package com.iitsm.web.dto;

import java.util.Map;

// OOP (Immutability): MttrReportDto is a record, so it is a read-only data carrier with no setters.
public record MttrReportDto(
        double overallMttrMinutes,
        double overallMttrespMinutes,
        Map<String, Double> mttrByCategoryMinutes,
        Map<String, Double> mttrByPriorityMinutes) {}

