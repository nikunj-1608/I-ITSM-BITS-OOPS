# Employee Portal Redesign (ADR-050)

Date: 2026-11-20. Branch `bug-fixes`. Nothing committed, at the user's instruction.

## What changed

The Employee Portal tab (`#console-employee`) was rebuilt from the product owner's mockup, **front end only**: no new endpoint,
DTO field, domain method or migration. Everything on the screen comes from `EmployeeIncidentView` through the existing
employee endpoints (`GET/POST /api/employee/incidents`, `.../{id}/provide-info`, `verify`, `reject`, `feedback`).

| Mockup element | Built as | Data |
|---|---|---|
| Privacy banner | `#emp-privacy-banner`, "ENFORCED" pill, no policy id | static text |
| Sub-navigation | "Report Issue" (scrolls to the form, focuses the title), "My Active Tickets", "My History" with count badges | one list, filtered in the browser (open = not RESOLVED/CLOSED) |
| "My Ticket History (N tickets)" | `#btn-emp-open-history`, opens My History | count of RESOLVED/CLOSED |
| Four KPI cards | lifetime, active handling (open, not awaiting my reply), action required (`allowedActions` has `provideInfo`), resolved & closed; bar = count / total | the list |
| Ticket list | compact `button.btn-emp-select[data-id]` rows: id, public status badge, title, relative time | `id`, `status`, `title`, `createdTime` |
| Featured ticket | id, badge, "Raised ...", title, description, the technician's pending question, resolution text, "Assigned to", "Last update" | `assigneeName`, newest `auditTrail` timestamp, `resolutionText` |
| Featured actions | Provide info / Confirm Fixed / Reject fix, each from `allowedActions` only, opening the existing dialogs | `allowedActions` |
| Live Ticket Tracking | five public steps, ACTIVE pill, "N% complete", time and text of the newest matching history entry, "Reopened N time(s)", collapsible activity history | `status`, `auditTrail`, `reopenCount` |
| Report form | kept: validation, counter, POST; new submit guard; the new ticket is selected after submit | `POST /api/employee/incidents` |
| Quick-fill chips | relabelled with the team that handles the category; texts rewritten so the real classifier agrees | `EMP_TEMPLATES` |

Selection rule: the selected ticket is kept across reloads while it exists; otherwise the newest ticket awaiting my reply,
else the newest open ticket, else the newest ticket. Switching to a view that does not contain the selected ticket features
the newest ticket of that view.

Guards: `STATE.employee.requestSeq` discards late answers; `resetEmployeeView` runs on sign-out; `submitInFlight` stops a
second submit (Enter twice); rows and buttons keep `btn-emp-...` classes and `data-id` so `beginWrite`/`restoreFocus` work;
dialogs go through `openModal`/`closeModal`. Styles: "Employee portal (ADR-050)" section at the end of `styles.css`
(prefix `emp-`); one column below 1024 px, stacked rows and buttons below 640 px. Asset version 5.0.

## Dropped from the mockup, and why

| Element | Reason |
|---|---|
| Priority badge ("Priority Blocker"), "(Tier 2)" labels | rule 12 / ADR-014: priority is not in the employee type |
| Target SLA box | rule 12: SLA internals are not in the employee type |
| Gateway box | no employee data for it |
| "AI Smart Classifier Routing / Instant Route" preview (`updateAiPreview`, `#emp-ai-preview`) | no endpoint; the old client-side guess used its own word list, disagreed with the real classifier (ADR-034) and named people who do not exist |
| Knowledge Base and Service Catalog tabs | no employee endpoint (the knowledge base of ADR-042 is staff only) |
| Header clock and "SIM ACTIVE" pill | simulation internals, not employee data |
| "SEC-POL-8824" | invented policy id |
| "Total submissions" style flavour text | claims we do not compute |

## Fixed on the way

- The old card read `t.category` and `t.createdAt`, which the API never sends (every card showed GENERAL and "--"); only `createdTime` and real fields are read now.
- The old "View Timeline" dialog (`openEmployeeDetailsModal`) read `incident.auditRecords` (the field is `auditTrail`), so it was always empty. It is removed; Live Ticket Tracking shows the history.
- The template chips named invented people ("Elena Rostova", "David Kim", ...) and a "Tier 4" gateway; they now name real teams, and the payment template no longer ties three categories (it would have gone to triage).

## Tests

- New `EmployeePortalMarkupTest` (9): every id the employee script looks up exists; the employee markup (outside the banner) has no priority, P1..P4, SLA, gateway, tier, knowledge base, service catalog or classifier preview; the banner says what is hidden; the script reads only `EmployeeIncidentView` fields; the three buttons are gated on `allowedActions`; the tracker mapping; request sequence, sign-out reset and submit guard; no inline handlers, the `emp-` CSS section with phone-width rules, asset version 5.0; **each chip text is classified by the composite classifier (keyword + Naive Bayes trained on the corpus) into a category of the team its label names, at confidence >= 0.60**.
- Changed on purpose: `FrontEndClosureMarkupTest` J4 now reads `empFeaturedActionsHtml` (the buttons moved there); `TicketDisplayNameMarkupTest` checks the whole employee script section instead of two functions; the 12 tests that pin the asset version (5.0).
- Suite: __SUITE__.

## Live checks

- `scripts/live-checks/employee_portal_browser.py` (new, Edge, real login as the employee; the technician side through the API): __PORTAL__.
- `scripts/live-checks/action_buttons_browser.py` (updated: the employee steps select the ticket in the list and use the featured card's buttons; "View timeline" replaced by the tracker check): __BUTTONS__.

## Build environment note

On 2026-11-20 the repository's own `target/` folder could not be used for Spring tests: test JVMs from earlier runs (and every
new one) hung inside a Windows file-open call while Spring scanned `target/classes`, and could not be killed (they stay as
exited processes with one thread in the kernel). The plain markup tests ran in the repository; the full `mvn -o clean verify`
and the app for the live checks ran from a fresh copy of `pom.xml` and `src` in a sibling folder (identical sources, checked
with `diff -r`). A reboot should release the stuck processes.

## Known gaps

- "Last update" and the tracker times use only the history entries an employee may see (staff-only events such as an SLA warning do not move it).
- Relative times use the browser clock; after the simulation clock was advanced past it they fall back to the date.
- The selection and the sub-view are not remembered across a page reload.
- The ticket-created callout (demo walkthrough, `displayTicketCreatedCallout`) is unchanged apart from removing an unused variable that named an invented administrator; it still tells the employee when a ticket went to the administrator's triage queue.
- There is still no JavaScript unit-test runner; behaviour is covered by the Edge scripts only, in Microsoft Edge only.
