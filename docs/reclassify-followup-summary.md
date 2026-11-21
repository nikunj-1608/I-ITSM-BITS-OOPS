# Reclassify follow-up (stress audit H7): summary

Branch `bug-fixes`, 2026-11-03. ADR-021 fixed the core of H7 (history lost, no re-route). This follow-up checked that fix against a wider set of tests and closed what they found. ADR-023.

## What was wrong (found by the new tests)
| # | Gap | Evidence |
|---|---|---|
| 1 | `PriorityCalculated` published with a priority that was not applied (override or closed ticket kept) | `ReclassifyAuditRegressionTest` A1, A2 failed |
| 2 | A ticket handed to a new technician had no `IncidentAssigned` event (no notification, no audit) | `ReclassifyRoutingRegressionTest` T6 failed |
| 3 | A pending wrong-domain request stayed `PENDING` after the reclassify | T7 failed |
| 4 | The web dialog sends no service id, so a reclassify from the browser never re-routed | `ReclassifyEndpointRegressionTest` E1 failed (ticket stayed in TEAM-NET after SECURITY) |

## What changed
- `Incident`: `isPrioritySettled()` is public (the rule "an override or an earned verdict is not recalculated" stays in the aggregate).
- `AdminIncidentService`: shared helpers `recalculatePriority`, `slaPolicyFor`, `publishPriorityIfApplied`, `approvePendingReassignments`, `owningTeamFor`; `rerouteToOwningTeam` returns the new technician; `SupportTeamRepository` added to the constructor.
- No change to `app.js`, the controller or the schema.

## Tests (suite 515 -> 581, +66)
`IncidentReclassifyContractTest` (36: null inputs, idempotence, tracker identity, paused ticket shift, breached/resolved/reopened, every category, every priority), `ReclassifyAuditRegressionTest` (3), `ReclassifyRoutingRegressionTest` (17: every state x same/other team, full team, round trip, triage flag, roles, unknown ticket), `ReclassifyEndpointRegressionTest` (10: the dialog's body, roles 403/401, validation 400, 404, override kept in the response, 12 overlapping requests give only 200/409). Each fix was preceded by a failing test.

## Verification
Local runs used Maven 3.10.0 with `-Djacoco.skip=true`: this machine has JDK 24 and JaCoCo 0.8.12 cannot instrument it. Full suite: 581 tests, 0 failures, 1 skipped in the clean run; one other run showed `CorrectnessRegressionTest.F8_emailChannelSendsRealSmtpMessage` failing (it passes alone and in other full runs; it depends on test order through the shared mailbox). The 70% coverage gate was not re-run locally; CI on JDK 21 must confirm it.

## Known limits
- A `PENDING_INFO` ticket is still not moved (ADR-021).
- A category-only reclassify leaves the ticket's service unchanged (see ADR-023, consequence 2).
- Tickets corrupted by the old reclassify cannot be repaired: their history was overwritten.
- An unknown `serviceId` in the request is stored as given (`service` is null); not changed here (input validation, audit items M5/M6).
