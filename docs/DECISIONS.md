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

---

## ADR-012: Multi-Value Priority and Category Filters in Incident Search
- **Status**: Accepted
- **Context**: The Admin Command Centre can filter the incident directory by one priority and one category only. Administrators need to view, for example, P1 and P3 tickets together, or NETWORK and HARDWARE tickets together. The search path is `AdminController` -> `SearchService` -> `IncidentSearchPort` -> `IncidentSearchAdapter` -> `IncidentSpecifications`.
- **Decision**:
  1. `GET /api/admin/incidents` keeps the parameter names `priority` and `category` but binds them to `Set<Priority>` and `Set<IncidentCategory>`. Clients send repeated parameters (`?priority=P1&priority=P3`). A single value and the comma-separated form still bind, so existing callers are unaffected.
  2. `SearchService.IncidentSearchCriteria` and `IncidentSearchPort.SearchCriteria` carry `priorities` and `categories` sets. A null or empty set means "no filter".
  3. `IncidentSpecifications` gains `hasPriorityIn` and `hasCategoryIn` (`IN` predicates). Values within one filter are OR-ed, different filters stay AND-ed. The single-value `hasPriority` and `hasCategory` remain for existing callers and tests.
  4. Filter categories are the real `IncidentCategory` values. The legacy `ACCESS` option never existed in the enum and made the request fail (an unknown enum value surfaces as HTTP 500 through the global handler's catch-all), so it is not offered.
- **Alternatives Considered**:
  1. *Comma-separated single parameter (`priorities=P1,P3`)*: needs custom splitting and a new parameter name; Spring already binds repeated parameters to a collection.
  2. *Keep single-value fields and add parallel list fields*: two sources of truth per criterion and branching in the adapter.
  3. *Replace the single-value specifications*: needless churn in tests and any future caller; the new ones cover the single-value case anyway.
- **Reason**: Reusing the Specification pattern keeps the change inside the infrastructure and application layers, leaves controllers free of logic (rule 11), and is backward compatible with single-value requests.

---

## ADR-013: Department-Based Ticket Numbers (`DEPT-YEAR-SEQUENCE`)
- **Status**: Accepted (all 5 phases built: domain types, gap-free counters, generation, migration of existing tickets, staff display name, web display)
- **Context**: Ticket ids are `INC-` plus eight random hex characters (`IncidentFactory`). They are unique but carry no meaning, cannot be read out on a call, and give no sense of volume. Employees already have a `department`, and the id is the primary key of `incidents`, referenced by notes, audit records, links, notifications and technician workloads.
- **Decision**:
  1. The permanent ticket id is `<DEPT>-<YEAR>-<SEQUENCE>`, for example `FIN-2026-000142`. The department is the reporter's department (code of 2-4 uppercase letters), the year comes from the injected `Clock`, and the sequence is a zero-padded counter (minimum six digits, never truncated) kept per department code and per year. It restarts at `000001` each year.
  2. Only data that is known and fixed at creation goes into the id. Category, priority, team and assignee are excluded because they are assigned after creation and change later (reclassification, override, reassignment); an id that tracks them would have to be rewritten across every referencing table.
  3. `TicketNumber` (value object) formats and parses the id. `DepartmentCodeResolver` (Strategy) maps a free-text department to a code; the default `RegistryDepartmentCodeResolver` holds the eight seeded departments (ENG, FIN, HR, SAL, OPS, CSU, LEG, EXE), accepts new registrations at runtime, and falls back to the first three letters of an unregistered name, or `MISC` when the name has fewer than two letters. `TicketSequenceRepository` is the port for the counters.
  4. Priority appears only in a derived, staff-only display name, the current priority in front of the ticket id (for example `P2-FIN-2026-000142`, hyphens only, no spaces). `TicketDisplayName` builds it and is computed on every render of the technician and admin views (`displayId` next to the unchanged `id`); it is never stored, so an override is reflected at once and the id (the primary key referenced everywhere) never changes. `EmployeeIncidentView` has no such field, so hiding the ticket's priority from employees is still enforced by response type (rule 12). Lookups (`loadIncident` in `IncidentLifecycleService` and `AdminIncidentService`) and the admin text search accept either the plain id or the complete display name; a half-typed name such as `P2-FIN` is not guessed at. The text search now also matches the ticket id (it only searched title and description before).
  5. Existing tickets are renamed into the same format by Flyway migration `V4__rename_legacy_tickets` (a Java migration that delegates to `LegacyTicketRenamer`, so the same `RegistryDepartmentCodeResolver` and `TicketNumber` rules apply and the logic is testable). Department = the reporter's, year = the ticket's creation year (UTC), sequence = creation order within department and year, continuing after any numbers already issued. The ticket id is the primary key, so the migration re-creates the nine foreign keys that point at `incidents(id)` with `ON UPDATE CASCADE` (their delete rules are kept; constraint names are read from the JDBC metadata because they differ between PostgreSQL and H2), updates the three tables that hold an incident id without a foreign key (`technician_active_incidents`, `incident_linked_ids.linked_incident_id`, `audit_records`), rewrites ids quoted in system generated text (`notifications` subject and message, `audit_records.description`), and advances the counters. It runs in Flyway's transaction, so on PostgreSQL a failure rolls everything back and the application refuses to start rather than run on a half-renamed database. It does nothing on a database without old-style ids. Rejected: leaving old tickets as they are (two id formats forever), and copying each incident row to a new id (needs every column listed and breaks on future columns).
  6. Counters live in the `ticket_sequences` table (Flyway V3), one row per department code and year. The number is reserved inside the ticket's own transaction (`TicketSequenceRepositoryAdapter.next`, propagation MANDATORY): an atomic `UPDATE ... SET last_sequence = last_sequence + 1` followed by a read. The row lock queues concurrent callers, and if the intake rolls back the increment rolls back with it, so **the sequence never skips a number**. Only the first ticket of a department and year needs a separate short transaction to create the counter row (starting at 0, which is not an issued number); a lost creation race is ignored and the increment is simply repeated. The accepted cost: tickets of one department and year are issued one transaction at a time, because intake is a single transaction and the next ticket waits for it to finish. A side benefit is that a ticket raised right after a similar one now sees it for duplicate detection. Rejected: reserving the number in a separate transaction (no waiting, but a failed intake would leave a gap).
- **Alternatives Considered**:
  1. *Category in the id*: category is only a classifier guess at creation; triage and admin reclassification would leave a wrong prefix.
  2. *Priority frozen into the id, or an id that changes with priority*: goes stale on override or aging, or forces rewrites of every reference; either way it shows priority to employees.
  3. *Date-based `INC-YYYYMMDD-NNNN`*: no department, and a daily reset makes the counter meaningless.
  4. *Keep random ids and add a separate display number*: two identifiers per ticket to explain and search.
  5. *Database sequence per department*: needs DDL per new department, which breaks runtime extensibility of the department list.
- **Reason**: A short, meaningful, permanent id that can be read aloud, sorts by year and department, and is unique through a locked counter row. Keeping the id immutable preserves every reference and every id already quoted in emails, while the display name still gives staff the priority at a glance. The resolver and registry keep the department list open for extension without editing existing classes (OCP).

---

## ADR-014: Employees See Only Non-Sensitive Entries of a Ticket's Activity History
- **Status**: Accepted
- **Context**: `EmployeeIncidentView` is built so it cannot carry the ticket's priority, but its activity history was built from every audit record of the ticket. Audit descriptions such as "Calculated priority P3 (Score: 0.3975)" and "Priority changed from P3 to P1" therefore showed employees the priority and score, against the rule that employees never see priority (rule 12). Found while building the staff display name (ADR-013); it was already present before.
- **Decision**:
  1. `IncidentDtoMapper.toEmployeeView` keeps only audit entries whose action is in an allow-list (`EMPLOYEE_VISIBLE_AUDIT_ACTIONS`, expressed with the event classes so names cannot drift). Hidden: `PriorityCalculated`, `PriorityOverridden`, `SlaWarning`, `SlaViolated`, `IncidentEscalated` (priority, SLA state, escalation roles/reasons). Everything an employee saw before is still shown, including duplicate and reassignment entries, which do not reveal priority.
  2. The list is an allow-list, so a new event type is hidden from employees until someone adds it. A test requires every `DomainEvent` type to be either shown or in the hidden set, so adding an event forces that decision.
  3. Technician and admin views are unchanged; the stored audit records are unchanged.
- **Alternatives Considered**:
  1. *Deny-list of the priority events*: smaller diff, but any new event that quotes the priority would leak by default.
  2. *Store a separate employee-safe description on each audit record*: cleaner long term, needs a migration and changes every event type.
  3. *Hide duplicate and reassignment entries too*: they expose other ticket ids and a technician's reason, but they do not reveal priority, so they are left for a separate decision.
- **Reason**: Enforces the privacy rule at the one place that builds the employee projection, with a safe default for future events and no schema change.
