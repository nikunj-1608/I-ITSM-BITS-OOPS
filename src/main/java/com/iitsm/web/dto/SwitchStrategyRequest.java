package com.iitsm.web.dto;

import jakarta.validation.constraints.NotBlank;

// OOP (Immutability): SwitchStrategyRequest is a record, so it is a read-only data carrier with no setters.
/**
 * Request payload to switch the active assignment strategy.
 */
public record SwitchStrategyRequest(
        @NotBlank(message = "Strategy name must not be blank")
        String strategyName
) {}

