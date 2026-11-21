# Admin Search Filters (ADR-049)

Completes the course specification's "Search, Filtering and Reporting" item: an administrator can filter incidents by
Incident ID, Date, Status, Priority, Category, Service, Technician and SLA status, in the API and in the Admin Command
Centre. Built in four approved phases on 2026-11-20. **Nothing committed** (the working tree also holds the
uncommitted authentication-hardening work, ADR-046 to ADR-048).

## What was built

| Filter | API parameter (`GET /api/admin/incidents`) | Screen control | Notes |
|---|---|---|---|
| Incident ID and text | `query` | search box | matches the id, a staff display name, title, description (unchanged) |
| Date | `fromDate`, `toDate` (ISO instants) | "Created from" / "Created to" date inputs | a chosen day is sent as local 00:00:00.000 to 23:59:59.999; `fromDate` after `toDate` is 400 `ERR_VALIDATION_FAILED` and is stopped in the browser |
| Status | `status` (repeatable, **new: several values**) | multi-select "All Statuses" | values OR-ed |
| Priority | `priority` (repeatable) | multi-select (unchanged, ADR-012) | |
| Category | `category` (repeatable) | multi-select (unchanged, ADR-012) | |
| Service | `serviceId` | dropdown filled from the new `GET /api/admin/services` | single value |
| Technician | `assigneeId` | dropdown filled from `GET /api/admin/technicians` | single value |
| SLA status | `slaStatus` (repeatable, **new**) | multi-select "All SLA States" | the stored status of the last SLA evaluation; a ticket without a tracker never matches |

Different filters are AND-ed; values inside one filter are OR-ed; an empty filter applies nothing. An unknown enum value
is 400 `ERR_INVALID_PARAMETER` (ADR-027). The table gained an **SLA** column (badge, "--" without a tracker), and a
**Clear** button empties every control and searches once. The Filter button stays the only search trigger (there was
never an Enter-key search; none was added).

## Files

Phase 1, backend filters:
- `infrastructure/persistence/specification/IncidentSpecifications.java`: `hasStatusIn`, `hasSlaStatusIn` (inner join on `slaTracker`).
- `application/port/IncidentSearchPort.java`, `application/service/SearchService.java`: `statuses` replaces `status`, new `slaStatuses`; `validateDateRange` in `search` and `searchPaged`.
- `infrastructure/persistence/adapter/IncidentSearchAdapter.java`, `web/controller/AdminController.java` (`searchIncidents`).

Phase 2, service lookup:
- New `application/service/ServiceCatalogService.java`, `web/dto/ItServiceOptionDto.java`; `AdminController` (`GET /services`, constructor).

Phase 3, web app:
- `static/index.html` (filter bar, SLA column, asset version 4.9), `static/app.js` (`adminSearchParams`, `localDayBoundaryIso`, `clearAdminFilters`, `loadAdminFilterOptions`, `fillFilterSelect`, `resetAdminFilterOptions`, SLA cell, 9-column message rows), `static/styles.css` (`.admin-filter-control`, `.admin-filter-date`).

Phase 4: `scripts/live-checks/admin_search_filters_browser.py`, ADR-049, this file, `CLAUDE.md`, `docs/review/requirements-traceability.md` (REQ-WF-13a), `docs/testing/test-cases.md` (TC-NORM-05, TC-EXC-04). `docs/uml/class-diagram.puml` was not changed: it shows `SearchService` without its criteria fields.

## Tests (suite 1944 -> 1975, 31 new)

- `IncidentSpecificationsTest` +7 (status one/many/empty, SLA match, no tracker excluded, SLA empty, SLA and status combined without duplicate rows).
- `SearchServiceTest` +3 (pass-through of both sets, reversed range refused before the port is called, equal or open range accepted).
- `AdminControllerTest` +3 (repeated `status`, `slaStatus` one and several, unknown values 400).
- New `AdminSearchFiltersRegressionTest` (6, `@Tag("regression")`, over HTTP; ticket A is made P1 and breached with the simulation clock and the SLA evaluation; its tickets are closed afterwards).
- New `ServiceCatalogServiceTest` (2), `ServiceCatalogEndpointTest` (3: admin 200 sorted with three fields only, employee and technician 403, no token 401).
- New `AdminSearchFilterMarkupTest` (7: ids, option values equal the enums, parameter names, the lists are loaded and filled and forgotten on sign-out, reversed dates stopped before the request, 9 header cells and no 8-column row, no inline handlers).
- Changed on purpose: the four existing `IncidentSearchCriteria` calls in `SearchServiceTest`; the 12 tests that pin the asset version (4.8 -> 4.9); `MultiSelectFilterMarkupTest` reads the priority dropdown up to `#multiselect-status` (the new dropdowns sit between it and the Filter button).

## Measured results

- `mvn -o clean verify`: 1975 tests, 0 failures, 0 errors, 0 skipped, JaCoCo gate met (report-wide line coverage 91.4 %).
- Fast lane after each phase: 1909, 1914, 1921 tests, 0 failures.
- One JVM per class: NOT COMPLETED, skipped on purpose (stopped at the user's request to save time). Two partial runs, both with 0 failures and 0 errors: (1) in the working tree, 189 of about 264 classes (1377 tests), then file access on the development machine stalled (a thread dump showed the JVM crawling through file opens during Spring's classpath scan; four orphaned test JVMs that could not be ended kept the machine busy and, most likely, locked files in `target/`); (2) from a clean copy of the working tree without `target/`, 130 classes (1051 tests), when it was stopped; Spring tests ran normally there, so the stall was environmental, not a code defect. The classes of the two runs overlap and the remaining ones were not run, so order independence of the new tests is not proven by a complete run (the new tests seed themselves and are covered by the full `mvn verify`). To finish: `mvn -o test -Djacoco.skip=true -DforkCount=1 -DreuseForks=false` from a clean checkout of the working tree.
- Live check `admin_search_filters_browser.py` against the running app (Edge): **30 of 30** on two consecutive runs (every filter alone and all together, table rows equal to the API answer for the exact request the page sent, dropdowns filled with 15 services and 24 technicians, SLA column, reversed dates send no request, Clear). `triage_browser.py` 14 of 14 (twice); `action_buttons_browser.py` 35 of 35 on its first two runs (a third run on the same database stopped in its own setup with `ERR_TECHNICIAN_OVERLOADED` because earlier runs had filled TECH-NET-3; that script needs a fresh database). No ERROR in the app log.
- Mutation checks, each made tests fail and was undone (file restored byte for byte): SLA filter ignored in the adapter (3 failures), status filter uses only the first value (2), reversed range accepted (2), `app.js` stops sending `slaStatus` (1), service dropdown never filled (1). The two front-end mutations were checked against the markup test, not in the browser.

## Known limits

- The SLA status is the value written at the last SLA evaluation, so it can lag up to one scan interval (`iitsm.sla.scan-interval-ms`); a paused ticket keeps the status it had when it was paused. On a fresh database every new ticket is `ON_TRACK`, so the browser check proves the SLA filter on `ON_TRACK` and `MET`; `BREACHED` is proved by the regression test.
- No "unassigned" technician choice; service and technician are single-select.
- The reporter filter (`reporterId`) stays API-only.
- The dropdown lists are read once per sign-in (a later addition appears after the next sign-in; a failed list is retried when the catalogue is shown again).
- The selection is not remembered across reload or sign-out, and focus is not trapped in an open multi-select menu (CLAUDE.md section 7 item 11).
- The date window is in the browser's local time zone; an API client sends instants.
