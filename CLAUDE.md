# CLAUDE.md - I-ITSM Project Rules & Operating Guidelines

> Last reconciled with the code base: 2026-10-31 (branch `bug-fixes`, after the ticket numbering work, ADR-013 and ADR-014). Sections 1 and the Operating Protocol are the
> project's rules and are unchanged. Sections 0 and 2-7 describe what the code actually does today; where the code
> departs from a rule, section 1.1 says so instead of hiding it.

## Operating Protocol & Phase Workflow
1. **Phase-by-Phase Execution**: Never start a phase until explicit user approval ("proceed to Phase N").
2. **Phase Start**: Re-read `PROMPT.md`, `CLAUDE.md`, and `docs/DECISIONS.md`. Post a short plan (packages, classes, tests to create) and wait for approval.
3. **Phase Completion**: Run `mvn verify` (all tests green), create `docs/phase-N-summary.md` (what was built, which pattern is where and why, known gaps), refresh PlantUML diagrams in `docs/uml/`, commit with a conventional commit message, and wait.
4. **Pattern & OOP Documentation**: Include a one-line `Pattern:` or `OOP:` note in the Javadoc of every pattern-bearing class (e.g., `Pattern: Strategy. Lets admins swap assignment logic at runtime.`).
5. **Decisions & Ambiguity**: Record any architectural or design choice in `docs/DECISIONS.md` as an ADR (decision, alternatives, reason).
6. **Feature Discipline**: Strictly follow specifications. No extra unlisted features. No Lombok on domain entities (records and explicit constructors preferred; Lombok allowed only on DTOs/config).

---

## 0. Project Snapshot

**What it is**: Intelligent IT Service Management for the fictional fintech Nimbus FinServe Inc. Employees raise
incidents; the system classifies, prioritises (explainably), assigns, escalates and audits them. Course project (CS F213, OOP).

**Run it** (Windows, from the repo root):
- `.\mvnw spring-boot:run` starts the `dev` profile (default): in-memory H2, demo mode on, deterministic company seed (seed 42), port **8085**.
- Open `http://localhost:8085/` (Swagger UI: `/swagger-ui.html`, configured via the springdoc dependency and the security allow-list).
- Every seeded account uses the password **`Password123!`**: `admin@nimbusfinserve.com` (ADMIN-SYS-01), `manager@nimbusfinserve.com` (IT manager), `employee@nimbusfinserve.com` (EMP-DEMO-01), `tech.network@nimbusfinserve.com` (TECH-NET-1), `lead.network@nimbusfinserve.com` (LEAD-NET), plus technicians `tech.net2@`, `tech.desk1@`, etc. The seed creates 6 support teams (Network, Database & Storage, Application, Security, Cloud Platform, Service Desk), each with a team lead and 3 technicians (24 technicians).
- Email notifications expect a mail server on `localhost:1025` (Mailpit). If none is running, `EmailChannel` logs a WARN per message; the failure is isolated and never blocks assignment.

**Verify it**: `.\mvnw verify` (currently 380 tests, 0 failures; JaCoCo gate = bundle-wide LINE coverage >= 70%, excluding `domain/demo` and `IitsmApplication`). CI (`.github/workflows/ci.yml`) runs `mvn clean verify -B` on JDK 21. There is no Checkstyle or formatter plugin in the `pom.xml`, so Google Java Style is not enforced by tooling.

**Profiles**: `dev` (default, H2 in-memory), `test` (`src/test/resources/application.yml`, H2, **no auto-seed**), `prod` and `demo` (PostgreSQL; `demo` targets the Docker Compose host `postgres`).

**Docs map**: `docs/01..05-*.md` (analysis, requirements, architecture, technology, patterns), `docs/DECISIONS.md` (ADR-001..014), `docs/phase-1..6-summary.md`, `docs/fix-auto-assignment-summary.md`, `docs/admin-ticket-inspection-summary.md`, `docs/multi-select-filters-summary.md`, `docs/ticket-nomenclature-phase-1..5.md`, `docs/uml/`, `docs/db/`, `docs/review/`, `docs/testing/`.

---

## 1. Non-Negotiable OOP & Engineering Rules (Section 2)
1. **Encapsulation & Immutability**:
   - Aggregate roots (`Incident`, `Technician`, `SlaTracker`) have private fields, no public setters, and expose rich behaviour methods (e.g., `incident.acknowledge(by, now)`).
   - Invariants are enforced in constructors and domain methods.
   - Value objects (`Priority`, `Impact`, `Urgency`, `DeadlinePair`, `Email`, `Skill`) are immutable (Java `record` or `final` class).
2. **Tell, Don't Ask**:
   - Application services orchestrate transactions; domain objects make business decisions.
   - No `if (status == X)` conditionals in services for lifecycle rules; lifecycle logic is encapsulated within `IncidentState` implementations.
3. **Inheritance for True Is-A Only**:
   - Class hierarchy: `User` (abstract) → `Employee`, `Technician`, `Administrator`; `Technician` → `TeamLead`.
   - All other relationships use composition or interface implementation. Prefer interfaces over base classes for polymorphic behaviour.
4. **Polymorphism via Interfaces**:
   - Callers depend exclusively on interfaces: `AssignmentStrategy`, `PriorityFactor`, `IncidentClassifier`, `NotificationChannel`, `DomainEventListener`, `IncidentState`, `SimilarityMeasure`, `EscalationHandler`.
