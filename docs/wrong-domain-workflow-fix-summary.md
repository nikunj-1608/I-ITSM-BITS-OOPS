# Status rules and the wrong-domain workflow (remaining-defects plan, Phase C2): summary

Branch `bug-fixes`, 2026-11-05. ADR-028 (status rules) and ADR-029 (wrong-domain workflow). Plan and decisions: `docs/remaining-defects-plan.md` (D4 and D8). Closes stress-audit item L5, the status guard of H8, and M10 sub-gaps (a), (b) and (c) (sub-gap (d) was ADR-023); CLAUDE.md section 7 item 3.

## What was wrong
| Area | Gap | Evidence before the change |
|---|---|---|
| Priority override | accepted on a RESOLVED, VERIFIED or CLOSED ticket: the stored priority and the staff display name (`P1-...`) changed although nothing could honour it | `StatusRulesRegressionTest.overrideIsRefusedOnFinishedTickets`: HTTP 200 |
| Accept-suggestion | worked on any ticket, also on one that only waited for the dispatcher; never published `IncidentAssigned` (technician not notified, assignment not audited) | `acceptSuggestionNeedsATriageTicket`: 200 instead of 409; `acceptSuggestionAnnouncesTheAssignment` failed |
| Flag from IN_PROGRESS (M10 a) | refused with 409: a technician who finds out while investigating had to ask an administrator; the administrator's reassignment and the reclassify reroute bypassed the state machine for IN_PROGRESS | `technicianCanFlagFromInProgress`: 409 instead of 204 |
| Triage queue (M10 b) | a flagged ticket was listed twice (`LOW_CONFIDENCE` and `WRONG_DOMAIN`); the card called every entry "Flagged Out-of-Scope" and had no way to act on the flag | `flaggedTicketIsListedOnce` |
| Reject (M10 c) | `ReassignmentRequest.reject` existed but nothing called it; a decided request could be decided again | `rejectGivesTheTicketBack` and 3 more |
| Team lead flag (found on the way) | a lead flagging a ticket held by a technician released the **lead's** slot; the technician kept a phantom slot | `teamLeadFlagFreesTheAssigneesSlot`: `PHANTOM_SLOT` for the technician |

## What changed
- Status rules (ADR-028): `IncidentState.allowsPriorityOverride()` (false in the three finished states); `Incident.overridePriority` refuses with `InvalidStateTransitionException` (409); accept-suggestion requires a ticket in triage (`NotInTriageException`, 409 `ERR_NOT_IN_TRIAGE`) and publishes `IncidentAssigned`. Reclassify is unchanged (any status, relabel only once finished) and now pinned for CLOSED.
- Wrong-domain workflow (ADR-029): `InProgressState` allows `flagWrongDomain`; `AdminIncidentService.assign` and `rerouteToOwningTeam` release ASSIGNED, ACKNOWLEDGED and IN_PROGRESS tickets through the state machine (PENDING_INFO keeps its explicit reset); one triage entry per ticket with `requestId`; `POST /api/admin/reassignments/{requestId}/reject` (reason required, one transaction, ticket back to the technician, request REJECTED, reason as an internal note, `IncidentAssigned` published); `ReassignmentRequestNotPendingException` (409 `ERR_REQUEST_NOT_PENDING`); `ReassignmentRequest.approve`/`reject` refuse a decided request; `Incident.removeExcludedTechnician`; the flag releases the holder's slot.
- The two empty `catch (Exception ignored)` blocks of the triage queue became one logged WARN (a rule-9 deviation removed in code this phase touched).
- Web app: type-aware triage card, "Reject request" button and dialog (technician flags only), asset version `?v=3.6`; the technician's "Flag Out-of-Scope" button appears on IN_PROGRESS cards by itself (it follows `allowedActions`).
- `scripts/live-checks/triage_browser.py`: the workflow in a real browser. No schema or migration change (the rejection reason is a ticket note, no Flyway V7).

## Tests
New: `StatusRulesRegressionTest` (5), `WrongDomainWorkflowRegressionTest` (10; 7 failed before the change, the 3 controls passed before and after), `IncidentOverrideStatusRuleTest` (11), `AdminIncidentServiceWrongDomainTest` (10), `TriageCardMarkupTest` (6), `IncidentStateTransitionTableTest` (+1).

Changed on purpose (all listed in ADR-028 and ADR-029): `IncidentStateTransitionTableTest.testInProgressStateTransitions`; `PriorityOverrideSlaRegressionTest.resolvedTicketKeepsItsSlaVerdict` and `PriorityOverrideSlaEventsTest.resolvedTicketStaysMetAndSilent` (they overrode a resolved ticket and asserted the verdict stayed; they now assert the refusal and that nothing is recorded); the three static-asset tests that pin the version (`?v=3.6`).

