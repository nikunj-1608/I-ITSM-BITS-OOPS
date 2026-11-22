package com.iitsm.web.controller;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// OOP (Single responsibility): PublicConfigController validates input, calls one application service and maps the result to a DTO; no business rule lives here.
/**
 * Public configuration endpoint for client applications.
 * Allows client UI to determine if demo mode is enabled without requiring authentication.
 */
@RestController
@RequestMapping("/api/public")
public class PublicConfigController {

    private final boolean demoMode;
    private final String defaultDomain;

    // OOP (Dependency Injection): services arrive through the constructor, so the controller depends on classes it is given, not ones it creates.
    public PublicConfigController(
            @Value("${iitsm.demo-mode:true}") boolean demoMode,
            @Value("${iitsm.demo.domain:nimbusfinserve.com}") String defaultDomain) {
        this.demoMode = demoMode;
        this.defaultDomain = defaultDomain;
    }

    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getPublicConfig() {
        return ResponseEntity.ok(Map.of(
                "demoMode", demoMode,
                "defaultDomain", defaultDomain,
                "appName", "Swiftdesk :: Nimbus FinServe",
                "version", "1.0.0"
        ));
    }
}