5. **Open/Closed Principle (OCP)**:
   - Adding a new category, assignment strategy, notification channel, or priority factor must require creating a new class and registering it, without editing existing classes.
   - Every extensibility point must be proven with unit tests or demos.
6. **Strict Dependency Direction**:
   - `web` → `application` → `domain` ← `infrastructure`.
   - Domain package must have zero dependencies on Spring Framework (`jakarta.persistence` annotations on domain entities are an accepted documented trade-off recorded in `docs/DECISIONS.md`).
7. **Dependency Injection**:
   - Constructor injection only. No field injection (`@Autowired` on fields is prohibited).
   - No static singletons. Singleton lifecycles are managed by the Spring container or domain registries.
8. **Time Abstraction**:
   - Never call `Instant.now()` or `LocalDateTime.now()` directly in business/domain code. Always inject `java.time.Clock` to facilitate deterministic SLA tests and simulation clock acceleration.
9. **Exception Hierarchy**:
   - All custom exceptions derive unchecked from `IitsmException` with standard error codes.
   - Core exceptions: `InvalidStateTransitionException`, `TechnicianUnavailableException`, `TechnicianOverloadedException`, `ServiceNotFoundException`, `MissingSlaPolicyException`, `DuplicateIncidentException`, `UnauthorizedOperationException`, `InvalidCredentialsException`, `ValidationException`, `PersistenceFailureException`, `NotificationFailureException`.
   - Handled centrally via `@RestControllerAdvice` mapping to consistent JSON error responses.
   - Never swallow exceptions; no raw `catch (Exception)` except at global boundaries and notification dispatchers.
10. **Deliberate Collection Selection**:
    - State lookup: `EnumMap`.
    - Unassigned queue: `PriorityQueue` with `IncidentUrgencyOrdering`.
    - Escalation chains: `Deque`.
    - Inverted indices / text features: `Map<String, Set<String>>`.
    - Registries: `ConcurrentHashMap`.
    - Getters must return unmodifiable collections (`Collections.unmodifiableList`, `Set.copyOf`, etc.).
11. **Controller & Repository Cleanliness**:
    - Zero business logic in controllers or repositories.
    - Controllers: validate input DTOs, invoke one application service method, map output to persona-specific DTOs.
12. **Persona-Specific DTO Projections**:
    - `EmployeeIncidentView`: stripped of priority, SLA internals, and internal technician notes. Hiding priority is enforced by response type, not UI filtering.
    - `TechnicianIncidentView`: includes queue priority, SLA due times, notes, and state-driven actions.
    - `AdminIncidentView`: complete inspection view including priority factor breakdowns and audit logs.
    - `AdminIncidentDetailView` (added 2026-10-30, ADR-010): the single-ticket inspection projection. It embeds `AdminIncidentView` (`@JsonUnwrapped`) and adds reporter, IT service, support team, public status, triage flag and last-modified details, so the incident list never pays for them.
13. **Application-Layer Authorization**:
    - Ownership and role validation happen in application services via `AccessPolicy`, not solely via web security annotations.
    - An employee requesting another user's ticket throws `UnauthorizedOperationException`.
14. **Concurrency & Thread Safety**:
    - Optimistic locking via `@Version` on `Incident` and `Technician`.
    - Incident assignment runs in transactions to guarantee technician capacity invariants under race conditions.
15. **Code Style & Metrics**:
    - Java 21, Google Java Style formatting.
    - Method lengths under ~25 lines.
    - Zero magic numbers (policies in configuration or domain enums).

### 1.1 Known deviations from the rules (audit of 2026-10-30)
These are facts about the current code, found by reading it. They are not endorsed exceptions; fixing them is future work.

| Rule | What the code does | Where |
|---|---|---|
| 1 Encapsulation | `Incident` has 10 public setters (`setClassificationData`, `setCategory`, `setServiceId`, `setAssignee`, `setResolution`, `setFeedback`, `setReopenCount`, `setSlaTracker`, `setVersion`, `setNeedsTriage`), used by state classes, mappers and the intake pipeline. | `domain/model/Incident.java` |
| 2 Tell, Don't Ask | About a dozen `getStatus() ==/!=` checks in services. Admin reassignment of `IN_PROGRESS`/`PENDING_INFO` tickets bypasses the state machine with a direct `transitionTo(CLASSIFIED)`. | `AdminIncidentService`, `IncidentLifecycleService`, `IncidentIntakePipeline`, `DuplicateDetectionService`, `ReportingService` |
| 7 DI | Field-level `@Value` injection (`minConfidence`, seeder `autoSeed`/`seed`). No field `@Autowired` exists. | `IncidentIntakePipeline`, `CompanySeeder` |
| 8 Clock | `Instant.now()` fallbacks remain in `Incident`, `AgingFactor`, `GlobalExceptionHandler` and the `UserJpaEntity` default. | see files |
| 9 Exceptions | Two empty `catch (Exception ignored) {}` blocks while building triage suggestions. Other broad catches sit at boundaries (notifications, scheduler, config, login attempts). | `AdminIncidentService` |
| 10 Collections | The escalation chain does not use a `Deque`. | `domain/chain/escalation` |
| 15 Style/metrics | Long methods exist (e.g. `IncidentIntakePipeline.processIntake` is ~110 lines); magic numbers remain (`@Scheduled(fixedDelay = 30000, initialDelay = 10000)` in `UnassignedIncidentDispatcher`; keyword confidences 0.90/0.75/0.50/0.20 in `KeywordClassifier`). | see files |
| 6 Dependency direction | **Better than the rule**: the domain package has no Spring or Jakarta imports at all (enforced by `ArchitectureRegressionTest`). JPA annotations live only in `infrastructure/persistence/entity`. | |

