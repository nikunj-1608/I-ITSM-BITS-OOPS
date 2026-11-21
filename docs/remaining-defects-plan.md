# Remaining defects: phase plan and decisions

Branch `bug-fixes`, 2026-11-05. One programme for every defect left open after the stress-audit fixes of
2026-10-31 to 2026-11-03 (ADR-015 to ADR-024). The plan was built around how the fixes interact; the full
analysis (ground truth, interaction map, decision register, risks) was presented in the session of 2026-11-05.
This file keeps what later phases need: the phase order, the decisions taken and the defect status.

## Decisions (taken 2026-11-05)

The product owner asked for all recommended options to be adopted. Each phase records its decision in an ADR.

| # | Decision | Adopted option |
|---|---|---|
| D1 | Linked duplicates and technician capacity (F4) | F4 unchanged: a duplicate takes no slot; the admin technician view shows carried duplicates separately |
| D2 | Stop slots being reserved outside the caller's transaction | Split by caller: intake and dispatcher commit the ticket, then assign in one top-level transaction (ticket assignee and slot together); admin actions run in one transaction (409 on a race) |
| D3 | Capacity check and repair | Invariant test, admin endpoint that reports and repairs on request, one log line at start-up |
| D4 | Status rules | Priority override refused from RESOLVED on (409); reclassify allowed in every status (category/service only once resolved); accept-suggestion only for triage tickets or tickets with a pending wrong-domain request |
| D5 | Accept-suggestion without a category | Use the classifier's top suggestion; an explicit category wins; an unknown one is 400 |
| D6 | Unknown `serviceId` | 404 (`ServiceNotFoundException`) on reclassify and accept-suggestion |
| D7 | Simulation clock | Negative or zero advance and speed <= 0 are 400; reset stays a documented demo tool |
| D8 | Wrong-domain workflow | (a) flag allowed from IN_PROGRESS (not PENDING_INFO); (b) one triage entry per ticket; (c) reject = assign back to the requester and mark the request REJECTED |
| D9 | Response breach | SLA status = the worse of the response and the resolution verdict, derived from stored fields (no migration) |
| D10 | Team queue | Technicians: own tickets and unassigned team tickets; team leads: whole team; admins: everything |
| D11 | Lockout | 15 minutes (configurable), automatic expiry, same generic message, same rule for admins |
| D12 | Exposure | Registration domains from configuration (`nimbusfinserve.com` in prod/demo, any in dev/test); department must be registered; Swagger off in prod; remove the `tech.database@` login alias |
| D13 | Confidence model | Keep the 0.60 switch; document and pin 0.55/0.60/0.61 |
| D14 | Classifier acceptance budget | Held-out accuracy not lower; every audit example fixed; triage rate up by at most 10 points; F5 and AutoAssignmentRegressionTest keep their intent |
| D15 | Duplicate recall below 59 % | Precision targets stay hard; recall may be re-baselined only with explicit approval at the checkpoint, never below 55 % |
| D16 | `corpus.csv` | Repair the pairing, measure before and after |
| D17 | Routing | The classified category decides the team; the service decides criticality and the SLA policy |
| D18 | Overflow | Only to technicians of other teams with the category skill; otherwise the ticket waits (SLA escalation covers long waits) |
| D19 | Employee activity history | Neutral wording for duplicate and reassignment entries (no other ticket ids, no technician reasons) |
| D20 | Actors | Store user ids from now on, show names in staff views; V8 for old rows only if D21 is yes |
| D21 | PostgreSQL database with data from before 2026-10-31 | Unknown; assumed "no" until the product owner says otherwise (no V7 unescape migration) |
| D22 | Leave open | Notification metrics only (no outbox); defer the intake vector cache, T1-T3 and R-H1 limits (add the T1 pinning test), the ticket-counter half of P1 and L1 |
| C1 | Severity changes | H8 -> Medium, M7 -> Low, P4 -> Medium, L1 deferred, L2 is a decision |
| C2 | Rule deviations | Fixed only in code a phase already touches |
| C3 | Commits and line endings | Commits are proposed per concern and run by the product owner; `Incident.java` line endings restored to CRLF (content unchanged) |

## Phases

| Phase | Goal | Defects | Status |
|---|---|---|---|
| A | Test foundation | P5, P6, log noise, a second order-dependent test, benchmarks, capacity check helper | Done 2026-11-05 (`docs/test-foundation-summary.md`, ADR-025) |
| B | Capacity: no slot outside the caller's transaction; check and repair | H8 (leak), M6 (leak), P2, dispatcher cycle in one transaction | Done 2026-11-05 (`docs/capacity-accounting-fix-summary.md`, ADR-026) |
| C1 | Input validation | M5 (also: a wrong HTTP method gives 500 instead of 405, found in Phase A), M6 (404), M7, M8 | Done 2026-11-05 (`docs/input-validation-fix-summary.md`, ADR-027) |
| C2 | Status rules, wrong-domain workflow | L5, H8 (guard, event), M10 (a), (b), (c), and the team-lead flag slot found on the way | Done 2026-11-05 (`docs/wrong-domain-workflow-fix-summary.md`, ADR-028, ADR-029) |
| D | SLA | M1, the violation target, H10 | Done 2026-11-05 (ADR-030, ADR-031, `docs/sla-response-breach-fix-summary.md`) |
| E | Security | M2, M3, M4, P1 (login), L4 | Done 2026-11-06 (ADR-032, ADR-033, `docs/security-hardening-fix-summary.md`) |
| F | Classification quality (duplicate vocabulary split from the classifier lexicon) | H9 causes 2-4, L2, L7, CLAUDE.md issues 4 and 5 | Done 2026-11-06 (ADR-034, ADR-035, `docs/classification-quality-fix-summary.md`) |
| G | Routing in one place | H9 cause 1, L6 | Done 2026-11-06 (ADR-036, `docs/routing-fix-summary.md`) |
| H | Privacy and actors | L3, S5 | Done 2026-11-07 (ADR-037, `docs/privacy-and-actors-fix-summary.md`) |
| I | Old data, event history | R-C1 (only if D21 = yes), P4, R-N metrics, T1 pinning test | Done 2026-11-07 (ADR-038, `docs/event-history-and-metrics-fix-summary.md`; R-C1 skipped, D21 = no) |
| J | Front-end closure | P3, UI leftovers | Done 2026-11-07 (ADR-039, `docs/front-end-closure-fix-summary.md`) |

Order: A, B, C1, C2, D, E, F, G, H, I, J. B comes before C and G (one cause behind the slot leaks), C2 before G
(the state machine carries IN_PROGRESS tickets), F before G (routing by category makes classification errors
costlier), H after C to G (it rewrites the actor argument in the same methods).

## Findings that shape later phases
- `TechnicianAssignmentService.tryAssignTechnician` commits the slot in its own transaction (REQUIRES_NEW); any
  rollback of the caller afterwards leaves the slot (H8, M6, the unexplained P2 mismatch).
- `UnassignedIncidentDispatcher.dispatchWaitingIncidents` runs a whole cycle in one transaction with REQUIRES_NEW
  slot reservations inside it (same leak).
- The web app never calls accept-suggestion (the triage button opens the reclassify dialog).
- M1 can be derived from stored fields; `locked_until` exists, so M4 needs no migration; the next free Flyway
  number is V7.
- `DuplicatePairSetTest` has no slack: paraphrase recall is 52/87 and 52 are needed.
- `corpus.csv`: 630 rows, 190 distinct descriptions, 431 rows share no content word between title and description.
- Simulation reset moves the clock backwards; the login alias exists in two classes.
