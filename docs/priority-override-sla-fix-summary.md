# Priority override moves the SLA deadlines (stress audit H6) - summary

Date: 2026-11-03 - decision in ADR-024. Done in five phases with a check at the end of each. The production code and
the first tests were saved in commit `6cd386b` together with other SLA work; this file records the full battery that
was added afterwards and the finding that came out of it.

## Why
`Incident.overridePriority` replaced only the priority decision. The `SlaTracker` kept the deadlines of the original
priority, so a P4 ticket raised to P1 kept its 24 h deadline and stayed `ON_TRACK` long after a P1's 30 minute target,
while its display name already read `P1-...`. Warnings, breaches and escalations kept following the old priority.

## What was built (where, and which pattern)
| Where | What | Pattern / OOP |
|---|---|---|
| `SlaTracker.reanchor(policy, now)` | recompute deadlines from `createdAt` + new targets + time already paused; a resolved ticket keeps its deadlines and verdict | OOP: Encapsulation (the tracker owns its clock rules) |
| `Incident.overridePriority(newPriority, adminId, reason, slaPolicy, now)` | validates, records the override, re-anchors the tracker; rejects a policy of another priority | OOP: Tell, Don't Ask |
| `AdminIncidentService.overridePriority` | looks up the policy of the new priority (repository, factory default as fallback), saves, announces the SLA transition, publishes `PriorityOverridden` | application service orchestrates only |
| `SlaTracker.creditedDuration`, `V5`, `V6` / `OverrideDeadlineRepairer` | keep the reopen credit across re-anchoring; repair tickets overridden before the fix | OOP: Encapsulation; Pattern: Transaction Script (migration) |
| `SlaService.announceTransition(incident, previousStatus, now)` | `SlaWarning` / `SlaViolated` for a status change that already happened; shared with the scan | Pattern: Observer (publishes domain events) |

## A second bug found on the way
Once the tracker moved with the override, the override changed the status inside the admin's transaction. The next scan
then saw "no change" and never published `SlaViolated`/`SlaWarning`: no audit entry, no notification. `announceTransition`
fixes that; escalation was never affected (it runs on every scan).

## Test battery of the first version (181 tests, 0 skipped; the follow-up tests are listed under "Gaps ... now fixed")
| Class | Tests | Layer | What it pins |
|---|---|---|---|
| `PriorityOverrideSlaRegressionTest` | 6 | integration | the original bug scenarios; the lowered-ticket case now runs (was skipped) |
| `PriorityOverrideSlaMatrixTest` | 43 | integration | all 16 priority pairs; ON_TRACK / AT_RISK / WARNING / BREACHED at 50/80/95/100 % of each priority's window; round trip; idempotence; PENDING_INFO; blank reason, employee, technician, unknown ticket, display-name reference |
| `IncidentOverrideSlaTest` | 65 | domain | 16 pairs; every invalid reason and mismatched policy leaves nothing changed; status bands and the exact-deadline boundary; recorded response, paused time (finished and ongoing), resolved and reopened tickets; chains of overrides; `reanchor` directly |
| `SlaServiceAnnounceTransitionTest` | 28 | unit (Mockito) | all 25 status pairs publish the right event or none; event contents |
| `AdminIncidentServiceOverrideTest` | 17 | unit (Mockito) | policy lookup for the NEW priority, repository policy wins over the default, order save -> announce -> event, previous status handed over, non-admin / unknown ticket / blank reason publish and save nothing, a failing announcement propagates |
| `PriorityOverrideSlaEventsTest` | 7 | integration, real audit trail and scan | one `SlaViolated` across repeated overrides and scans, recovery on lowering, `SlaWarning` in the 80 % band, silence inside the window, one escalation, audit text of each override, resolved ticket stays `MET` |
| `PriorityOverrideSlaHttpTest` | 21 | MockMvc | admin response and detail show the new deadline and display name, 400 / 403 / 404 / no token, technician view, employee JSON / list / history show nothing about priority, SLA or the override |
| `IncidentEncapsulationTest` | +5 | domain | updated for the new signature, plus the first domain cases |

**Mutation check** (a deliberate break, tests run, break undone): removing the re-anchor failed 64 of 159 tests in the
six override classes; removing the announcement failed 8 of 30; removing the resolved-ticket guard failed 4 of 78
(`IncidentOverrideSlaTest` 3, `PriorityOverrideSlaRegressionTest` 1; run on those two classes and the events class). All three were caught.

## Live check
Run on a second instance (port 8086, own in-memory database): a P3 ticket overridden to P1 moved from +1 h / +8 h to
+5 min / +30 min and its display name from `P3-` to `P1-`; the employee response named none of priority, SLA, deadline,
display name or reason; after the simulation clock was advanced 60 min the scan set it `BREACHED` with one
`SlaViolated`, one `IncidentEscalated` and one `PriorityOverridden` in the audit trail. Not checked in a browser.

## Gaps that were open after the first version, now fixed
| Gap | Fix | Tests |
|---|---|---|
| A reopened ticket's credit for waiting on the verdict (ADR-022) was dropped by an override or reclassify | `SlaTracker.creditedDuration`: `reopen` adds to it, `updatePolicy` adds it back (resolution deadline only); stored in `sla_trackers.credited_duration_seconds` (`V5__sla_credited_duration.sql`), mapped in `SlaTrackerJpaEntity` / `IncidentMapper` | `IncidentOverrideSlaTest` (+9, the former "KNOWN GAP" test now asserts the credit is kept), `ReopenCreditPersistenceTest` (5, real stack with a moved clock) |
| Tickets overridden before the fix kept the old deadlines | `V6__repair_override_deadlines` (`OverrideDeadlineRepairer`): repairs only unresolved overridden tickets whose deadlines still equal the original priority's policy (original priority read from the audit entry); leaves anything it cannot explain alone; idempotent | `OverrideDeadlineRepairH2Test` and `OverrideDeadlineRepairPostgresTest` (16 scenarios each: both directions, paused time, already correct, resolved, unexplained deadline, no audit entry, never overridden, same priority, two overrides, stored policy, idempotence, status untouched, mixed database) |
| `F8_emailChannelSendsRealSmtpMessage` failed intermittently in full runs | other tests' notifications are delivered after commit on a worker thread and can reach the shared SMTP port after `greenMail.reset()`; F8 now counts only the message it sent (by subject) | `CorrectnessRegressionTest` (full suite run twice) |
| `employeeListIsClean` of this battery scanned every ticket of the demo employee, including other tests' | it now checks only the overridden ticket's entry (field names and text) | `PriorityOverrideSlaHttpTest` |
| The JaCoCo agent crashed on this machine's JDK 24 (0.8.12) | `jacoco-maven-plugin` 0.8.13 | full `mvn verify` |

**Mutation check of the new code:** removing the credit from `updatePolicy` failed 7 of 79 tests
(`IncidentOverrideSlaTest` 6, `ReopenCreditPersistenceTest` 1); disabling the repair update failed 8 of 16
`OverrideDeadlineRepairH2Test` scenarios. Both were caught and undone.

## Known gaps
- Credit a ticket earned before V5 was not recorded (no column existed), so an override or reclassify of such a ticket
  still drops it. It cannot be rebuilt from the audit trail with certainty.
- The credit is stored in whole seconds, like the paused duration: a deadline can differ from the exact sum by under a second.
- `overriddenBy` stores the login e-mail, not the user id (existing open item 2).
