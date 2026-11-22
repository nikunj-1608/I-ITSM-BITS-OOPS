# Paused SLA clock no longer breaches (stress audit H4) - summary

Date: 2026-11-03 - decision in ADR-019. Done in phases with a human checkpoint after each.

## Why
A ticket in `PENDING_INFO` (waiting for the employee) has its SLA clock paused, but the deadline is only extended when
the employee replies. `SlaTracker.evaluateStatus` compared the real time with the old deadline before it looked at the
pause flag, so the scheduled scan marked the ticket `BREACHED`, published `SlaViolated` (an SMS went out) and
escalated it. After the reply the status flipped back to `ON_TRACK` (audit evidence: deadline moved by 89,115 s).

## What was built
- `SlaTracker`: private `effectiveNow(now)` (pause instant while paused); `evaluateStatus` and `getRiskScore` use it
  for every comparison; the late pause guard is gone; new `isResponseOverdue(now)`; thresholds are named constants.
- `Tier2TeamLeadHandler`: asks `isResponseOverdue` instead of comparing `responseDueAt` itself.
- Pattern/OOP: Encapsulation. The tracker owns the paused-clock rule; callers never check the pause state.

## Tests
- `SlaTrackerTest` (+8): past the old deadline while paused, repeated scans, status frozen (`WARNING` stays), already
  breached stays breached, response target while paused, risk score frozen, the 89,115 s audit case, and
  `isResponseOverdue` on a running clock.
- `SlaServiceTest` (+1): no `SlaViolated`/`SlaWarning` for a paused ticket. `EscalationChainTest` (+2): no Tier 2/3/4
  escalation for a paused ticket.
- Before the fix 9 of the first 10 tests failed for the H4 reason; after it all pass.

## Known gaps
- Tickets already marked `BREACHED` in a persistent database stay so until the employee replies.
- `IncidentUrgencyOrdering` still orders paused tickets by the unextended deadline.

## Verification
- Full `mvn clean verify` with nothing else running (2026-11-03): 507 tests, 9 failures, 1 skipped; JaCoCo gate met.
  All 9 failures are the H7 failing-first tests (`IncidentReclassifyTest`, `ReclassifyStateRegressionTest`):
  `Incident.reclassify` still replaces the SLA tracker. No H4 test fails. An earlier run in a shared `target/` directory
  was corrupted by parallel builds and is not counted.
- Live check (real app, 3 s SLA scan, simulated clock): a `PENDING_INFO` P3 ticket stayed `ON_TRACK` with no
  `SlaViolated`, SMS or escalation after the clock was advanced 1000 minutes (about 16.7 h) past its 7 h deadline; after
  the employee's reply it returned to `IN_PROGRESS`/`ON_TRACK` and the deadline moved by the 60,027 s it waited. An
  unpaused control ticket breached with SMS and escalation, so the scheduler was scanning. Advancing the clock again
  breached the resumed ticket too, which is correct (no longer paused, extended deadline passed).
