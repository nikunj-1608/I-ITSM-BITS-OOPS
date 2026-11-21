# Architecture Decision Records (ADR) - I-ITSM

This document records architectural, design, and technical decisions made during the project, documenting the context, alternatives considered, and rationale.

---

## ADR-001: Acceptance of `jakarta.persistence` Annotations on Domain Entities
- **Status**: Accepted
- **Context**: The domain layer must be clean and framework-independent. However, maintaining duplicate entity hierarchies (one pure domain POJO and one JPA entity) introduces substantial boilerplate mapping, bidirectional sync complexity, and potential dirty-checking inconsistencies.
- **Decision**: Allow standard `jakarta.persistence` annotations directly on domain entities while strictly prohibiting any `org.springframework.*` imports in `com.iitsm.domain`.
- **Alternatives Considered**:
  1. *Complete separation*: Pure POJOs in `domain`, separate `@Entity` classes in `infrastructure.persistence` mapped via MapStruct. (Rejected: excessive boilerplate for a 1-semester OOP project without architectural benefit, since `jakarta.persistence` is a vendor-neutral standard specification).
  2. *XML mapping (`orm.xml`)*: Pure POJOs mapped via external XML. (Rejected: brittle, difficult to refactor and maintain).
- **Reason**: Preserves high cohesion and readability of domain entities while keeping the domain completely free of Spring runtime dependencies.

---

## ADR-002: Pure-Java In-House NLP vs. External Heavyweight ML Libraries
- **Status**: Accepted
- **Context**: The system requires incident text classification, service matching, and duplicate detection.
- **Decision**: Implement TF-IDF vectorization, Cosine Similarity, and Multinomial Naive Bayes entirely in pure Java under `com.iitsm.domain.strategy`.
- **Alternatives Considered**:
  1. *External libraries (Smile, Weka, Apache OpenNLP)*. (Rejected: hides OOP implementation details, introduces dependency bloat, reduces viva defensibility).
  2. *External LLM APIs (OpenAI / Anthropic / Hugging Face)*. (Rejected: nondeterministic, requires API keys/network access, not self-contained for offline evaluation).
- **Reason**: Demonstrates core OOP principles (Strategy, Template Method, immutability, clean interface segregation), remains 100% explainable in code reviews and vivas, and executes deterministically without external dependencies.

---

