# Paused SLA clock: edge cases and the reclassify path (follow-up to H4) - summary

Date: 2026-11-03 - decision in ADR-021. Follow-up to `docs/sla-paused-clock-fix-summary.md` (ADR-019).

## Why
The first H4 fix was checked with hand-picked cases. This round tried many more: a model-based test with random
sequences, every admin action on a waiting ticket, and every code path that rebuilds the SLA tracker.

## What was tried
- **Model test** (`SlaTrackerPauseModelTest`): 10,000 random sequences of respond / pause / resume / evaluate (and
  random policy changes while paused) compared with a reference that treats the tracker as a function of working time
  (real time minus time paused). Status, risk score, paused duration and the paused flag all match. A mutation check
  (put the old `now` comparison back) makes the test fail at once, so it does bite.
- **End-to-end** (`PausedSlaPathsRegressionTest`, 5): reclassify, priority override, override then reclassify, the
  employee's reply, and an `IN_PROGRESS` reclassify, each followed by a scan three days later on the real services.
- **Readers of the SLA status** were read for direct deadline comparisons: `SlaService`, the four escalation tiers,
  `ReportingService`, the technician and admin screens. Only the Tier 2 response check compared deadlines itself
  (fixed in the first round); the rest read the tracker's status.

## Bugs found and fixed
1. **Reclassify lost the paused clock** (and the recorded response, a manual override and a closed ticket's verdict):
   `Incident.reclassify` replaced the tracker. A reclassified `PENDING_INFO` ticket started breaching while waiting for
   the employee, the H4 symptom through another door. Fixed in the aggregate (ADR-021).
2. **Reclassify left the ticket with the old team's technician** (H7, `ReclassifyStateRegressionTest.R5`): released and
   offered to the owning team (ADR-021). `PENDING_INFO` tickets are deliberately not rerouted.

## Found, not fixed
- The paused duration is stored in whole seconds while deadlines keep full precision (drift under 1 s per pause cycle;
  no false breach). Needs a schema change for no practical gain.

## Tests
- New: `SlaTrackerPauseModelTest` (2), `PausedSlaPathsRegressionTest` (5). The 11 H7 failing-first tests
  (`IncidentReclassifyTest` 5, `ReclassifyStateRegressionTest` 6) now pass.

## A test-isolation finding
- `AdminIntelligenceEndpointsTest` is `@Transactional`, so creating the ticket and the later reclassify request share one
  persistence context. The intake assigns the technician in a `REQUIRES_NEW` transaction, which commits a new version of
  the technician row while the shared context still holds the old one; releasing that technician in the reclassify then
  wrote against a stale version (HTTP 409). Real requests each have their own transaction, so production is not affected.
  The test now flushes and clears its persistence context after creating the ticket. The team lookup in `rerouteToOwningTeam`
  also runs before the ticket is changed, so the query cannot flush half-finished changes.

## Verification
- Full `mvn clean verify` (2026-11-03): 514 tests, 0 failures, 1 skipped, JaCoCo gate met.