---

## 2. Technology Stack & Architectural Decisions (Section 3)
- **Language**: Java 21 (Records, sealed classes, pattern matching, strong static typing).
- **Build**: Maven (`mvn clean verify`; wrapper `mvnw`).
- **Backend Framework**: Spring Boot 3.3.4 (Web, Validation, Data JPA, Security, Mail, Scheduling, Actuator).
- **Persistence**: H2 in-memory (`dev` and `test` profiles), PostgreSQL (`prod` and `demo` profiles), Flyway migrations (`V1__init_schema.sql`, `V2__add_triage_and_exclusions.sql`, `V3__ticket_sequences.sql`, and the Java migration `db.migration.V4__rename_legacy_tickets`). Hibernate runs with `ddl-auto: validate`, so every mapped entity needs a matching Flyway table.
- **ORM / Querying**: Spring Data JPA + Hibernate; JPA Specifications for dynamic multi-criteria search.
- **Security**: Spring Security 6 + stateless JWT (`jjwt` 0.12.6), BCrypt password hashing, method-level security (`@PreAuthorize`), 5-strike account lockout.
- **Text & Machine Learning Intelligence**: Pure-Java TF-IDF + Cosine Similarity + Multinomial Naive Bayes classifier (self-contained, explainable, zero heavy ML libraries), trained at startup from `src/main/resources/sim/corpus.csv`.
- **Notifications**:
  - Email: `JavaMailSender` adapter (`EmailChannel`) targeting Mailpit in dev/demo or external SMTP.
  - In-App: persisted notifications table + Server-Sent Events (`SseEmitterRegistry`, `GET /api/notifications/stream`).
  - SMS: simulated adapter (`SmsSimulatorChannel`).
- **API Documentation**: SpringDoc OpenAPI 3 (`/swagger-ui.html`).
- **Frontend**: Embedded Single-Page Application (Vanilla HTML5, CSS3, JavaScript in `src/main/resources/static/`, zero Node.js/npm dependencies, strict CSP compliant (`script-src 'self'`, so **no inline event handlers**), no CDN resources). Six role-based tabs: Employee, Technician, Team Lead, Admin, Simulation, Reports.
- **Testing**: JUnit 5, Mockito, AssertJ, Spring Boot Test, MockMvc, ArchUnit, GreenMail, embedded PostgreSQL (`io.zonky.test:embedded-postgres`, test scope; real PostgreSQL in the test JVM, no Docker), JaCoCo (gate: bundle LINE coverage >= 70%).
- **Test data**: Datafaker for the deterministic company seed (`CompanySeeder`).
- **Containerization & CI**: Docker Compose (`app`, `postgres`, `mailpit`), GitHub Actions CI running `mvn clean verify -B`.

---

## 3. Architecture & Package Structure (Section 4)

### 3.1 Layered Architecture
```
Frontend (Vanilla JS SPA: Employee | Technician | Team Lead | Admin | Simulation | Reports)
       │ (REST + JWT, SSE)
       ▼
Web Layer (com.iitsm.web)
       │ (Controllers, Persona DTOs, IncidentDtoMapper, GlobalExceptionHandler)
       ▼
Application Layer (com.iitsm.application)
       │ (IncidentService facade, IncidentIntakePipeline, IncidentLifecycleService, AdminIncidentService,
       │  AdminIncidentDetailService, TechnicianAssignmentService, UnassignedIncidentDispatcher,
       │  SlaService, EscalationService, DuplicateDetectionService, ReportingService, ...)
       ▼
Domain Layer (com.iitsm.domain - Pure Java Core, Zero Spring/Jakarta Imports)
       ▲ (Ports / Interfaces: Repositories, Publishers, Strategies)
       │
Infrastructure Layer (com.iitsm.infrastructure)
         (JPA Adapters, Flyway, Security, Notification Adapters, Observers, Schedulers)
```

