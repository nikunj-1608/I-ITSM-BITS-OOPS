package com.iitsm.application.service;

import com.iitsm.domain.event.DomainEvent;
import com.iitsm.domain.event.DomainEventPublisher;
import com.iitsm.domain.model.Incident;
import com.iitsm.domain.repository.IncidentRepository;
import com.iitsm.domain.strategy.similarity.DuplicateMatchPolicy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OOP: Application service identifying duplicate tickets and correlating recurring incidents.
 */
@Service
public class DuplicateDetectionService {


    public record DuplicateMatch(String parentIncidentId, double similarityScore) {}

    private final IncidentRepository incidentRepository;
    private final DomainEventPublisher eventPublisher;
    private final DuplicateMatchPolicy matchPolicy;
    private final Duration recencyWindow;
    private final Clock clock;

    public DuplicateDetectionService(
            IncidentRepository incidentRepository,
            DomainEventPublisher eventPublisher,
            DuplicateMatchPolicy matchPolicy,
            @Value("${iitsm.duplicates.recency-window-hours:72}") long recencyWindowHours,
            Clock clock) {
        this.incidentRepository = Objects.requireNonNull(incidentRepository, "incidentRepository cannot be null");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher cannot be null");
        this.matchPolicy = Objects.requireNonNull(matchPolicy, "matchPolicy cannot be null");
        if (recencyWindowHours <= 0) {
            throw new IllegalArgumentException("Duplicate recency window must be positive: " + recencyWindowHours);
        }
        this.recencyWindow = Duration.ofHours(recencyWindowHours);
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
    }

    /**
     * Checks if a new incident is a duplicate of a recent open incident with the same service and category.
     * If the {@link DuplicateMatchPolicy} accepts the pair (similarity threshold and shared domain terms, ADR-016),
     * links the incidents bidirectionally and publishes a DuplicateDetected event. The new incident joins the
     * cluster root only when it also matches the root; otherwise it links to its best match alone, so chains of
     * pairwise-similar tickets cannot drift into one cluster.
     */
    @Transactional
    public Optional<DuplicateMatch> checkAndLinkDuplicate(Incident newIncident) {
        Objects.requireNonNull(newIncident, "newIncident cannot be null");
        String newText = textOf(newIncident);
        Instant cutoff = clock.instant().minus(recencyWindow);

        Incident bestParent = null;
        double maxSimilarity = 0.0;
        for (Incident candidate : findCandidates(newIncident, cutoff)) {
            if (candidate.getId().equals(newIncident.getId()) || candidate.getCreatedAt().isBefore(cutoff)) {
                continue;
            }
            OptionalDouble similarity = matchPolicy.match(newText, textOf(candidate));
            if (similarity.isPresent() && similarity.getAsDouble() > maxSimilarity) {
                maxSimilarity = similarity.getAsDouble();
                bestParent = candidate;
            }
        }
        if (bestParent == null) {
            return Optional.empty();
        }
        Incident anchor = chooseAnchor(newIncident, newText, bestParent);
        link(newIncident, anchor, bestParent);
        eventPublisher.publish(new DomainEvent.DuplicateDetected(
                UUID.randomUUID().toString(), clock.instant(), newIncident.getId(), "SYSTEM",
                anchor.getId(), maxSimilarity));
        return Optional.of(new DuplicateMatch(anchor.getId(), maxSimilarity));
    }

    /** Open tickets of the same service and category inside the recency window; never an all-time scan. */
    private List<Incident> findCandidates(Incident incident, Instant cutoff) {
        if (incident.getServiceId() != null && incident.getCategory() != null) {
            return incidentRepository.findOpenIncidentsByServiceAndCategorySince(
                    incident.getServiceId(), incident.getCategory(), cutoff);
        }
        return incidentRepository.findOpenIncidentsSince(cutoff).stream()
                .filter(c -> incident.getCategory() == null || incident.getCategory() == c.getCategory())
                .collect(Collectors.toList());
    }

    /** The cluster root, unless the new ticket does not itself match the root (chain drift): then the best match. */
    private Incident chooseAnchor(Incident newIncident, String newText, Incident bestParent) {
        Incident root = findRootParent(bestParent);
        if (root.getId().equals(bestParent.getId()) || matchPolicy.match(newText, textOf(root)).isPresent()) {
            return root;
        }
        return bestParent;
    }

    private void link(Incident newIncident, Incident anchor, Incident bestParent) {
        anchor.linkIncident(newIncident.getId());
        newIncident.linkIncident(anchor.getId());
        incidentRepository.save(anchor);
        if (!bestParent.getId().equals(anchor.getId())) {
            bestParent.linkIncident(newIncident.getId());
            newIncident.linkIncident(bestParent.getId());
            incidentRepository.save(bestParent);
        }
    }

    private static String textOf(Incident incident) {
        return (incident.getTitle() != null ? incident.getTitle() : "") + " "
                + (incident.getDescription() != null ? incident.getDescription() : "");
    }

    private Incident findRootParent(Incident candidate) {
        Set<String> visited = new HashSet<>();
        return findRootParent(candidate, visited);
    }

    private Incident findRootParent(Incident current, Set<String> visited) {
        visited.add(current.getId());
        Incident oldest = current;
        for (String linkedId : current.getLinkedIncidentIds()) {
            if (!visited.contains(linkedId)) {
                Optional<Incident> opt = incidentRepository.findById(linkedId);
                if (opt.isPresent()) {
                    Incident candidate = opt.get();
                    if (candidate.getCreatedAt().isBefore(oldest.getCreatedAt())) {
                        Incident deepOldest = findRootParent(candidate, visited);
                        if (deepOldest.getCreatedAt().isBefore(oldest.getCreatedAt())) {
                            oldest = deepOldest;
                        }
                    }
                }
            }
        }
        return oldest;
    }

    /**
     * Retrieves all linked duplicate incidents for a given incident.
     */
    @Transactional(readOnly = true)
    public List<Incident> findLinkedDuplicates(String incidentId) {
        if (incidentId == null) {
            return Collections.emptyList();
        }
        Optional<Incident> opt = incidentRepository.findById(incidentId);
        if (opt.isEmpty() || opt.get().getLinkedIncidentIds().isEmpty()) {
            return Collections.emptyList();
        }

        List<Incident> linked = new ArrayList<>();
        for (String linkedId : opt.get().getLinkedIncidentIds()) {
            incidentRepository.findById(linkedId).ifPresent(linked::add);
        }
        return Collections.unmodifiableList(linked);
    }
}

