package com.iitsm.domain.model;

import com.iitsm.domain.exception.InvalidStateTransitionException;
import com.iitsm.domain.exception.ValidationException;
import com.iitsm.domain.state.IncidentStates;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

// OOP (Encapsulation): Incident is the aggregate root; the lifecycle actions below delegate to the current IncidentState.
/**
 * OOP: Aggregate Root representing an IT service incident ticket.
 * Encapsulates full ticket lifecycle, invariants, and delegates lifecycle actions to IncidentState.
 * Private fields, no public setters, domain invariants enforced in constructor and methods.
 */
public class Incident {

    private final String id;
    private final String title;
    private final String description;
    private final String reporterId;
    private final Instant createdAt;

    private IncidentCategory category;
    private String serviceId;
    private PriorityDecision priorityDecision;
    private IncidentStatus status;
    private String assigneeId;
    private SlaTracker slaTracker; // Composition: lifecycle bound to this incident

    private final List<InvestigationNote> notes;
    private Resolution resolution;
    private Feedback feedback;
    private int reopenCount;
    private final Set<String> linkedIncidentIds;
    private boolean needsTriage;
    private final Set<String> excludedTechnicianIds;
    private Long version; // Optimistic locking

    // OOP (Constructor): invariants are checked here, so an Incident can never exist in an invalid state.
    public Incident(String id, String title, String description, String reporterId, Instant createdAt) {
        if (id == null || id.trim().isEmpty()) {
            throw new ValidationException("id", "Incident ID cannot be blank");
        }
        if (title == null || title.trim().length() < 5 || title.trim().length() > 120) {
            throw new ValidationException("title", "Title length must be between 5 and 120 characters");
        }
        if (description == null || description.trim().length() < 20 || description.trim().length() > 4000) {
            throw new ValidationException("description", "Description length must be between 20 and 4000 characters");
        }
        if (reporterId == null || reporterId.trim().isEmpty()) {
            throw new ValidationException("reporterId", "Reporter ID cannot be blank");
        }
        if (createdAt == null) {
            throw new ValidationException("createdAt", "Created timestamp cannot be null");
        }

        this.id = id.trim();
        this.title = title.trim();
        this.description = description.trim();
        this.reporterId = reporterId.trim();
        this.createdAt = createdAt;

        this.status = IncidentStatus.NEW;
        this.notes = new ArrayList<>();
        this.linkedIncidentIds = new HashSet<>();
        this.excludedTechnicianIds = new HashSet<>();
        this.needsTriage = false;
        this.reopenCount = 0;
        this.version = 0L;
    }

    // --- State Pattern Delegation Methods ---

    public void classify(
            IncidentCategory category,
            String serviceId,
            PriorityDecision priorityDecision,
            SlaPolicy slaPolicy,
            Instant now) {
        Objects.requireNonNull(slaPolicy, "slaPolicy cannot be null");
        IncidentStates.of(this.status).classify(this, category, serviceId, priorityDecision, now);
        this.slaTracker = SlaTracker.create(this.createdAt, slaPolicy);
    }

    public void assign(String technicianId, Instant now) {
        IncidentStates.of(this.status).assign(this, technicianId, now);
    }

    // OOP (Delegation / State pattern): no if (status == ...) here; the current state object decides if the action is legal.
    public void acknowledge(String technicianId, Instant now) {
        IncidentStates.of(this.status).acknowledge(this, technicianId, now);
    }

    public void startInvestigation(String technicianId, Instant now) {
        IncidentStates.of(this.status).startInvestigation(this, technicianId, now);
    }

    public void requestInfo(String technicianId, String message, Instant now) {
        IncidentStates.of(this.status).requestInfo(this, technicianId, message, now);
    }

    public void provideInfo(String employeeId, String responseText, Instant now) {
        IncidentStates.of(this.status).provideInfo(this, employeeId, responseText, now);
    }

    // OOP (Polymorphism): the same call behaves differently depending on which IncidentState object is plugged in.
    public void resolve(Resolution resolution, Instant now) {
        IncidentStates.of(this.status).resolve(this, resolution, now);
    }

    // OOP (Delegation): verification is only legal in the Resolved state; the state class enforces it.
    public void verify(String employeeId, Instant now) {
        IncidentStates.of(this.status).verify(this, employeeId, now);
    }

    public void reject(String employeeId, String reason, Instant now) {
        IncidentStates.of(this.status).reject(this, employeeId, reason, now);
    }

    public void close(String actorId, Feedback feedback, Instant now) {
        IncidentStates.of(this.status).close(this, actorId, feedback, now);
    }