### 3.2 Package Organization (as it exists)
```
com.iitsm
├── domain
│   ├── model          # Aggregates, entities, value objects (Incident, User hierarchy, ItService, SlaPolicy,
│   │                  #   SlaTracker, PriorityDecision, FactorContribution, ...)
│   ├── state          # IncidentState, 9 state classes, IncidentStates registry, PublicStatusMapper
│   ├── strategy
│   │   ├── assignment      # AssignmentStrategy + 5 strategies, AssignmentStrategyRegistry, CandidateFilter
│   │   ├── classification  # IncidentClassifier, KeywordClassifier, NaiveBayesClassifier, CompositeClassifier, ServiceMatcher
│   │   ├── priority        # PriorityFactor + 6 factors, CompositePriorityCalculator
│   │   ├── similarity      # SimilarityMeasure, CosineTfIdfSimilarity, JaccardSimilarity
│   │   └── nlp             # TextNormalizer, TfIdfVectorizer
│   ├── chain/escalation    # Chain of Responsibility: 4 tier handlers + EscalationChainBuilder
│   ├── policy         # AccessPolicy (port), DepartmentCodeResolver (Strategy) + RegistryDepartmentCodeResolver (ticket numbers)
│   ├── event          # Sealed DomainEvent, DomainEventListener/Publisher, InMemoryDomainEventPublisher
│   ├── repository     # Repository ports (Incident, User, Technician, ItService, SupportTeam, SlaPolicy, Audit, ...)
│   ├── exception      # IitsmException hierarchy
│   └── demo           # DomainLifecycleDemo (excluded from coverage)
├── application
│   ├── service        # Application services (see 3.1), IncidentFactory, UserFactory, AuditService, ...
│   ├── port           # IncidentSearchPort, RealtimeNotificationPort, TokenPort
│   ├── policy         # DefaultAccessPolicy
│   └── dto            # Internal read models (AdminIncidentDetail, TriageQueueItemDto)
├── infrastructure
│   ├── persistence    # CompanySeeder; adapter/ (repository adapters, TicketSequenceRepositoryAdapter), entity/ (JPA entities), mapper/, repository/ (Spring Data), specification/, migration/ (LegacyTicketRenamer, used by Flyway V4)
│   ├── notification   # EmailChannel, InAppChannel, SmsSimulatorChannel, NotificationRouter/Factory, SseEmitterRegistry
│   ├── security       # JWT filter/provider, SecurityConfig, user details
│   ├── scheduling     # SlaMonitoringScheduler
│   ├── observer       # AuditTrailObserver, NotificationObserver
│   └── config         # AdjustableClock, DomainEventConfig, IntelligenceConfig
├── simulation         # SimulationService (scenarios S1-S5), SimulationResult, SimulationStatusDto
└── web
    ├── controller     # Admin, Auth, Employee, Technician, Notification, PublicConfig, Report, Simulation
    ├── dto            # Persona views and request/response DTOs
    ├── mapper         # IncidentDtoMapper
    └── error          # GlobalExceptionHandler
```
There are no `application/command`, `application/query`, `simulation/generator` or `simulation/scenario` packages; the earlier
version of this file listed them but they were never created.

### 3.3 REST surface (base paths)
`/api/auth` (login, register, me) · `/api/employee/incidents` (raise, list, view, provide-info, verify, reject, feedback) ·
`/api/technician` (queue, team-queue, dashboard, acknowledge, start-investigation, notes, request-info, resolve, flag-wrong-domain) ·
`/api/admin` (stats, incidents (repeated `priority` and `category` params; `query` matches title, description and ticket id/name), `incidents/{id}` detail (id or staff display name), assign, override-priority, close, reclassify, duplicates, triage-queue,
technicians, assignment strategies, unlock user, designation) · `/api/admin/reports` (workload, services, sla, mttr, escalations) ·
`/api/notifications` (list, read, SSE stream) · `/api/simulation` (status, speed, advance, reset, run scenario) · `/api/public/config`.

### 3.4 Event-Driven Spine (Observer Pattern)
- Sealed `DomainEvent` hierarchy (17 events, all present): `IncidentCreated`, `IncidentClassified`, `PriorityCalculated`, `PriorityOverridden`, `DuplicateDetected`, `IncidentAssigned`, `ReassignmentRequested`, `IncidentAcknowledged`, `InvestigationStarted`, `MoreInfoRequested`, `IncidentResolved`, `ResolutionVerified`, `IncidentReopened`, `IncidentClosed`, `SlaWarning`, `SlaViolated`, `IncidentEscalated`.
- `DomainEventConfig` discovers every `DomainEventListener` bean and registers it with the publisher. Observers that exist:
  - `AuditTrailObserver`: synchronous recording of `AuditRecord`.
  - `NotificationObserver`: dispatches notifications through `NotificationRouter` (channel failures are isolated).
- **There is no `CorrelationObserver`.** Duplicate detection and linking run inside the intake pipeline through `DuplicateDetectionService` (threshold 0.70 over a 72-hour window). The wired `SimilarityMeasure` is `CosineTfIdfSimilarity` (`IntelligenceConfig`); `JaccardSimilarity` exists but is not wired anywhere.

### 3.5 State Machine & Lifecycle (State Pattern)
`Incident` delegates every action to the `IncidentState` for its current status. Allowed actions per state (from the code):

| State | Allowed actions | Moves to |
|---|---|---|
| `NEW` | classify | `CLASSIFIED` |
| `CLASSIFIED` | assign | `ASSIGNED` |
| `ASSIGNED` | acknowledge, flagWrongDomain | `ACKNOWLEDGED`, `CLASSIFIED` |
| `ACKNOWLEDGED` | startInvestigation, flagWrongDomain | `IN_PROGRESS`, `CLASSIFIED` |
| `IN_PROGRESS` | requestInfo, resolve | `PENDING_INFO`, `RESOLVED` |
| `PENDING_INFO` | provideInfo | `IN_PROGRESS` |
| `RESOLVED` | verify, reject | `VERIFIED`, `IN_PROGRESS` (reject = reopen, increments `reopenCount`) |
| `VERIFIED` | close | `CLOSED` |
| `CLOSED` | none | |

- Illegal actions throw `InvalidStateTransitionException`.
- `PublicStatusMapper` projects the 9 internal states to the employee 5-step stepper: `NEW` (NEW, CLASSIFIED), `ASSIGNED` (ASSIGNED, ACKNOWLEDGED), `IN PROGRESS` (IN_PROGRESS, PENDING_INFO), `RESOLVED` (RESOLVED, VERIFIED), `CLOSED`.
- Admin reassignment (`AdminIncidentService`) returns an `IN_PROGRESS`/`PENDING_INFO` ticket to `CLASSIFIED` by direct transition, outside the state table above (see 1.1).

