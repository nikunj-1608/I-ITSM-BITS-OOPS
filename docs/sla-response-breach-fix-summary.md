# SLA response breach and per-ticket scan transactions (Phase D)

Remaining-defects plan, Phase D. ADR-030 (stress-audit item M1) and ADR-031 (item H10). Decision D9-A: no migration.

## What was wrong

1. **M1, a response breach was forgotten.** `SlaTracker` has one status for two targets. The scan marked a ticket `BREACHED` when no response had come by the response deadline, but `evaluateStatus` looked at the response only while `respondedAt` was null. Reproduced before the fix:
   - a P1 ticket the scan had marked `BREACHED` went back to `ON_TRACK` as soon as the technician acknowledged it (the administrator's list and the escalation chain then saw a healthy ticket);
   - a late response followed by a timely resolution ended `MET`;
   - every `SlaViolated` event said `RESOLUTION_TARGET`, also when the response target was missed;
   - a late acknowledge happens inside the technician's transaction, so the next scan saw "no change" and the breach was never announced at all;
   - a policy change (override, reclassify) added the time the ticket later waited for the employee to the *response* deadline, so a late response could look on time afterwards.
2. **H10, the scan was one giant transaction.** `SlaService.evaluateAllActiveIncidents` evaluated every active ticket inside the class-level transaction. One ticket that failed (a listener that threw, a lost write race) rolled back the statuses, events and escalations of all the others, and failed again on every scan, so all breaches stayed unannounced for as long as one ticket was broken.

## What changed

| Area | Change |
|---|---|
| `SlaTracker` | `responseBreached(clockNow)` (derived from `respondedAt` / `responseDueAt`, no new column); `evaluateStatus` and `recordResolution` use it, so the status is the worse of the two verdicts; `updatePolicy` adds the pause to the response deadline only while there is no response; new `breachedTarget(now)` with the enum `BreachedTarget` (`RESPONSE_TARGET` / `RESOLUTION_TARGET`) |
| `SlaService` | `SlaViolated` carries the real target; the scan reads ids (`findActiveIncidentIds`) and evaluates each ticket in its own transaction through `TransactionOperations`; a failure is logged (lost race WARN, other ERROR with stack trace) and the ticket is retried at the next scan; `evaluateAllActiveIncidents` is `SUPPORTS`; new constructor argument |
| `IncidentLifecycleService` | `acknowledge` and `resolve` announce the SLA transition they caused (same pattern as the priority override, ADR-024); takes `SlaService` |
| `IncidentRepository` / adapter / Spring Data | `findActiveIncidentIds()` |
| `src/test/resources/application.yml` | `iitsm.sla.scan-interval-ms: 3600000` (tests call the scan themselves) |

No schema, migration, API or UI change. The asset version stays `?v=3.6`.

## Behaviour changed on purpose

- A late response is a breach for good (until a policy change moves the deadline); a ticket that responded late and ends in time is `BREACHED`, not `MET`.
- An active ticket that was acknowledged late *before* this change and was forgiven back to `ON_TRACK` becomes `BREACHED` at the next scan, with one `SlaViolated` (`RESPONSE_TARGET`) and the escalation. Tickets already `MET` are not rewritten.
- The scan no longer fails as a whole: one broken ticket logs one ERROR per scan and the rest are evaluated.
- `IncidentReclassifyContractTest.overrideOnResolvedTicketIsKept` resolved a P2 ticket after 20 minutes without any response, which cannot happen through the lifecycle (acknowledge comes first); it now records a response at 2 minutes.
- `SlaTrackerPauseModelTest` and `SlaTrackerReopenModelTest` (5000 random sequences each) track the working time at which the response happened and judge it against the current policy; the seven tests that build `SlaService` by hand pass `TransactionOperations.withoutTransaction()`.

## Tests (suite 963 to 989)

| Class | Tests | Notes |
|---|---|---|
| `SlaTrackerResponseBreachTest` | 9 | 6 failed before the change (late acknowledge after a scan, late response without a scan, late response then timely resolution, reopen, policy change, pause after the response); 3 controls |
| `SlaServiceScanTest` | 9 | one transaction per ticket, one failure or lost race does not stop the scan, vanished ticket, empty scan, label per target, no repeat announcement, unchanged status is silent |
| `ResponseBreachRegressionTest` | 6 | whole application, committed data, audit trail counted: late acknowledge after the scan, without a scan (announced once at the acknowledge), late response then timely resolution, on-time control (MET, no violation), resolution breach label, reopened late ticket |
| `SlaScanIsolationRegressionTest` | 2 | a listener throws for one marked ticket: the other two breach and are saved, the marked one is rolled back as a whole and breaches on the next scan |

Mutation checks (each made tests fail, then undone): the scan rethrows a ticket failure (4 tests), the label hard-coded to `RESOLUTION_TARGET` (3 tests), no announcement at acknowledge (2 tests). The tracker change itself was proven red first (6 of 9 failed before it).

## Verification

- Full `mvn -o clean verify`: 989 tests, 0 failures, 0 skipped, JaCoCo gate met (89.5 % lines). The first run found one failure, the contract test above.
- Every test class in its own JVM (127 classes): 989 tests, 0 failures.
- Live check against the running application (`scripts/live-checks/sla_response_breach.py`, 3 s scan interval, simulation clock): 10 of 10 checks. A: the scan breaches an unacknowledged P1 ticket with one `RESPONSE_TARGET` violation, a late acknowledge keeps it `BREACHED` and announces nothing more. B: acknowledged after 7 minutes and resolved in time, the ticket is `BREACHED` with exactly one violation. C: acknowledged and resolved in time, the ticket is `MET` and silent. No ERROR in the app log. (The first run of the script failed only because it looked for the assignee among the wrong team; the Kubernetes ticket was routed to the Service Desk lead, which is the known routing weakness for Phase G.)
- Not run: CI on JDK 21.

## Known limits

- The label of an event announced late (first scan after a restart) comes from the tracker's state at that moment; a ticket that missed both targets is announced as `RESPONSE_TARGET` only.
- The scan loads one ticket at a time (one `findById` each) instead of one query for all; every transaction is short, a very large board scans a little slower.
- A ticket evaluated while another transaction changes it can lose the write race and is retried at the next scan (no automatic retry inside a scan).
- A policy change does not re-check the resolution verdict of a resolved ticket (ADR-024).
