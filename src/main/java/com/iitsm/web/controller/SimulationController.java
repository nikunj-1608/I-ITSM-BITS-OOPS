package com.iitsm.web.controller;

import com.iitsm.application.service.StrategyComparisonService;
import com.iitsm.simulation.SimulationResult;
import com.iitsm.simulation.SimulationService;
import com.iitsm.simulation.SimulationStatusDto;
import com.iitsm.web.dto.StrategyComparisonRequest;
import com.iitsm.web.dto.StrategyComparisonResponse;
import com.iitsm.web.mapper.StrategyComparisonMapper;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// OOP (Single responsibility): SimulationController validates input, calls one application service and maps the result to a DTO; no business rule lives here.
/**
 * REST controller providing administrative controls for the time-accelerated simulation engine
 * and demonstration scenario execution (S1–S5).
 */
@RestController
@RequestMapping("/api/simulation")
public class SimulationController {

    private final SimulationService simulationService;
    private final StrategyComparisonService strategyComparisonService;

    // OOP (Dependency Injection): services arrive through the constructor, so the controller depends on classes it is given, not ones it creates.
    public SimulationController(SimulationService simulationService, StrategyComparisonService strategyComparisonService) {
        this.simulationService = Objects.requireNonNull(simulationService, "simulationService cannot be null");
        this.strategyComparisonService = Objects.requireNonNull(strategyComparisonService, "strategyComparisonService cannot be null");
    }

    @GetMapping("/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SimulationStatusDto> getStatus() {
        return ResponseEntity.ok(simulationService.getClockStatus());
    }

    @PostMapping("/speed")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SimulationStatusDto> setSpeed(@RequestParam(name = "multiplier", defaultValue = "1") long multiplier) {
        simulationService.setSpeedMultiplier(multiplier);
        return ResponseEntity.ok(simulationService.getClockStatus());
    }

    @PostMapping("/advance")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SimulationStatusDto> advanceTime(@RequestParam(name = "minutes", defaultValue = "15") long minutes) {
        simulationService.advanceClockByMinutes(minutes);
        return ResponseEntity.ok(simulationService.getClockStatus());
    }

    @PostMapping("/reset")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SimulationStatusDto> resetClock() {
        simulationService.resetClock();
        return ResponseEntity.ok(simulationService.getClockStatus());
    }

    @PostMapping("/run/{scenarioId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SimulationResult> runScenario(@PathVariable("scenarioId") String scenarioId) {
        SimulationResult result = simulationService.runScenario(scenarioId);
        return ResponseEntity.ok(result);
    }

    /**
     * Offline comparison of the assignment strategies on a seeded synthetic ticket stream (ADR-044, Phase L3). Read-only
     * and idempotent: it writes nothing, publishes nothing and never touches the live clock or the live strategies. The
     * body and every field of it are optional.
     */
    @PostMapping("/strategy-comparison")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<StrategyComparisonResponse> compareStrategies(
            @RequestBody(required = false) StrategyComparisonRequest request) {
        StrategyComparisonRequest body = (request != null) ? request : new StrategyComparisonRequest(null, null, null, null, null);
        return ResponseEntity.ok(StrategyComparisonMapper.toResponse(strategyComparisonService.compare(
                body.seed(), body.ticketCount(), body.replications(), body.arrivalsPerHour(), body.strategies())));
    }
}

