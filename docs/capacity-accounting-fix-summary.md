# Capacity accounting (remaining-defects plan, Phase B): summary

Branch `bug-fixes`, 2026-11-05. ADR-026. Plan and decisions: `docs/remaining-defects-plan.md` (D1, D2, D3). Closes the slot-leak half of stress-audit items H8 and M6 and the cause found for P2.

## What was wrong
A technician's capacity slot was committed in a transaction of its own (`TechnicianAssignmentService`, `REQUIRES_NEW`) while the ticket's assignment committed later in the caller's transaction. Any later failure of the caller left a **phantom slot**: capacity used for a ticket that never got the technician. Four ways in, each reproduced by `CapacityAccountingRegressionTest` before the fix:

| # | Way in | Evidence before the fix |
|---|---|---|
| B1 | Intake: a failure after the assignment | `createIncident` threw, the ticket was lost, the slot stayed |
| B2 | Reclassify with an unknown service id (M6) | `PHANTOM_SLOT\|TECH-SEC-1\|ENG-2026-000001`: a security technician held a slot for a VPN ticket still owned by a network technician |
| B3 | Accept-suggestion on an assigned ticket (H8) | HTTP 200 instead of a refusal; phantom slot; assignee unchanged |
| B4 | Dispatcher cycle with one failing ticket | the call threw; slots of the whole cycle stayed, assignments rolled back |

Because ticket numbers are given back on rollback (ADR-013), a phantom slot can later name a different ticket with the same number: the most likely cause of the "1 of 24 technicians" mismatch of the audit (hypothesis, not proven for the original runs).

