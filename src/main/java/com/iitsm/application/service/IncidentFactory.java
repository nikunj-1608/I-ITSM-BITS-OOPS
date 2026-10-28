package com.iitsm.application.service;

import com.iitsm.domain.model.CategoryRegistry;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.model.IncidentCategory;
import com.iitsm.domain.repository.IncidentRepository;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * Pattern: Factory.
 * Constructs new Incident aggregate roots with domain-specific unique ticket IDs
 * (format: PREFIX-RANDOM_NUMBER), enforcing validation and temporal consistency via Clock.
 */
@Component
public class IncidentFactory {

    private final Clock clock;
    private final CategoryRegistry categoryRegistry;
    private final IncidentRepository incidentRepository;
    private final SecureRandom random = new SecureRandom();
    private final Set<String> generatedIds = ConcurrentHashMap.newKeySet();

    public IncidentFactory(Clock clock) {
        this(clock, new CategoryRegistry(), null);
    }

    public IncidentFactory(Clock clock, CategoryRegistry categoryRegistry) {
        this(clock, categoryRegistry, null);
    }

    @Autowired
    public IncidentFactory(
            Clock clock,
            CategoryRegistry categoryRegistry,
            @Nullable IncidentRepository incidentRepository) {
        this.clock = Objects.requireNonNull(clock, "Clock cannot be null");
        this.categoryRegistry = (categoryRegistry != null) ? categoryRegistry : new CategoryRegistry();
        this.incidentRepository = incidentRepository;
    }

    public Incident createIncident(String title, String description, String reporterId) {
        return createIncident(title, description, reporterId, (String) null);
    }

    public Incident createIncident(String title, String description, String reporterId, IncidentCategory category) {
        return createIncident(title, description, reporterId, category != null ? category.name() : null);
    }

    public Incident createIncident(String title, String description, String reporterId, String domainOrCategory) {
        String id = generateTicketId(domainOrCategory);
        Instant now = clock.instant();
        return new Incident(id, title, description, reporterId, now);
    }

    public String generateTicketId(IncidentCategory category) {
        return generateTicketId(category != null ? category.name() : null);
    }

    public String generateTicketId(String domainOrCategory) {
        String prefix = categoryRegistry.getPrefix(domainOrCategory);
        if (prefix == null || prefix.trim().isEmpty()) {
            prefix = "GEN";
        }
        prefix = prefix.trim().toUpperCase();

        int attempts = 0;
        int max4DigitAttempts = 100;

        while (true) {
            attempts++;
            String candidateId;
            if (attempts <= max4DigitAttempts) {
                // 4-digit random number: 1000 - 9999 (e.g. NET-4821, SOF-7392, HWD-1648, DBS-9253, SEC-3076)
                int num = 1000 + random.nextInt(9000);
                candidateId = prefix + "-" + num;
            } else if (attempts <= max4DigitAttempts + 100) {
                // Expand to 5 digits if dense collision space: 10000 - 99999
                int num = 10000 + random.nextInt(90000);
                candidateId = prefix + "-" + num;
            } else {
                // Expand to 6 digits: 100000 - 999999
                int num = 100000 + random.nextInt(900000);
                candidateId = prefix + "-" + num;
            }

            if (!isIdDuplicate(candidateId)) {
                generatedIds.add(candidateId);
                return candidateId;
            }
        }
    }

    private boolean isIdDuplicate(String id) {
        if (generatedIds.contains(id)) {
            return true;
        }
        if (incidentRepository != null) {
            try {
                return incidentRepository.existsById(id);
            } catch (Exception e) {
                return incidentRepository.findById(id).isPresent();
            }
        }
        return false;
    }
}