### 3.6 Intake, Classification & Automatic Assignment (ADR-009)
`IncidentIntakePipeline.processIntake` runs: classify (keyword + Naive Bayes) → match IT service → duplicate detection → 6-factor priority → SLA policy → assign.
- **Auto-assignment gate**: a ticket is assigned automatically only when classification confidence >= `iitsm.classification.min-confidence` (0.60). Otherwise it is flagged `needsTriage`, stays `CLASSIFIED`, appears in the admin triage queue, and **`UnassignedIncidentDispatcher` deliberately skips it** (humans own low-confidence tickets).
- **Why this matters**: Naive Bayes alone is badly overconfident (measured 0.84 to 0.97 on vague text with zero keyword hits), so it must be corroborated by keyword evidence. `CompositeClassifier` therefore caps confidence at 0.55 (-> triage) when: no keyword matched; or exactly one keyword matched outside the title (`KeywordClassifier` 0.50); or the models disagree and keyword confidence is < 0.60; or neither model reaches 0.60. A single keyword **in the title** scores 0.55 and, with Naive Bayes agreeing, passes the gate. Thresholds are named constants in `CompositeClassifier`/`KeywordClassifier`.
- **Capacity and contention**: candidates come from `findAvailableTechnicians()` (null-safe for NULL status/capacity) then `CandidateFilter`; `TechnicianAssignmentService.tryAssignTechnician` runs in `REQUIRES_NEW` and absorbs only `TechnicianOverloadedException`. Optimistic-lock failures surface at commit, so the pipeline and the dispatcher catch `ObjectOptimisticLockingFailureException` and log a WARN. Unexpected exceptions propagate.
- **Dispatcher**: `UnassignedIncidentDispatcher` runs every 30 s over `CLASSIFIED`, unassigned, non-triage tickets; one lost lock race does not abort the cycle; an empty technician pool stops the cycle with a WARN.
- `TechnicianAssignmentService`, `IncidentIntakePipeline`, `UnassignedIncidentDispatcher` all log when no technician has capacity.

### 3.7 Explainable Priority & Its Persistence (ADR-011)
- `CompositePriorityCalculator` combines 6 `PriorityFactor`s (`ServiceCriticality`, `Impact`, `Urgency`, `ReporterDesignation`, `Aging`, `ReporterHistory`; weights sum to 1.0) plus policy override rules, producing a `PriorityDecision` with `factorContributions`.
- The breakdown is persisted: `FactorContributionJpaEntity` maps the existing `factor_contributions` table, owned by `IncidentJpaEntity` as an eager, `@OrderBy("id ASC")` **`Set`** (a `List` would create a second eager bag next to `notes` and raise `MultipleBagFetchException`). `IncidentMapper` writes and reads it. Each incident save deletes and re-inserts at most 6 rows. Tickets saved before 2026-10-30 have no stored rows.
- Manual override (`PriorityDecision.withOverride`) keeps the original factor list and records `overriddenBy`, `overrideReason` and a new `calculatedAt`.

### 3.8 Admin Ticket Inspection (ADR-010)
- Backend: `GET /api/admin/incidents/{id}` -> `AdminIncidentDetailService.getDetail` (read-only; loads through `IncidentLifecycleService`, so the access policy still applies; resolves reporter, assignee, IT service, support team, audit trail; dangling references become `null`) -> `AdminIncidentDetail` (application read model; `lastModifiedAt()` = newest audit record, since `Incident` has no modification timestamp) -> `IncidentDtoMapper.toAdminDetailView` -> `AdminIncidentDetailView`.
- Support team rule: the assignee's own team when assigned, otherwise the owning team of the incident's IT service.
- Frontend: clicking a Ticket ID in the Command Centre table (`.btn-admin-view-ticket` span inside the ID cell, keyboard accessible) opens `#admin-ticket-detail-modal`. Markup is in `index.html` (one empty container per section), styles in `styles.css` (`ticket-detail-*`, `td-*`, `badge-sla-*`, `badge-tier-*`), logic in section "14. Admin Ticket Inspection Dialog" at the end of `app.js` (helpers prefixed `td*`; `openAdminTicketDetailModal`, `closeAdminTicketDetailModal`, `setupTicketDetailListeners`).
- Behaviour: loading/error/content panels; every dynamic value goes through `escapeHtml`; closes on X, footer Close, backdrop click, Escape and sign-out; returns focus to the clicked ID; discards late responses (`STATE.adminTicketDetail.requestSeq`); footer Override/Reassign close the dialog and call the existing `openPriorityOverrideModal`/`openReassignModal`.
- SLA display uses the real `SlaStatus` values (`ON_TRACK`, `AT_RISK`, `WARNING`, `BREACHED`, `MET`); a `PENDING_INFO` ticket also shows a "Clock paused" pill. Service criticality is `BusinessCriticality` `TIER_1..4`.
- The Command Centre table reads `priorityDecision.priority` and `slaTracker.resolutionDueAt` (the list DTO has no `priority`/`slaDeadline` fields).

