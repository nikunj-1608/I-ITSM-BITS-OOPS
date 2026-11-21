# Input validation (remaining-defects plan, Phase C1): summary

Branch `bug-fixes`, 2026-11-05. ADR-027. Plan and decisions: `docs/remaining-defects-plan.md` (D5, D6, D7). Closes stress-audit items M5, M6, M7 and M8, plus the wrong-HTTP-method 500 found in Phase A (CLAUDE.md section 7, items 10 and 15).

## What was wrong
Errors the client causes but that reached the catch-all handler were reported as server faults (HTTP 500, ERROR log with stack trace). Each row is a request that `InputValidationRegressionTest` sends; the "before" column is what the unchanged code answered.

| Request | Before | After |
|---|---|---|
| `GET /api/admin/incidents?priority=P9` (also `category=PRINTER`, `status=LIMBO`, `fromDate=yesterday`) | 500 | 400 `ERR_INVALID_PARAMETER`, names the parameter, lists the valid values |
| `PUT /api/admin/users/{id}/designation` `{"designation":"OVERLORD"}` | 500 | 400 `ERR_VALIDATION_FAILED`, lists the valid designations |
| `PUT /api/admin/assignment/strategies/active` `{"strategyName":"NOPE"}` | 500 | 400 `ERR_VALIDATION_FAILED`, strategy unchanged |
| `POST /api/simulation/run/S99` | 500 | 400 `ERR_VALIDATION_FAILED` |
| `POST /api/simulation/speed?multiplier=-5` (also `0`, `fast`) | 500 | 400 (`ERR_VALIDATION_FAILED`, `ERR_INVALID_PARAMETER`), speed unchanged |
| `POST /api/simulation/advance?minutes=-30` (M8) | **200, the clock moved back 30 minutes** | 400, the clock does not move |
| `POST /api/simulation/advance?minutes=9223372036854775807` | 500 (overflow) | 400 |
| `POST .../accept-suggestion` `{"category":"PRINTERS"}` | 500 | 400, ticket stays in triage |
| `POST .../accept-suggestion` with no body or `{}` (M7) | forced the category to **SOFTWARE** | uses the classifier's suggestion |
| `POST .../reclassify` and `.../accept-suggestion` with an unknown `serviceId` (M6) | 500 at commit (foreign key) | 404 `ERR_SERVICE_NOT_FOUND`, nothing changed |
| `GET /api/auth/login` (wrong method) | 500 | 405 `ERR_METHOD_NOT_ALLOWED` with an `Allow` header |
| `GET /favicon.ico` | 500 and an ERROR log per browser session | 404 `ERR_NOT_FOUND`, quiet |

## What changed
- `GlobalExceptionHandler`: handlers for type mismatch (400), missing parameter (400), method not supported (405 + `Allow`), unsupported media type (415) and missing resource (404), each with a stable code; DEBUG logging; echoed client text cut at 50 characters. **No global `IllegalArgumentException` handler**: a genuine bug stays a logged 500 (guard tests).
- Converted at the source: new `EnumText` with `IncidentCategory.fromText` and `Designation.fromText`; `AssignmentStrategyRegistry`, `SimulationService.runScenario` and `AdjustableClock` throw `ValidationException`; `SlaService.evaluateIncident` throws `ServiceNotFoundException`.
- `AdminIncidentService`: `findServiceOrFail` (D6) in reclassify and accept-suggestion; `suggestedCategory` (D5); `AdminController` no longer defaults to SOFTWARE.
- `SimulationService.advanceClockByMinutes` (1 to ten years) and `AdjustableClock.fastForward` refusing zero and negative durations (D7). Reset and `setTimeTo` unchanged.
- `scripts/live-checks/input_errors.py`: the audit's requests against a running application.
- No change to `app.js`, `index.html`, `styles.css`, the schema or the migrations (the web app shows the server's `message`; asset version stays `?v=3.5`).

## Tests
New: `InputValidationRegressionTest` (14, 11 failed before the change, 3 controls and guards passed before and after), `GlobalExceptionHandlerInputErrorTest` (9), `EnumTextTest` (26), `AdminIncidentServiceInputTest` (5), `AssignmentStrategyRegistryInputTest` (3), `AdjustableClockTest` (+1, one changed).

Changed on purpose:
- `AdjustableClockTest.testInvalidMultiplier`: expects `ValidationException`, not `IllegalArgumentException`.
- `Round3RegressionTest.N1`: it posted the service id `SRV-NET-001`, which is not in the seeded catalogue and had been silently ignored; it now uses `SVC-VPN`.

Mutation checks (each made tests fail, then the code was restored byte for byte):
| Mutation | Tests that failed |
|---|---|
| clock accepts a negative advance again | `AdjustableClockTest.testFastForwardOnlyMovesForward` (the HTTP test is also protected by the service-level bound, two layers) |
| unknown service id ignored again | 2 `AdminIncidentServiceInputTest`, `unknownServiceIdIsNotFound` |
| missing category forced to SOFTWARE | `AdminIncidentServiceInputTest`, `missingCategoryUsesTheClassifiersSuggestion` |
| a global `IllegalArgumentException` -> 400 handler added | both guard tests |
| the 404 handler removed | `missingResourceIsNotFound`, `GlobalExceptionHandlerInputErrorTest.missingResource` |

Two mistakes of my own in new tests, found and fixed on the way (not defects of the change): a JsonPath `length()` assertion that does not work on a string, and a constant that had to be public for a test in another package.

## Verification
| Run | Result | Time |
|---|---|---|
| Regression test against the unchanged code (before any production change) | 14 tests: 11 failed (500 where a 4xx belongs; a negative advance answered 200 and moved the clock back), 3 controls and guards passed | |
| Targeted: the new and changed tests, `Round3RegressionTest`, `SecurityRegressionTest` | 89 tests, 0 failures (after fixing two mistakes of my own in new tests) | |
| Mutation checks (5) | each made tests fail; real code restored byte for byte (`cmp`) | |
| Full `mvn -o clean verify` | 920 tests, 0 failures, 0 errors, 0 skipped, JaCoCo gate met, line coverage 89.2 % | 4 min 22 s |
| Every class in its own JVM (118 classes) | 920 tests, 0 failures | 18 min 46 s |
| Live check: `scripts/live-checks/input_errors.py` against the real app (dev profile, seeded) | 21 requests as expected (18 error cases with the right status and code, 3 valid controls); app log: no ERROR, no unhandled exception | |

The suite went from 862 to 920 tests. Log of the full run: 18 ERROR lines, all produced on purpose (4 deliberate start-up failures, 3 assignment failures injected by the capacity tests, 4 "Unhandled server exception" lines from the guard probes and the handler tests that prove a real bug stays a 500, the counter-row race, and the usual channel, mail-server and start-up-check failures of other tests). The two ERROR lines that were evidence of the old defects (a wrong HTTP method answered as 500, and the foreign-key failure of an unknown service id) are gone.

Not run: CI on JDK 21 (local JDK 24.0.2).

## Known limits
- Reset (`POST /api/simulation/reset`) and `AdjustableClock.setTimeTo` can still move the clock back: the demo tool is kept (decision D7). The paused-clock logic ignores negative windows.
- A missing ticket reuses `ServiceNotFoundException`, whose text reads "IT Service '...' was not found" (existing wording).
- `PUT .../designation` with an empty `{}` body is still a silent 200 no-op (not part of M5).
- Rarer client errors such as 406 still reach the catch-all.
