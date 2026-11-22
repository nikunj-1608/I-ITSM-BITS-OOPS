package com.iitsm.web.dto;

// OOP (Immutability): StrategyInfoDto is a record, so it is a read-only data carrier with no setters.
/**
 * DTO representing an assignment strategy and its active status.
 */
public record StrategyInfoDto(String name, String description, boolean active) {}