### 3.9 Multi-Select Incident Filters (ADR-012)
- Backend: `GET /api/admin/incidents` binds `priority` and `category` to `Set<Priority>` / `Set<IncidentCategory>` (repeated params, e.g. `?priority=P1&priority=P3`; one value and blank still work). `SearchService.IncidentSearchCriteria` and `IncidentSearchPort.SearchCriteria` carry `priorities` / `categories`; `IncidentSpecifications.hasPriorityIn` / `hasCategoryIn` use `IN` predicates. Values inside one filter are OR-ed, the two filters are AND-ed, null or empty means no filter.
- Frontend: `#multiselect-category` and `#multiselect-priority` replace the native selects (markup in `index.html`, `.custom-multiselect` / `.multiselect-*` / `.custom-checkbox` styles in `styles.css`, `initMultiSelect(containerId, options, onChange)` in section 15 of `app.js`). A selected option shows a blue border with a solid blue dot (`var(--primary)`); the menu stays open while toggling and closes on outside click, Escape or Tab. The **Filter** button still triggers the search; toggling only updates the selection and the trigger label.
- Option values are the real enum names (the old `ACCESS` option never existed in `IncidentCategory`); `MultiSelectFilterMarkupTest` compares the markup with the enums.

### 3.10 Ticket Numbering (ADR-013) and Employee Privacy of the Activity History (ADR-014)
- **Format**: the incident id is `DEPT-YEAR-SEQUENCE`, e.g. `FIN-2026-000142`: reporter's department code (2-4 letters), creation year from the injected `Clock`, zero-padded sequence (6+ digits, never truncated) that restarts at `000001` each year, counted per department code. Only data fixed at creation is in the id, so it never changes. `TicketNumber` (value object) formats/parses it; `DepartmentCodeResolver` / `RegistryDepartmentCodeResolver` map the free-text department (ENG, FIN, HR, SAL, OPS, CSU, LEG, EXE; unregistered names use their first three letters; blank, non-employee or letterless -> `MISC`).
- **Generation**: `IncidentIntakePipeline` -> `IncidentFactory.createIncident(title, description, reporter)` -> `TicketNumberGenerator.nextFor(reporter)` -> `TicketSequenceRepository.next(code, year)` (`TicketSequenceRepositoryAdapter`, table `ticket_sequences`, atomic `UPDATE ... + 1` inside the intake transaction, propagation MANDATORY). **No gaps**: a rolled-back intake gives its number back. Cost: tickets of one department and year are created one after another; different departments do not wait for each other. Only creating a missing counter row uses a separate short transaction (`TicketSequenceRowCreator`). `User.getDepartment()` is `null` unless overridden (`Employee`).
- **Migration of old ids**: Flyway Java migration `V4__rename_legacy_tickets` runs `LegacyTicketRenamer`: renames `INC-XXXXXXXX` tickets (department of the reporter, creation year, creation order, continuing after existing numbers), re-creates the nine foreign keys to `incidents(id)` with `ON UPDATE CASCADE` (names read from JDBC metadata), updates `technician_active_incidents`, `incident_linked_ids.linked_incident_id`, `audit_records.incident_id`, rewrites ids quoted in `notifications` and `audit_records.description`, advances the counters. All or nothing on PostgreSQL; a no-op on a database without old ids. Old `INC-` links stop working after it.
- **Staff display name**: `TicketDisplayName.of(priority, id)` gives `P2-FIN-2026-000142` (hyphens only). It is computed on every render into `displayId` on `TechnicianIncidentView` and `AdminIncidentView` (and so `AdminIncidentDetailView`), never stored; an override changes it, the id stays. `EmployeeIncidentView` has no such field. `loadIncident` in `IncidentLifecycleService` and `AdminIncidentService` and the admin text search accept the plain id or a complete display name (`TicketDisplayName.canonicalId`); the text search also matches the ticket id.
- **Web**: `ticketNameHtml(ticket, asBlock)` in `app.js` renders the display name next to the plain id in technician cards, the two team lead lists, the Command Centre table (second line under the clickable id) and the admin detail dialog (`#ticket-detail-display-name`). The plain id stays the link and the key of every button. Employee screens show the plain id only. `TicketDisplayNameMarkupTest` guards this.
- **Employee activity history (ADR-014)**: `IncidentDtoMapper.toEmployeeView` shows only audit actions in `EMPLOYEE_VISIBLE_AUDIT_ACTIONS` (allow-list). Priority calculation/override, SLA warning/breach and escalation entries are hidden; new event types are hidden by default and `EmployeeAuditTrailPrivacyTest` forces every `DomainEvent` type to be classified.

---

## 4. Testing & Verification Notes
- Suite: 380 tests, 0 failures. Layers: domain unit tests, application service tests (Mockito), persistence integration tests (`@SpringBootTest`, H2), migration tests on H2 and on a real embedded PostgreSQL, MockMvc controller tests, ArchUnit architecture tests, regression tests (`@Tag("regression")`), and static-resource contract tests.
- **Embedded PostgreSQL** (`com.iitsm.testsupport.TestPostgres`): `LegacyTicketMigrationPostgresTest` and `TicketNumberingPostgresRegressionTest` run the migrations and the whole application on PostgreSQL 14 started in the test JVM. It runs from `target/embedded-pg` with a port bound on 127.0.0.1 and retries, because on the Windows development machine PostgreSQL could not listen when extracted to the Temp folder ("could not listen on IPv4 address 127.0.0.1: Permission denied", connection refused). First start downloads the binaries (Maven Central) and can take about a minute.
- The Maven wrapper's 3.9.9 download is incomplete on the development machine; builds there were run with the Maven 3.10.0 already in `~/.m2`.
- **Integration tests share one in-memory H2 database** (`testdb`) and the `test` profile does not auto-seed. Tests that need users/services call `CompanySeeder.seedCompanyData()` when `userRepository.count() == 0`. `IncidentRepositoryAdapterTest` still depends on another class having seeded first and fails when run alone.
- `AdminTicketDetailModalMarkupTest` guards the `index.html` / `styles.css` / `app.js` contract: required element ids, the dialog is hidden and `role="dialog"` by default, required CSS classes, **no inline `on...=` handlers and no `http(s)://` or `@import`** in the static files, every element id the script looks up exists, and the admin table reads real API fields.
- Front-end behaviour was verified outside Maven in headless Edge: a DOM harness with captured API JSON (61 checks: null data, out-of-order responses, hostile HTML) and a DevTools-protocol run against the live app with real mouse/keyboard input (21 checks, zero console errors). Those scripts are **not in the repository**.
- Tests that pin a behaviour on purpose: `CorrectnessRegressionTest.F5` (an ambiguous ticket with one keyword only in the description must go to triage), `AutoAssignmentRegressionTest` (clear ticket assigns at intake; vague ticket stays in triage and the dispatcher skips it; waiting ticket is assigned once capacity frees).

