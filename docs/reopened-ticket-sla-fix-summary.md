# Rejected fix reopens the SLA clock (stress audit H5) - summary

Date: 2026-11-03 - decision in ADR-022. Done in phases with a human checkpoint after each. The production code and
the tests were saved in commit `6cd386b` together with other SLA work; the documentation is the last phase.

## Why
`ResolvedState.reject` returned a ticket to `IN_PROGRESS` but never told its `SlaTracker`. The tracker kept
`resolvedAt` and the first verdict (`MET`), so `evaluateStatus` returned at once, `pauseClock` refused to pause and a
later info request shifted nothing. In all 10 rejection loops of the audit the SLA stayed `MET`: reopened tickets, the
ones customers are unhappy about, could never warn, breach or escalate.

## What was built
- `SlaTracker.reopen(now)`: no-op if never resolved; otherwise credits the time the ticket waited in `RESOLVED` to
  `resolutionDueAt` (never a negative window), clears `resolvedAt`, resets the status and re-evaluates it. The recorded
  response and the paused duration are kept.
- `ResolvedState.reject`: calls `reopen(now)` (null-safe) before the transition.
- Pattern/OOP: State + Encapsulation. The state asks the tracker to reopen; no service checks `resolvedAt`.
- No schema change (`sla_trackers.resolved_at` is nullable), no change to services or reporting.

## Deadline rule (decision)
The original deadline stays, plus the verdict wait (option A in ADR-022). Alternatives (no credit; a fresh window) were
rejected. This is awaiting the product owner's confirmation.

## Tests (28 new, all in the suite)
- `SlaTrackerReopenTest` (9): clears resolution, credits the wait, keeps pause and response data, late resolution stays
  `BREACHED`, keeps evaluating (`WARNING` then `BREACHED`), pauses again, no-op without resolution, backwards clock, null.
- `ReopenedTicketSlaTest` (11): the same rules through the `Incident` lifecycle, including three reject loops, a second
  resolution `MET`/`BREACHED`, and a verified ticket keeping `MET`.
- `SlaTrackerReopenModelTest` (1 test, 5000 random sequences of respond, pause, resume, resolve, reopen and evaluate
  checked against a working-time model: status, `resolvedAt`, paused duration, deadline and risk score after every step).
  Mutation check: with the deadline credit removed 4 tests fail, with `resolvedAt` not cleared 12 fail.
- `ReopenedTicketSlaRegressionTest` (5): the real `SlaService` scan flags a reopened ticket, publishes
  `SlaWarning`/`SlaViolated`, and calls the escalation check.
- `ReopenedTicketSlaPersistenceTest` (2, H2): the reopened state survives a save and load, `resolved_at` is NULL in the
  table, the ticket is in `findActiveIncidents`, a later breach is stored.
- Failing first: 11 of the 16 Phase 1 tests failed on the old code for the audit reason; after the fix all pass.

## Behaviour changes to know
- MTTR skips a reopened ticket until its final resolution; SLA compliance no longer counts an open reopened ticket as
  `MET`. No existing report test changed.
- A reopened ticket whose first resolution was late stays `BREACHED`.

## Known gaps
- Tickets already rejected in a persistent database (`resolvedAt` set, `IN_PROGRESS`, `MET`) are not repaired; no data
  migration was written. The dev profile is in-memory, so this matters only for a persistent database.
- No separate SLA audit entry for the reopen; `IncidentReopened` is the record.

## Verification
- Full `mvn clean verify` on a clean tree (2026-11-03, final run, with the model test): 515 tests, 0 failures,
  1 skipped (an `Assumptions` guard in `PriorityOverrideSlaRegressionTest`, not part of this fix), JaCoCo gate met.
  An earlier run, while other fixes were being edited in the same tree, failed to compile and had 3 failures in other
  fixes' tests; it is not counted.
- Live check 1 (real app, dev profile, 60 s SLA scan, simulated clock), 8 of 8 checks: resolve gives `MET`; reject
  after 20 simulated minutes gives `IN_PROGRESS`, `resolvedAt` cleared, `ON_TRACK`, deadline +20 min; an info request
  with a 30 minute wait paused 1800 s and moved the deadline +30 min; after the clock was advanced far past the
  deadline the scan logged `ON_TRACK to BREACHED`.
- Live check 2, six tickets at once, 14 of 14 checks: resolve, reject, resolve again in time and verify stays `MET`;
  three reject loops leave the ticket unresolved; a late first resolution is `BREACHED` and stays so after the reject;
  a pause after a reopen paused 2700 s and moved the deadline; after the clock was advanced 30,000 minutes the scan
  marked all three open reopened tickets `BREACHED`, while the verified and the resolved-in-time tickets kept `MET`.
- No errors in the app log in either live run. The scripts are not in the repository.