## ADR-003: Injected `java.time.Clock` for Temporal Operations
- **Status**: Accepted
- **Context**: SLA tracking, aging calculation, ticket escalation, and simulation require precise temporal control and acceleration.
- **Decision**: Forbid direct calls to `Instant.now()`, `LocalDateTime.now()`, or `System.currentTimeMillis()` in business code; always inject `java.time.Clock`.
- **Alternatives Considered**:
  1. *Static mock frameworks (MockedStatic in Mockito)*. (Rejected: brittle, multi-threading issues, doesn't support interactive simulation).
  2. *Direct system time calls*. (Rejected: impossible to test SLA breaches deterministically or fast-forward time in demos).
- **Reason**: Enables rock-solid unit testing of SLA deadlines, pause-resume windows, and allows the `AdjustableClock` simulation engine to fast-forward time (x1, x60, x600).

---

## ADR-004: State Pattern with Stateless State Objects in EnumMap
- **Status**: Accepted
- **Context**: Incidents progress through 9 internal states with complex transition rules, action permissions, and SLA pausing.
- **Decision**: Implement the State Pattern where `Incident` delegates actions to `IncidentState` implementations. The state instances are stateless singletons registered in an `EnumMap<IncidentStatus, IncidentState>` within `IncidentStates`.
- **Alternatives Considered**:
  1. *Giant `switch(status)` / `if-else` blocks in services*. (Rejected: violates Tell-Don't-Ask and Single Responsibility Principle, fails OOP criteria).
  2. *Spring State Machine framework*. (Rejected: heavy dependency, leaks framework details into domain, overkill for defined linear/branching transitions).
  3. *State objects with stateful incident reference*. (Rejected: complicates JPA entity lifecycle and caching).
- **Reason**: Zero-allocation per transition, completely thread-safe, encapsulates transition logic cleanly, and enables trivial exhaustive transition matrix testing.

---

## ADR-005: Hexagonal Repository Ports & Adapters with Optimistic Locking
- **Status**: Accepted
- **Context**: Pure domain repositories must remain free of Spring dependencies, but the persistence layer must leverage Spring Data JPA, handle transactions, and guard against race conditions.
- **Decision**: Define pure Java repository ports in `com.iitsm.domain.repository` and implement them via Spring `@Component` adapters in `com.iitsm.infrastructure.persistence.adapter`. Catch all database errors and optimistic locking collisions (`@Version`) and translate them into domain `PersistenceFailureException`.
- **Alternatives Considered**:
  1. *Spring Data repositories extending domain interfaces directly*. (Rejected: couples domain to Spring Data types like `Pageable`, `Optional` Spring behaviors, and leaks framework infrastructure).
- **Reason**: Maintains strict architectural layer separation (`domain` never imports Spring), enables effortless switching between H2 for tests and PostgreSQL for production, and provides centralized exception translation.

---

## ADR-006: Stateless JWT Authentication with BCrypt and 5-Strike Lockout
- **Status**: Accepted
- **Context**: The system must provide role-based security across 3 personas (Employee, Technician, Admin) and enforce security boundaries (account lockout after 5 consecutive failed logins).
- **Decision**: Implement stateless JWT authentication via `jjwt` with BCrypt password hashing. Authentication failures track attempt counts and lock accounts on the 5th strike. Centralize 401 and 403 responses into consistent JSON objects.
- **Alternatives Considered**:
  1. *Stateful HTTP Sessions*. (Rejected: hard to scale, poor match for single-page applications and REST APIs).
  2. *OAuth2 / Keycloak*. (Rejected: requires external identity provider server setup, complicating standalone viva and grading evaluation).
- **Reason**: Lightweight, completely self-contained in pure Java, satisfies all security criteria, and returns clean, uniform JSON error contracts.

---

## ADR-007: Composite ML/Rule-Based Classification and Multi-Factor Priority Engine Architecture
- **Status**: Accepted
- **Context**: Incident intake requires automated category assignment, service association, multi-dimensional business priority scoring, duplicate correlation, and technician routing without external SaaS dependencies or non-deterministic behavior.
- **Decision**:
  1. Build a Composite Classifier combining high-precision Keyword matching with a pure-Java Multinomial Naive Bayes model trained on a 630-sample synthetic corpus with Laplace smoothing and log-space likelihood calculation.
  2. Implement a 6-Factor Composite Priority Engine using the Strategy Pattern ($\sum w_i \times s_i$, where $\sum w_i = 1.0$) coupled with policy override rule chains for security breaches, critical outages, and duplicate ticket avalanches.
  3. Implement swappable Technician Assignment Strategies managed via a thread-safe runtime registry (`AssignmentStrategyRegistry`), enabling zero-downtime routing strategy hot-swapping via Admin REST endpoints.
  4. Perform duplicate ticket detection using TF-IDF vector cosine similarity and Jaccard token similarity over a 72-hour sliding window, bidirectionally linking incidents when similarity exceeds 0.70.
- **Alternatives Considered**:
  1. *Rule-only keyword matching*: Lacks generalization on conversational descriptions or variations in user phrasing.
  2. *Single Naive Bayes model*: Can produce false positives on edge cases where explicit technical terms (e.g. "VPN", "PostgreSQL") clearly dictate the domain.
  3. *Static if-else technician routing*: Violates the Open/Closed Principle and prevents dynamic operational load balancing.
- **Reason**: The composite approach pairs deterministic technical precision with statistical generalization, ensures full transparency and explainability in priority scoring, provides runtime algorithm extensibility (OCP), and runs 100% offline in pure Java.

---

## ADR-008: Chain of Responsibility for Multi-Tier Escalation and Virtual Time-Accelerated Simulation
- **Status**: Accepted
- **Context**: SLA degradation and high-impact outages require progressive multi-tier escalation (Tier 1 Technician Reminder -> Tier 2 Team Lead Intervention -> Tier 3 System Administrator Action -> Tier 4 IT Operations Manager Breach Notification) governed by dynamic SLA thresholds (75%, 90%, 100% hard breach) and service criticality. Live evaluation, testing, and viva demonstration of temporal degradation requires accelerating time without modifying system clocks or sleeping.
- **Decision**:
  1. Implement the **Chain of Responsibility Pattern** (`EscalationHandler`, `AbstractEscalationHandler`) with 4 distinct tier handlers (`Tier4ItManagerHandler`, `Tier3SystemAdminHandler`, `Tier2TeamLeadHandler`, `Tier1TechnicianHandler`) assembled via `EscalationChainBuilder`.
  2. Implement `AdjustableClock` extending `java.time.Clock` with virtual epoch tracking, dynamic speed multipliers ($\times 1$, $\times 60$, $\times 600$), discrete fast-forward jumps (`fastForward(Duration)`), and state resets.
  3. Decouple SLA evaluation into domain model logic (`SlaTracker.evaluateStatus`, continuous risk scoring $0.0 \dots 1.0$), application service orchestration (`SlaService`), and scheduled background scanning (`SlaMonitoringScheduler`), publishing `DomainEvent.SlaWarning`, `DomainEvent.SlaViolated`, and `DomainEvent.IncidentEscalated`.
  4. Build a deterministic `SimulationService` orchestrating 5 core demonstration scenarios (S1 Happy Path, S2 Critical Outage & Multi-Tier Escalation, S3 Wrong-Domain & Triage Reassignment, S4 Duplicate Storm Avalanche, S5 Rejection Loop & SLA Clock Pausing).
- **Alternatives Considered**:
  1. *Monolithic if-else blocks in SlaService*: Violates the Open/Closed Principle and Single Responsibility Principle; adding or reordering escalation tiers requires modifying application service code.
  2. *OS system time manipulation or Thread.sleep in tests*: Flaky, dangerous, causes timeout in CI/CD, and cannot support interactive simulation speed adjustments.
- **Reason**: The Chain of Responsibility pattern isolates escalation criteria into independently testable classes with clear precedence; `AdjustableClock` provides instantaneous temporal simulation with zero thread blocking; and domain events seamlessly trigger notifications and audit logs across all tiers.

---

## ADR-009: Evidence-Based Triage Gating for Automatic Technician Assignment
- **Status**: Accepted
- **Context**: Tickets stopped being auto-assigned. Intake only assigns when classification confidence is at least 0.60; otherwise the ticket is flagged `needsTriage` and `UnassignedIncidentDispatcher` deliberately skips it, so only an admin can release it. The composite classifier capped confidence at 0.55 whenever the keyword classifier scored 0.50 or lower, which sent almost every short or conversational ticket to triage even when Naive Bayes agreed. Measured on the seeded corpus, Naive Bayes alone is badly overconfident on vague text (0.84 to 0.97 with zero keyword hits), so it cannot be trusted without corroboration.
- **Decision**:
  1. `KeywordClassifier` scores a single keyword hit found in the ticket title at 0.55 (`SINGLE_TITLE_MATCH_CONFIDENCE`); a single hit only in the description stays at 0.50.
  2. `CompositeClassifier` caps confidence at 0.55 (routing to triage) when: no keyword matched; or a single keyword matched outside the title; or models disagree and keyword support is below 0.60; or neither model reaches 0.60. All thresholds are named constants.
  3. `TechnicianAssignmentService`, `IncidentIntakePipeline` and `UnassignedIncidentDispatcher` handle only expected contention (`TechnicianOverloadedException`, `ObjectOptimisticLockingFailureException`), log a WARN when no technician has capacity, and let unexpected failures propagate. A lost lock race no longer aborts the whole dispatcher cycle.
  4. `findAvailableTechnicians` is null-safe for `availability_status` and `max_capacity` (a NULL capacity is treated as uncapped by the query; the domain `hasCapacity()` check then applies the mapper default of 5).
- **Alternatives Considered**:
  1. *Trust Naive Bayes when keywords abstain*: rejected after measurement; it auto-assigned vague and gibberish tickets to arbitrary teams.
  2. *Auto-assign any single keyword (no title rule)*: rejected; indistinguishable from ambiguous tickets such as F5 ("screen" only in the description), which the regression suite requires to go to triage.
  3. *Keep the original rule (single keyword goes to triage)*: rejected; clear tickets such as "VPN keeps dropping" would never auto-assign.
  4. *Let the dispatcher also release triage tickets automatically*: rejected as an unlisted feature; human triage stays the owner of low-confidence tickets.
- **Reason**: Evidence-weighted gating keeps the human-in-the-loop safeguard for genuinely ambiguous tickets while restoring automatic assignment for clear ones, keeps the policy inside the Composite/Strategy classifier so callers still depend only on `IncidentClassifier`, and replaces silent failure with explicit, observable handling (CLAUDE.md rules 9 and 15).

---

## ADR-010: Dedicated Read Model for the Admin Ticket Inspection Dialog
- **Status**: Accepted
- **Context**: Administrators need a full inspection view of one ticket (reporter, IT service and criticality, support team, SLA, explainable priority breakdown, notes, resolution, feedback, linked tickets, audit trail). `AdminIncidentView` already carries most of this, but it is also the row type of the Command Centre list, which maps every row with two user lookups and one audit query. Adding service and team lookups to it would make every table load pay for data only the single-ticket dialog needs.
- **Decision**:
  1. `GET /api/admin/incidents/{id}` returns a new `AdminIncidentDetailView`; the list endpoint keeps returning `AdminIncidentView`.
  2. The detail view embeds `AdminIncidentView` with `@JsonUnwrapped`, so every existing top-level JSON field is unchanged and current consumers keep working.
  3. A new read-only application service, `AdminIncidentDetailService`, assembles an application-layer `AdminIncidentDetail` (incident, reporter, assignee, IT service, support team, audit records). Access control is inherited by loading the incident through `IncidentLifecycleService`. `IncidentDtoMapper.toAdminDetailView` projects it to the web DTO.
  4. Support team resolution: the assignee's own team when assigned, otherwise the owning team of the incident's IT service. "Last modified" is derived from the newest audit record, because `Incident` has no modification timestamp of its own.
  5. Controllers stay free of repositories (ArchUnit rule A1); all lookups live in the application service and degrade to `null` for dangling references.
- **Alternatives Considered**:
  1. *Extend `AdminIncidentView` with the extra fields*: simplest, but adds N+1 lookups to the list endpoint.
  2. *Resolve the extra data in `AdminController`*: violates "controllers call one application service method" and the A1 architecture rule.
  3. *Separate endpoints per section, fetched lazily by the UI*: more round trips and more partial-failure states for no user benefit.
- **Reason**: A purpose-built read model keeps the list cheap, keeps the dependency direction `web -> application -> domain`, and is backward compatible. It follows the persona-specific DTO rule (CLAUDE.md rule 12) and Interface Segregation: callers get exactly the projection they need.

---

## ADR-011: Persist the Priority Factor Breakdown
- **Status**: Accepted
- **Context**: The Flyway schema has a `factor_contributions` table, but no JPA mapping existed. `IncidentMapper` rebuilt every loaded `PriorityDecision` with an empty factor list, so the explainable priority breakdown (a core requirement of the multi-factor priority engine, ADR-007) was computed at intake and then silently lost on the first save and reload. Administrators saw an empty matrix.
- **Decision**:
  1. Add `FactorContributionJpaEntity` mapped to the existing table (no new migration), owned by `IncidentJpaEntity` through `@OneToMany(mappedBy, cascade = ALL, orphanRemoval = true, fetch = EAGER)`.
  2. Use a `Set` ordered with `@OrderBy("id ASC")` rather than a `List`: the aggregate already fetches its notes eagerly as a `List` (a Hibernate bag), and a second eager bag raises `MultipleBagFetchException`. Generated identity ids preserve the calculator's factor order.
  3. `IncidentMapper` writes the breakdown on save and reads it on load. The domain `PriorityDecision` and `FactorContribution` value objects are unchanged, so the domain layer stays pure.
  4. Because `save` merges a freshly built entity, the factor rows are deleted and re-inserted on each incident save (at most six small rows). This is correct and bounded; avoiding it would require a natural key and a schema change.
- **Alternatives Considered**:
  1. *`@ElementCollection` of an embeddable*: same multiple-bag problem for a `List`, and the table already has a surrogate id column.
  2. *Recompute factors on read*: depends on mutable inputs (reporter history, ticket age), so it would not reproduce what was decided at the time.
  3. *Store the breakdown as JSON on the incident row*: loses queryability and duplicates the existing table.
- **Reason**: The explainability requirement is only met if the decision that was actually made is what administrators later inspect. Mapping the table that already exists is the smallest change that achieves this and keeps the aggregate boundary intact. Tickets saved before this change have no stored rows; the UI states that the breakdown was not recorded for them.
