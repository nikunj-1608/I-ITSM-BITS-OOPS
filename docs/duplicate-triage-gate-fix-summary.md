# Confidence triage gate for duplicate tickets (stress audit H2) - summary

Date: 2026-11-03 - decision in ADR-020. Done in four phases with a human checkpoint after each.

## Why
ADR-009 says humans own low-confidence tickets. `IncidentIntakePipeline` only applied that gate to tickets that were
not duplicates. A ticket linked to a parent skipped it, was saved with `needsTriage=false`, and was then handed to a
technician (by copying the parent's assignee, or later by `UnassignedIncidentDispatcher`). In the audit, tickets with
confidence 0.35 and 0.41 were assigned this way because they resembled another vague ticket.

## What changed
- `IncidentIntakePipeline.processIntake`, step 6: the gate is computed once (`confident`) and applies to every ticket.
  - duplicate: always linked, cluster root's priority always recalculated;
  - confident duplicate: takes the root's technician if it has one (F4, unchanged);
  - low-confidence duplicate: no inherited technician, `needsTriage=true`, appears in the admin triage queue.
- The step was split into `linkToParentCluster`, `refreshParentPriority`, `assignAutomatically` and `routeToTriage`
  (same logic, moved), so the method is shorter. No other production file changed.

## Tests
`DuplicateTriageGateRegressionTest` (5, new, tag `regression`). Written first; the two that describe the bug failed on the old code.

| Test | Pins |
|---|---|
| lowConfidenceDuplicateOfTriageParentGoesToTriage | linked, triage, unassigned, dispatcher skips it (failed before) |
| lowConfidenceDuplicateOfAssignedParentGoesToTriage | no inherited technician (failed before) |
| confidentDuplicateOfAssignedParentInheritsAssignee | F4 unchanged |
| confidentDuplicateOfWaitingParentIsNotTriaged | not flagged when the parent merely waits for capacity |
| lowConfidenceDuplicateStillUpdatesParentCluster | parent linked and audited when a duplicate joins |

Each test uses its own wording and marker, because the integration tests share one H2 database and open tickets of
other tests would otherwise join their clusters (this made two tests flaky until fixed).

## Verification
- Related classes (`DuplicateTriageGateRegressionTest`, `AutoAssignmentRegressionTest`, `CorrectnessRegressionTest`,
  `DuplicateDetectionRegressionTest`): 20 tests green on three consecutive runs.
- Full `mvn clean verify`: 507 tests, 9 failures, all in `IncidentReclassifyTest` and `ReclassifyStateRegressionTest`
  (failing-first tests for audit finding H7, unrelated). With those two classes excluded: 496 tests, 0 failures,
  1 skipped (already skipped before this change), JaCoCo gate met.
- Live app (dev profile): two near-identical vague tickets; the second was linked to the first, both showed in the
  admin triage queue at confidence 0.5, and after a dispatcher cycle both were still `CLASSIFIED`, unassigned, `needsTriage=true`.

## Known gaps
- Tickets saved before this fix (`needsTriage=false`, possibly assigned) are not changed.
- A triaged duplicate is not updated when an admin assigns or reclassifies its parent.
- More low-confidence duplicates reach triage, especially when the parent is already assigned.
- A confident duplicate of a parent that sits in triage is left to the dispatcher; not pinned by a test.
- A plain `mvn verify` stays red until the H7 work is finished.