    // OOP (Delegation): another lifecycle action that is forwarded to the state object.
    public void flagWrongDomain(String technicianId, String reason, Instant now) {
        IncidentStates.of(this.status).flagWrongDomain(this, technicianId, reason, now);
    }

    // --- Domain Package Mutations (Invoked by IncidentState implementations) ---

    // OOP (State pattern): swaps the state object; the incident then behaves like a different class without changing its own type.
    public void transitionTo(IncidentStatus nextStatus) {
        this.status = Objects.requireNonNull(nextStatus, "Next status cannot be null");
    }

    public void setClassificationData(
            IncidentCategory category,
            String serviceId,
            PriorityDecision priorityDecision) {
        this.category = Objects.requireNonNull(category, "Category cannot be null");
        this.serviceId = serviceId;
        this.priorityDecision = Objects.requireNonNull(priorityDecision, "PriorityDecision cannot be null");
    }

    public void setCategory(IncidentCategory category) {
        this.category = category;
    }

    public void setServiceId(String serviceId) {
        this.serviceId = serviceId;
    }

    // OOP (Known deviation): a public setter weakens encapsulation; it is kept for state classes and mappers (see CLAUDE.md 1.1).
    public void setAssignee(String technicianId) {
        this.assigneeId = Objects.requireNonNull(technicianId, "assigneeId cannot be null");
    }

    public void clearAssignee() {
        this.assigneeId = null;
    }

    public void setResolution(Resolution resolution) {
        this.resolution = Objects.requireNonNull(resolution, "resolution cannot be null");
    }

    public void setFeedback(Feedback feedback) {
        this.feedback = feedback;
    }

    public void incrementReopenCount() {
        this.reopenCount++;
    }

    public void setReopenCount(int count) {
        this.reopenCount = Math.max(0, count);
    }