---

## 5. Working Conventions
- **Line endings**: working copies on this Windows machine are CRLF (git `autocrlf`). When editing files with scripts, preserve CRLF; mixed endings produce noisy diffs.
- **Static asset cache-busting**: `index.html` references `/styles.css?v=3.4` and `/app.js?v=3.4`. Bump both when either file changes.
- **No inline handlers, no CDNs** in `index.html` (CSP). Use `addEventListener` and vanilla CSS/SVG/Unicode.
- **Utility colour classes in tables**: `.table td` sets a colour with higher specificity than `.text-red`; scoped rules (`.table td.text-red`, `.text-cyan`, `.text-muted`) exist so the utilities win inside cells.
- **Non-phase work** (bug fixes, features) is documented with an ADR in `docs/DECISIONS.md` and a named summary file (`docs/fix-auto-assignment-summary.md`, `docs/admin-ticket-inspection-summary.md`, `docs/multi-select-filters-summary.md`) rather than a `phase-N-summary.md`.
- **Commits are local** (`basic-prototype` branch); nothing is pushed unless explicitly requested.
- Seeded passwords, ports and profiles are listed in section 0; do not hard-code the old `Admin@123` (wrong).

---

## 6. Change Log

### 2026-10-30
**Fix: automatic ticket assignment restored** - commit `a636e33`, ADR-009, `docs/fix-auto-assignment-summary.md`
- Cause: the classifier capped confidence at 0.55 for any weak keyword score, so almost every short ticket went to human triage, which the dispatcher never retries. The technician pool was healthy (confirmed by running the app).
- `KeywordClassifier`: single keyword in the title scores 0.55. `CompositeClassifier`: evidence-based triage cap, named constants.
- `TechnicianAssignmentService`, `IncidentIntakePipeline`, `UnassignedIncidentDispatcher`: narrowed catch blocks, WARN logs for an empty pool, per-ticket lock handling.
- `SpringDataUserRepository.findAvailableTechnicians`: null-safe for NULL availability status and NULL capacity.
- Tests: `CompositeClassifierTest` (9), `TechnicianAssignmentServiceTest` (4), `UnassignedIncidentDispatcherTest` (4), `AvailableTechniciansQueryTest` (2), `AutoAssignmentRegressionTest` (3).

**Feature: admin ticket inspection dialog** - commit `44d1365`, ADR-010 and ADR-011, `docs/admin-ticket-inspection-summary.md`
- New: `AdminIncidentDetail`, `AdminIncidentDetailService`, `AdminIncidentDetailView`, `IncidentDtoMapper.toAdminDetailView`, `FactorContributionJpaEntity`, the dialog markup/CSS/JS described in 3.8.
- Changed: `AdminController#getIncidentDetails` now returns `AdminIncidentDetailView` (existing JSON fields unchanged); `IncidentJpaEntity` and `IncidentMapper` persist the factor breakdown.
- Bugs fixed on the way: priority factor breakdown was never saved; the Command Centre table showed a fake P3 and no SLA deadline for every ticket; unescaped ticket ids in the Override/Reassign buttons; missing badge styles for `CLASSIFIED`, `ACKNOWLEDGED`, `VERIFIED`; breached deadlines were never red.
- Tests: `AdminIncidentDetailServiceTest` (5), `AdminIncidentDetailMapperTest` (3), `AdminControllerTest` (+1), `FactorContributionPersistenceTest` (3), `AdminTicketDetailModalMarkupTest` (7).
- Spec corrections made while building: password is `Password123!`; SLA status names and criticality names differ from the original spec; `Incident` has no "last modified" field; resolutions have no "summary" field (`stepsTaken` is shown as "Corrective actions").

**Documentation**: this file reconciled with the code (package structure, observers, REST surface, state table, stack, deviations).

### 2026-10-31
**Feature: multi-select Category and Priority filters in the Admin Command Centre** - ADR-012, `docs/multi-select-filters-summary.md` (uncommitted at time of writing)
- Changed: `AdminController#searchIncidents`, `SearchService`, `IncidentSearchPort`, `IncidentSearchAdapter` (set-valued criteria), `IncidentSpecifications` (+`hasPriorityIn`, `hasCategoryIn`), `index.html`, `styles.css` (new multi-select section), `app.js` (section 15, `searchAdminIncidents`); asset cache-bust now `?v=3.3`.
- Spec corrections made while building: `ACCESS` is not an `IncidentCategory` (nine real values offered instead); `--accent-blue` does not exist (`--primary` used).
- Tests: `IncidentSpecificationsTest` (+4), `AdminControllerTest` (+1), `MultiSelectFilterMarkupTest` (9, new). Suite 248 -> 262.
- Verified in headless Edge against the running app (harness 30 checks, DevTools run 27 checks, zero console errors); those scripts are not in the repository.

