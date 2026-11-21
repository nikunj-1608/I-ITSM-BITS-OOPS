# Feature summary: Admin ticket inspection dialog

## What was built
Clicking a Ticket ID in the Command Centre incident table opens a rectangular dialog showing everything the
system knows about that ticket: header pills, description, reporter, IT service and criticality, support team and
technician, lifecycle timestamps, SLA tracking, the explainable priority matrix, investigation notes and
resolution, customer feedback, linked tickets and triage flag, and the full audit trail. The footer offers
shortcuts to the existing Override Priority and Reassign modals.

## Where each piece lives and why

| Layer | Piece | Role |
|---|---|---|
| Web | `AdminIncidentDetailView` | Persona DTO for the dialog. Embeds `AdminIncidentView` via `@JsonUnwrapped`, so existing JSON fields are unchanged. |
| Web | `IncidentDtoMapper.toAdminDetailView` | Projects the application read model to the DTO, reusing `toAdminView`. |
| Web | `AdminController#getIncidentDetails` | One call to one application service; no repositories (ArchUnit A1). |
| Application | `AdminIncidentDetailService` | Read-only assembly of reporter, assignee, service, support team and audit trail. Access control inherited from `IncidentLifecycleService`. |
| Application | `AdminIncidentDetail` | Internal read model; derives "last modified" from the newest audit record. |
| Infrastructure | `FactorContributionJpaEntity`, `IncidentMapper` | Persist the priority factor breakdown that was previously lost (see ADR-011). |
| Frontend | `index.html`, `styles.css`, `app.js` | Dialog markup, styles and logic (vanilla JS, no CDN, CSP-compliant). |

Decisions are recorded in ADR-010 (dedicated read model) and ADR-011 (factor persistence).
No class was added to `com.iitsm.domain`, so the PlantUML diagrams in `docs/uml/` are unchanged.

## Corrections to the original specification
The spec was checked against the code before building. Differences that shaped the implementation:

- Seeded admin password is `Password123!`, not `Admin@123`.
- SLA statuses are `ON_TRACK`, `AT_RISK`, `WARNING`, `BREACHED`, `MET`. There is no `PAUSED` status; a paused clock is
  shown as a separate "Clock paused" pill when the ticket is `PENDING_INFO`.
- Criticality is `TIER_1..4` with descriptions ("Mission Critical" etc.), not the names in the spec.
- Priority factor names are `ServiceCriticality`, `Impact`, `Urgency`, `ReporterDesignation`, `Aging`, `ReporterHistory`
  and are humanized for display.
- Notes use `noteText` (plus an `internalOnly` flag); resolutions have `rootCause`, `stepsTaken`, `preventiveAction`,
  not a summary field. `stepsTaken` is shown as "Corrective actions".
- `Incident` has no modification timestamp, so "Last modified" is the latest audit entry.
- `cursor-pointer` and `hover:underline` are Tailwind names that do not exist in the vanilla stylesheet; real
  `.ticket-link` styles were added.

## Bugs found and fixed along the way
- **Priority factor breakdown was never persisted** (ADR-011): the admin and technician views always showed an empty
  breakdown after the first save.
- **Command Centre table read non-existent fields** (`priority`, `slaDeadline`, `slaBreached`): every ticket showed a
  fake "P3" and no SLA deadline. It now reads `priorityDecision.priority` and `slaTracker.resolutionDueAt`.
- Unescaped ticket IDs in the table's Override and Reassign buttons (attribute injection).
- Missing badge styles for CLASSIFIED, ACKNOWLEDGED and VERIFIED statuses.
- Utility colour classes (`text-red`, `text-cyan`, `text-muted`) were overridden by the `.table td` rule, so breached
  deadlines were never red.

## Tests added (suite: 248 tests, `mvn verify` green, JaCoCo checks met)
- `AdminIncidentDetailServiceTest` (5), `AdminIncidentDetailMapperTest` (3), `AdminControllerTest` (+1 detail JSON).
- `FactorContributionPersistenceTest` (3): round trip, no duplicates on re-save, override keeps the breakdown.
- `AdminTicketDetailModalMarkupTest` (7): required element ids, hidden-by-default accessible dialog, stylesheet
  classes, no inline handlers or external URLs, every script element reference exists, table reads real API fields.

Beyond the Maven suite, the work was verified in a real browser (headless Edge over the DevTools protocol) against
the running application: 21 end-to-end checks passed with real mouse and keyboard input and zero console errors,
including a live priority override through the footer shortcut. A 61-check DOM harness with captured API data
covers edge cases (null fields, out-of-order responses, hostile HTML). Those browser scripts are not part of the
repository.

## Known gaps
- **Titles and descriptions are re-escaped on every save and load** (pre-existing): the `Incident` constructor
  HTML-escapes its input and `IncidentMapper.toDomain` calls it on each load, so text containing `& < > " '`
  degrades progressively. The dialog is safe (it escapes on output), but the stored text is corrupted. The fix belongs
  in the domain/web boundary and was left out of scope.
- **Actors are stored as emails** (pre-existing): note authors, the override actor and "Resolved by" show
  `tech.network@nimbusfinserve.com` rather than a user id.
- Tickets saved before ADR-011 have no factor rows; the dialog says the breakdown was not recorded for them.
- Composite score is shown with two decimals and weighted contributions with three, so displayed sums can differ from
  the total in the last digit (the full value is in the score's tooltip).
- Keyboard focus is not trapped inside the dialog; Tab can reach the page behind it.
- Override and Reassign are offered for every ticket state, as in the table, and the dialog does not disable them.
  During end-to-end testing a priority override on a `CLOSED` ticket was accepted; whether that should be allowed is a
  product decision that was not changed here.