Mutation checks (each made tests fail, then the code was restored byte for byte):
| Mutation | Tests that failed |
|---|---|
| a RESOLVED ticket accepts an override again | `IncidentOverrideStatusRuleTest`, `StatusRulesRegressionTest.overrideIsRefusedOnFinishedTickets` |
| IN_PROGRESS cannot be flagged | the state-table tests (2) |
| accept-suggestion needs no triage | `AdminIncidentServiceWrongDomainTest`, `StatusRulesRegressionTest` |
| the flag frees the flagging user's slot again | `teamLeadFlagFreesTheAssigneesSlot` |
| triage entries ignore the request | 3 tests (unit and HTTP) |
| reject does not take the technician's slot | 5 tests |
| a decided request can be decided again | 3 tests |
| the Reject button is drawn on every card | `TriageCardMarkupTest.rejectButtonOnlyForFlaggedTickets` |

Mistakes of my own in new tests, found and fixed on the way (not defects of the change): the lead-flag scenario was skipped when the auto-assigned technician was itself a lead (it now gives the ticket to a regular member); a wrong `AdminLevel` constant; a weak "eligible by pending request" test that would also have passed on an unrelated failure (now asserts the success path); in the browser script, waiting for a dialog to be "detached" (the app hides it), a missing sub-tab click, and a case-sensitive check of text that the stylesheet upper-cases.

## Verification
| Run | Result | Time |
|---|---|---|
| New regression tests against the unchanged code | `StatusRulesRegressionTest` 3 of 5 failed, `WrongDomainWorkflowRegressionTest` 7 of 10 failed; the controls passed before and after | |
| Targeted: the new tests, the changed tests, the markup tests, the override-SLA tests | 102 tests, 0 failures (after fixing mistakes of my own in new tests) | |
| Mutation checks (8) | each made tests fail; real code restored byte for byte (`cmp`) | |
| Full `mvn -o clean verify`, first run (to find existing tests the new rule affects) | 936 tests, 2 errors: exactly the two override-SLA tests that override a resolved ticket (changed on purpose, ADR-028) | 4 min 25 s |
| Full `mvn -o clean verify`, final code | 963 tests, 0 failures, 0 errors, 0 skipped, JaCoCo gate met, line coverage 89.6 % | 4 min 59 s |
| Every class in its own JVM (123 classes) | 963 tests, **1 error**: `OverrideDeadlineRepairPostgresTest.mixedDatabase`, "The connection attempt failed" to the in-process PostgreSQL. Nothing in this phase touches migrations or persistence; the class passed 16 of 16 in two reruns on its own and in the full run above. Treated as the known temperamental embedded database on this machine (CLAUDE.md section 4) | 15 min 36 s |
| Live: `input_errors.py`, `capacity_burst.py` against the running app | 21 of 21 requests as expected; 8 identical and 30 different simultaneous reports all got a ticket, 0 capacity differences | |
| Real browser: `triage_browser.py` (Playwright, Edge, real login form) | 14 checks pass: the flag button on an IN_PROGRESS card, the flag dialog, one triage card per ticket labelled "Flagged by technician" with technician, reason and suggestion, a low-confidence card without "Reject request", the reject dialog, the card leaving the queue, the ticket ASSIGNED again with the reason as a note, no new capacity difference, no console error and no failed request | |

The suite went from 920 to 963 tests. Log of the final full run: 21 ERROR lines, all produced on purpose or known: 4 deliberate start-up failures, 4 "Unhandled server exception" lines from the guard probes and handler tests, 5 assignment-failure lines from tests that inject failures, the counter-row race (audit L1), the channel, mail-server and start-up-check failures of other tests, and 3 lines from the background SLA scan thread (an H2 table-lock timeout while a test held the lock, then its rollback failure: the one-giant-transaction scan of audit H10, planned for Phase D); no test failed because of them.

Not run: CI on JDK 21 (local JDK 24.0.2).

## Known limits
- The administrator's reason is stored on the ticket (internal note), not on the reassignment request.
- A technician cannot flag a ticket that waits for the employee (PENDING_INFO, ADR-021); the administrator can still reassign it.
- A flag by a team lead excludes the lead, not the technician who held the ticket, from the ticket (unchanged).
- A ticket can go back and forth between a technician (flag) and the administrator (reject); every step is audited and the notes keep the history.
- The background SLA scan (audit H10, Phase D) occasionally collides with a running test on the shared H2 table and logs an ERROR (a lock timeout); no test failed because of it.