**Feature: department based ticket numbers** - ADR-013, `docs/ticket-nomenclature-phase-1..5.md` (commits `27d7cf0`, `7ac7ac4`, `3b24b0b`, `20fd419`, `75346cb`, `d26c8c8`, then the Phase 5 commit)
- New: `TicketNumber`, `TicketDisplayName`, `DepartmentCodeResolver`, `RegistryDepartmentCodeResolver`, `TicketSequenceRepository`, `TicketNumberGenerator`, `TicketSequenceRepositoryAdapter`, `TicketSequenceRowCreator`, `TicketSequenceJpaEntity`, `TicketNumberConfig`, `LegacyTicketRenamer`, Flyway `V3` and `V4`, `displayId` on the staff views, the web display (section 3.10).
- Changed: `IncidentFactory` (takes the reporter, no more random id), `IncidentIntakePipeline` (one line), `User.getDepartment()`, both `loadIncident` helpers, `SearchService`, `IncidentSpecifications.containsText` (also matches the ticket id), `index.html` / `styles.css` / `app.js`; asset cache-bust now `?v=3.4`; `pom.xml` (test dependency `embedded-postgres` 2.0.7).
- Decisions along the way: gap-free numbers over throughput (a first design reserved numbers in a separate transaction and left gaps); the priority is only in a derived staff display name, never in the stored id; `CorrectnessRegressionTest.F7` now races reporters from two departments, because same-department tickets no longer run in parallel (the second would be linked to the first as a duplicate).
- Bugs found by the new tests and fixed: first counter row created with `merge` overwrote a concurrent row (duplicate numbers); PostgreSQL could not listen when run from the Temp folder.

**Fix: employees could read their ticket's priority** - ADR-014 (`ac10b30`)
- The employee activity history showed audit entries such as "Calculated priority P3 (Score: 0.3975)". Now filtered by an allow-list (section 3.10). Tests: `EmployeeAuditTrailPrivacyTest` (5).

---

## 7. Known Issues & Open Items
1. **Titles and descriptions are re-escaped on every save/load** (pre-existing, unfixed). `Incident`'s constructor HTML-escapes input and `IncidentMapper.toDomain` re-runs it on each load, so text containing `& < > " '` degrades progressively (`Can't` becomes `Can&#x27;t`, later `Can&amp;#x27;t`). The UI is safe (it escapes on output). The right fix is to escape only on output, not in the domain constructor.
2. **Actors are stored as emails, not user ids**: note authors, the priority-override actor and "Resolved by" show e.g. `tech.network@nimbusfinserve.com`. Audit `actorId` mixes ids (`SYSTEM`, `EMP-DEMO-01`) and emails.
3. A priority override on a `CLOSED` ticket is accepted; whether that is allowed is undecided.
4. Tickets without any clear keyword but a clear IT-service match (e.g. "printer thing") still go to triage; the matched service is not used as classification evidence.
5. A keyword that appears in a title by accident, with Naive Bayes agreeing, auto-assigns.
6. Admin dialog: keyboard focus is not trapped inside the dialog; composite score shows 2 decimals while weighted contributions show 3.
7. Tickets saved before the factor persistence fix show "Factor breakdown was not recorded for this ticket".
8. Rule deviations listed in section 1.1 (public setters on `Incident`, status checks in services, `Instant.now()` fallbacks, empty catch blocks, magic numbers, no formatter/Checkstyle).
9. `CLAUDE.md` previously described a React/Vite/Tailwind frontend, a `CorrelationObserver`, `application/command|query` packages and `simulation/generator|scenario` packages. None exist; they were removed from this file.
10. An unknown enum value in a query parameter (e.g. `/api/admin/incidents?priority=P9`) returns HTTP 500 instead of 400: `GlobalExceptionHandler`'s catch-all handles the type-mismatch exception. Pre-existing, affects every enum request parameter.
11. Multi-select filters: the selection is not remembered across reload or sign-out, and does not search on change (the **Filter** button does); focus is not trapped in the open menu.
12. Ticket numbers: the department is free text (a typo gets its own fallback code and counter); technicians or admins raising a ticket get `MISC`; `DomainLifecycleDemo` still uses a hard-coded `INC-1001`; tickets of one department and year are created one after another (gap-free numbering); old `INC-` links, bookmarks and ids in already-sent emails stop working after migration V4; user-written text that mentions an old id is not rewritten.
13. Employees still see the "marked as duplicate of ..." and "technician requested reassignment: ..." entries of the activity history (not priority related, so left alone by ADR-014); the first can name another ticket.
14. Notification and email texts for staff still use the plain ticket id, not the display name; the triage queue (`TriageQueueItemDto`) has no `displayId`.
15. `GET /favicon.ico` returns HTTP 500 (JSON error) instead of 404, because `GlobalExceptionHandler`'s catch-all handles the missing static resource; the page declares no favicon so every browser load logs a failed request. Pre-existing, same cause as item 10.