## What changed
- `TechnicianAssignmentService.tryAssignTechnician`: propagation `REQUIRES_NEW` -> **`MANDATORY`** (joins the caller's transaction, fails loudly without one).
- New `TicketDispatchService.dispatchOne`: one waiting ticket, one transaction; slot and assignee commit or roll back together. Shared by intake and the dispatcher (the two copies of the candidate code are now one; the third and fourth, in `AdminIncidentService`, stay until Phase G).
- `UnassignedIncidentDispatcher`: no longer one transaction per cycle but one per ticket; failures isolated per ticket and logged; schedule configurable (`iitsm.dispatcher.interval-ms`, `initial-delay-ms`; the old hard-coded numbers were a rule-15 deviation).
- Intake in two steps: `IncidentIntakePipeline.processIntake` saves and returns `IntakeResult`; `IncidentService.createIncident` assigns in a second transaction inside the intake gate. A failed assignment leaves the ticket for the dispatcher and the reporter keeps it.
- `AdminIncidentService.acceptTriageSuggestion`: the broad `catch (Exception)` is gone; an invalid move now rolls the slot back (HTTP 409).
- `Incident.isAwaitingAutomaticAssignment()`, `IncidentStatus.holdsTechnicianSlot()`: the rules moved into the domain.
- Capacity check and repair (D3): `CapacityAudit`, `CapacityDiscrepancy` (domain), `CapacityReconciliationService` (REPEATABLE_READ snapshot), `GET` and `POST /api/admin/capacity/reconcile`, `CapacityStartupAudit` (one log line at start-up), `carriedDuplicateCount` in `GET /api/admin/technicians` (D1: F4 unchanged, duplicates still take no slot).
- Test profile: dispatcher interval one hour (the background dispatcher of every cached test context shares the one database).
- `scripts/live-checks/capacity_burst.py`: burst harness for a running application (kept in the repository, decision Q2).
- No change to `app.js`, `index.html`, `styles.css` or the schema (no migration); the asset version stays `?v=3.5`.

## Tests
New (all in the suite): `CapacityAccountingRegressionTest` (4, failed before the fix), `CapacityRaceRegressionTest` (3 rounds of 10 intakes + 3 resolves + 3 reassignments + 3 dispatcher runs behind a barrier), `CapacityReconciliationEndpointTest` (3), `CapacityAuditTest` (12), `CapacityReconciliationServiceTest` (7), `TicketDispatchServiceTest` (8), `IncidentServiceIntakeAssignmentTest` (7), `CapacityStartupAuditTest` (3), `UnassignedIncidentDispatcherTest` (7, rewritten), `TechnicianAssignmentServiceTest` (+1 guard). Existing tests needed no change except the two unit tests whose constructor changed.

Mutation checks (each made tests fail, then the real code was restored byte for byte):
| Mutation | Tests that failed |
|---|---|
| `REQUIRES_NEW` put back | all 4 scenarios (a `PHANTOM_SLOT` each), the propagation guard, and `CapacityRaceRegressionTest` |
| one `@Transactional` around the dispatcher cycle | `failingTicketDoesNotAffectTheRestOfTheDispatcherCycle`, `cycleIsNotOneTransaction` |
| `catch (Exception)` back in accept-suggestion | `acceptSuggestionOnAssignedTicketLeaksNoSlot` |
| failure after intake rethrown | `failedAssignmentAfterIntakeKeepsTheTicketAndLeaksNoSlot`, two `IncidentServiceIntakeAssignmentTest` cases |

Two test mistakes of my own, found and fixed on the way (not defects of the fix): scenario B1 first used a per-instance random generator, so every scenario produced the same "unique" ticket text and duplicate detection (correctly) linked them; the repair endpoint test assumed an empty database, but the repair stops when a technician is full (leftover tickets of earlier tests) and so did not reach its own ticket.

## Verification
| Run | Result | Time |
|---|---|---|
| Targeted: the 4 scenarios, race test, endpoint test and new unit tests (single JVM) | 39 tests, 0 failures; later 8 in the order that pollutes the shared database | |
| Mutation checks (4) | each made tests fail; real code restored byte for byte (`cmp`) | |
| Full `mvn -o clean verify` #1 | 862 tests, **1 failure**: my new repair endpoint test assumed an empty database (the repair stops when a technician is full; leftovers of earlier tests). Test fixed, not the code. No existing test failed | 4 min 18 s |
| Full `mvn -o clean verify` #2 | 862 tests, 0 failures, 0 errors, 0 skipped, JaCoCo gate met | 4 min 8 s |
| Every class in its own JVM (113 classes) | 862 tests, 0 failures (run on the code before the start-up-check switch below) | 13 min 23 s |
| Full `mvn -o clean verify` #3, final code | 862 tests, 0 failures, 0 errors, 0 skipped, JaCoCo gate met, line coverage 89.0 % | 4 min 9 s |
| Live check: `scripts/live-checks/capacity_burst.py` against the real app (dev profile, seeded) | burst A: 8 identical reports released together, 8 x HTTP 201, slowest 0.41 s, 7 linked as duplicates; burst B: 30 different reports, 30 x 201, slowest 0.80 s; capacity differences 0 before and 0 after; 31 of 31 tickets that awaited assignment were assigned by step 2, none lost a race; app log: no ERROR, no 5xx (only the e-mail delivery warnings, no mail server runs) | |
| Benchmarks (Phase A) | classification and duplicate numbers identical to the Phase A baseline (nothing in classification or duplicates changed) | |

The suite went from 813 to 862 tests. Log of the final full run: 17 ERROR lines (Phase A: 10), all produced on purpose: 4 deliberate start-up failures, 5 assignment-failure lines from the new tests (injected failures in `CapacityAccountingRegressionTest` and the unit tests of the dispatcher and intake), the foreign-key failure of the reclassify scenario (HTTP 500 until Phase C1 returns 404), 1 counter-row race (audit L1, the request recovers), and the same 5 as before (channel failure, mail server down, "boom", the 405 returned as 500, the start-up check unit test).

Not run: CI on JDK 21 (local JDK 24.0.2). The start-up check switch (`iitsm.capacity.startup-check`, off in the test profile because tests damage slots on purpose) was added after the one-JVM-per-class run; it changes bean wiring only and is covered by full run #3.

## Behaviour that changed on purpose
- Accept-suggestion on an already assigned ticket: HTTP 409 and nothing changes (was 200 with a leaked slot). Phase C2 adds the explicit status rule.
- A new ticket is `CLASSIFIED` and unassigned for a few milliseconds before the second step assigns it.
- A lost race in the second step delays assignment by up to one dispatcher cycle (30 s).

## Known limits
- The first-ticket counter row (`TicketSequenceRowCreator`) and the login counter still open a nested transaction (audit P1; deferred / Phase E).
- The reject of a resolved ticket takes the slot back and fails with 409 when the technician is full (unchanged).
- `carriedDuplicateCount` is in the API only; the web app shows it in Phase J.
- A phantom slot leaked before this fix stays until an administrator runs the repair.
- The capacity check reads open tickets and the tickets behind slots; a very large number of open tickets makes it slower (it is not on a request path except the technician roster, which reads the same snapshot).