    public void setSlaTracker(SlaTracker tracker) {
        this.slaTracker = tracker;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    // OOP (Encapsulation): notes are added through a method, never by exposing the list.
    public void addNote(InvestigationNote note) {
        Objects.requireNonNull(note, "note cannot be null");
        this.notes.add(note);
    }

    public void linkIncident(String duplicateIncidentId) {
        if (duplicateIncidentId != null && !duplicateIncidentId.equals(this.id)) {
            this.linkedIncidentIds.add(duplicateIncidentId);
        }
    }

    public void updatePriorityDecision(PriorityDecision priorityDecision, SlaPolicy slaPolicy) {
        updatePriorityDecision(priorityDecision, slaPolicy, Instant.now());
    }

    public void updatePriorityDecision(PriorityDecision priorityDecision, SlaPolicy slaPolicy, Instant now) {
        this.priorityDecision = Objects.requireNonNull(priorityDecision, "priorityDecision cannot be null");
        if (slaPolicy != null) {
            if (this.slaTracker != null) {
                this.slaTracker.updatePolicy(slaPolicy, now != null ? now : Instant.now());
            } else {
                this.slaTracker = SlaTracker.create(this.createdAt, slaPolicy);
            }
        }
    }

    /**
     * Changes the category and service. History is never rewritten: a manual priority override and the SLA
     * verdict of a resolved ticket stay as they are, and an open ticket's tracker is re-anchored to the new
     * policy instead of replaced, so the recorded response and a paused clock survive.
     */
    public void reclassify(IncidentCategory category, String serviceId, PriorityDecision priorityDecision, SlaPolicy slaPolicy, Instant now) {
        Objects.requireNonNull(category, "category cannot be null");
        this.category = category;
        if (serviceId != null) {
            this.serviceId = serviceId;
        }
        if (isPrioritySettled()) {
            return;
        }
        if (priorityDecision != null) {
            this.priorityDecision = priorityDecision;
        }
        if (slaPolicy != null) {
            if (this.slaTracker != null) {
                this.slaTracker.reanchor(slaPolicy, now);
            } else {
                this.slaTracker = SlaTracker.create(this.createdAt, slaPolicy);
            }
        }
    }

    /** True when a recalculation would overwrite a manual override or a verdict the ticket already earned. */
    public boolean isPrioritySettled() {
        boolean overridden = this.priorityDecision != null && this.priorityDecision.isOverridden();
        boolean resolved = this.slaTracker != null && this.slaTracker.getResolvedAt() != null;
        return overridden || resolved;
    }

    // OOP (Tell, Don't Ask): the incident re-anchors its own SLA; the service only tells it what happened.
    public void overridePriority(Priority newPriority, String adminId, String reason, SlaPolicy slaPolicy, Instant now) {
        if (!IncidentStates.of(this.status).allowsPriorityOverride()) {
            throw new InvalidStateTransitionException(this.status, "overridePriority", IncidentStates.of(this.status).allowedActions());
        }
        if (this.priorityDecision == null) {
            throw new ValidationException("priorityDecision", "Cannot override priority before classification");
        }
        if (reason == null || reason.trim().isEmpty()) {
            throw new ValidationException("reason", "Mandatory reason required for priority override");
        }
        if (slaPolicy != null && slaPolicy.getPriority() != newPriority) {
            throw new ValidationException("slaPolicy", "SLA policy must be the one for the overridden priority");
        }
        this.priorityDecision = this.priorityDecision.withOverride(newPriority, adminId, reason.trim(), now);
        if (slaPolicy != null && this.slaTracker != null) {
            this.slaTracker.reanchor(slaPolicy, now);
        }
    }

    public void updateSlaStatus(Instant now) {
        if (this.slaTracker != null) {
            this.slaTracker.evaluateStatus(now);
        }
    }

    // --- Getters returning immutable views (Section 2 Rule 10) ---

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public String getReporterId() {
        return reporterId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public IncidentCategory getCategory() {
        return category;
    }

    public String getServiceId() {
        return serviceId;
    }

    public PriorityDecision getPriorityDecision() {
        return priorityDecision;
    }

    public Priority getPriority() {
        return (priorityDecision != null) ? priorityDecision.priority() : null;
    }

    public IncidentStatus getStatus() {
        return status;
    }

    public String getAssigneeId() {
        return assigneeId;
    }

    public SlaTracker getSlaTracker() {
        return slaTracker;
    }

    public List<InvestigationNote> getNotes() {
        return Collections.unmodifiableList(notes);
    }

    /**
     * Tell, Don't Ask (ADR-043): may this user promote the ticket's fix into the knowledge base now? The same rules as
     * {@link KnowledgeArticle#promote}: a confirmed resolution with a category, and an actor allowed by
     * {@link User#canPromoteResolution}. Whether the ticket was already promoted is not known here (the server answers 409).
     */
    public boolean canBePromotedBy(User actor) {
        return actor != null && status.hasConfirmedResolution() && resolution != null && category != null
                && actor.canPromoteResolution(resolution);
    }

    /**
     * Tell, Don't Ask (ADR-045): may this user give the ticket to another technician of the team now? The user must be
     * allowed to reassign team tickets, the status must allow it, and a ticket in triage stays with the administrator.
     * Whether the ticket belongs to the user's team is not known here (the application service checks it).
     */
    public boolean canBeReassignedBy(User actor) {
        return actor != null && actor.canReassignTeamTickets() && status.canBeReassignedByTeamLead() && !needsTriage;
    }

    public Resolution getResolution() {
        return resolution;
    }

    public Feedback getFeedback() {
        return feedback;
    }

    public int getReopenCount() {
        return reopenCount;
    }

    // OOP (Encapsulation): returns an unmodifiable collection so callers cannot bypass linkIncident.
    public Set<String> getLinkedIncidentIds() {
        return Collections.unmodifiableSet(linkedIncidentIds);
    }

    public Long getVersion() {
        return version;
    }

    public Set<String> getAllowedActions() {
        return IncidentStates.of(this.status).allowedActions();
    }

    public boolean isNeedsTriage() {
        return needsTriage;
    }

    /**
     * True when the ticket waits for an automatic assignment: classified, no assignee yet and not held back for human
     * triage (ADR-009: humans own low-confidence tickets, so the dispatcher never touches those).
     */
    public boolean isAwaitingAutomaticAssignment() {
        return status == IncidentStatus.CLASSIFIED && assigneeId == null && !needsTriage;
    }

    public void setNeedsTriage(boolean needsTriage) {
        this.needsTriage = needsTriage;
    }

    public Set<String> getExcludedTechnicianIds() {
        return Collections.unmodifiableSet(excludedTechnicianIds);
    }

    public void addExcludedTechnician(String technicianId) {
        if (technicianId != null && !technicianId.trim().isEmpty()) {
            this.excludedTechnicianIds.add(technicianId.trim());
        }
    }

    public void clearExcludedTechnicians() {
        this.excludedTechnicianIds.clear();
    }

    /** Lets one technician work on the ticket again (an administrator rejected the technician's wrong-domain flag). */
    public void removeExcludedTechnician(String technicianId) {
        if (technicianId != null) {
            this.excludedTechnicianIds.remove(technicianId.trim());
        }
    }
}

