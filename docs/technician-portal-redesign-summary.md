# Technician Portal Redesign (ADR-051)

Date: 2026-11-21. Branch `bug-fixes`. Nothing committed, at the user's instruction.

**Status: implemented, verification incomplete.** The markup contract is tested and passing; the full Maven gate and every
browser check could not be run (see "Verification"). Do not read this document as "verified in a browser".

## What changed

The Technician tab (`#console-technician`) was rebuilt from the product owner's mockup (`stitch_i_itsm_technician.zip`),
**front end only and Technician tab only**: no new endpoint, DTO field, domain method or migration; no change to the header,
the navigation, the sign-in screen or any other tab. Data: `GET /api/technician/queue`, `GET /api/technician/team-queue`,
`GET /api/technician/dashboard` and the existing action endpoints.

| Mockup element | Built as | Data |
|---|---|---|
| Capacity banner, "MODERATE LOAD (43%)" | `.tp-capacity`, three bands (< 40 % Available, 40-75 % Moderate, > 75 % Heavy), bar colour follows, `aria-valuenow` | dashboard counts and `maxConcurrentTickets` |
| Four counters with coloured top borders | `.tp-kpi-grid`; ids `tech-stat-*` kept | dashboard |
| "Under Active Investigation" panel | `#tp-spotlight`, own `IN_PROGRESS` ticket picked by: breached first, earliest deadline, higher priority; hidden when none, in team mode, shown regardless of the status filter | queue |
| Queue as a table | `.tp-table`: Ticket ID, Priority, Status, Requester, Date Raised, Deadline, Details; one `tbody[data-ticket-card]` per ticket | `TechnicianIncidentView` |
| Row detail | hidden `.tp-detail-row`: description, facts, last 3 notes, resolution, the action bar; one open row, kept across reloads | `TechnicianIncidentView` |
| "Assigned to Me" / "Team Queue" | segmented control, `aria-pressed`, ids kept, label "Unassigned Team Tickets" kept | queue / team-queue |
| Filter dropdown, refresh | `#tech-queue-filter` (status filter, options unchanged), `#btn-refresh-tech-queue` | client-side filter |
| Request Extra Info, Mark Resolved | in the spotlight and in the row's action bar, through the existing dialogs, only when `allowedActions` allows | `allowedActions` |

The nine action buttons (`btn-tech-ack`, `-investigate`, `-note`, `-reqinfo`, `-wrongdomain`, `-resolve`, `-suggest`, `-promote`,
`-reassign`) keep their class names, `data-id`, handlers and visibility rules.

## Dropped from the mockup (no backend, or against project rules)

Tailwind CDN, Google Fonts and inline `onclick` (CSP, no CDN); the six extra navigation tabs (Incidents, Service Requests,
Problem Management, Change Orders, Asset Tracking, Knowledge Base: not in the specification); "Take Ownership" and "Ping
Requester" (no endpoint; assignment is automatic or by a lead or administrator); "Investigation Progress 64 %", "Impact",
diagnostic logs, "Affected System", a location (no data); the fake ticking SIM CLOCK; invented ticket ids (real ids are
`DEPT-YEAR-SEQUENCE`, shown with the staff display name beside them); the detail modal (replaced by the expanding row).

## Where the code is

| Part | File | Marker |
|---|---|---|
| Markup | `src/main/resources/static/index.html` | the `console-technician` section |
| Styles | `src/main/resources/static/styles.css` | the block `/* Technician portal (tp-) */ ... /* End Technician portal */`, every selector starts with `.tp-` or `#tp-` |
| Logic | `src/main/resources/static/app.js` | section 7, helpers prefixed `tp`, constants `TP_*`; `STATE.technician`; `resetTechnicianView` (called by `handleSignOut`) |
| Asset version | `index.html` | `?v=5.1` |

Two small hooks sit outside section 7: the `technician: { requestSeq, expandedId }` entry in `STATE` and the
`resetTechnicianView()` call in `handleSignOut`.

## Tests

- **New `TechnicianPortalMarkupTest` (19):** the old ids are still there; the spotlight, the segmented toggle, the seven columns,
  the expandable rows, the helper functions and named load bands; the spotlight rule; `setTechQueueMode` and
  `focusRequestedTicket` follow the new state; every `btn-tech-*` class is rendered and wired; buttons still follow
  `allowedActions`; no inline handlers, no external URL, no ticket text without `escapeHtml`; every new style rule is in the
  marked block with a `tp-` selector; the shared rules `.kpi-card`, `.capacity-bar`, `.capacity-fill`, `.sla-timer-pill` and
  `.ticket-focus` are unchanged; and four SHA-256 guards (recorded before the work) prove that `index.html` outside the tab,
  `styles.css` outside the block, and the Employee and Team Lead scripts are byte-for-byte unchanged (asset versions
  normalised).
- **Changed on purpose:** the 14 tests that pin the asset version (5.1); `TeamLeadReassignMarkupTest`,
  `SuggestedResolutionMarkupTest` and `TicketDisplayNameMarkupTest` (they pinned the old renderer's internals; the same rules are now
  asserted against `tpPermissions`, `tpActionsHtml` and `tpRowHtml`).
- **Browser scripts** (`scripts/live-checks/`): `action_buttons_browser.py`, `suggestions_browser.py`, `triage_browser.py`,
  `team_view_browser.py` and `team_lead_reassign_browser.py` open the row before clicking and read `aria-pressed`; the new
  `technician_portal_browser.py` checks the page, the rows, the actions, the spotlight against the API, the toggle and sign-out.

## Verification

| Check | Result |
|---|---|
| The 153 `*MarkupTest` classes (including the 19 new tests), compiled with `javac` and run through the JUnit launcher against the live `src/main/resources` files | **153 of 153 pass** |
| The same structure checks replicated in a script before that | passed |
| `mvn verify` (full gate), `mvn test` | **not run**: Maven hangs at "Compiling 420 source files" on this machine (the Quick Heal wedge in the project notes); an interrupted compile also left `target/classes` partial, so the next Maven run rebuilds it |
| JavaScript syntax | **not parsed by an engine** (no Node; Edge hung). Brace, parenthesis and backtick balance and the text tests only |
| Application start, `technician_portal_browser.py` and the five updated browser scripts | **not run**: the application stalled at start-up and headless Edge hung (same cause) |
| Mutation checks, before/after screenshots of the other tabs | **not run** |
| CI on JDK 21 | not run |

## Known limits

The open row is not remembered across a page reload. "Time left" uses the browser clock (the simulation time only for
administrators), so it can disagree with a simulated server clock; the server's `slaStatus` still decides "breached". The
table scrolls sideways inside its card below 760 px. No paging (as before). The bands 40 % and 75 % are my choice. The
spotlight shows one ticket even when several are in progress. The seeded demo has few tickets per technician, so the table
with many rows has not been looked at.

## Next steps

Restart the machine (or clear the wedged Java processes), then run: `mvn -o clean verify`; start the application; run
`technician_portal_browser.py`, `action_buttons_browser.py`, `team_lead_reassign_browser.py`, `team_lead_refresh_browser.py`,
`suggestions_browser.py`, `triage_browser.py` and `team_view_browser.py` in Edge; look at every other tab once to confirm nothing
moved; fix what these find; then update the verification table above.
