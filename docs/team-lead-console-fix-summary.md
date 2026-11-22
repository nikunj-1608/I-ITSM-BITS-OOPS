# Team Lead Console fixes: Phases M1 to M5 (correct data, refresh, reassignment, buttons, proof)

Date: 2026-11-19. Branch `bug-fixes`. Decision record: ADR-045 in `docs/DECISIONS.md`.

## Why
A live run as the Network team lead found that the server handles the lead's tickets correctly but the Team Lead tab shows wrong or misleading data, and that the lead cannot change a technician. Twelve findings, fixed in five phases: M1 (wrong data), M2 (refresh), M3 (the lead can change the technician), M4 (dead or misleading buttons), M5 (end-to-end proof). Nothing is committed, at the user's instruction.

## What changed
| Finding | Cause | Fix |
|---|---|---|
| Every technician shows 0 active tickets, 0% load | Screen read `activeIncidentsCount`, API sends `activeIncidentCount` | `renderTeamRoster` reads `activeIncidentCount` (`app.js`) |
| "Domain: Engineering", "Tier: L2" for everyone | Screen read `domain` and `designation`, which the API never sends | The card shows "Skills" (distinct skill categories) and "Role" (Team Lead or Technician) |
| Lead sees all 24 technicians | `GET /api/admin/technicians` returned everyone | `UserService.getRosterVisibleTo(actorId)`: lead gets own team, administrator gets all, other roles refused; the controller passes `CurrentUser.idOf` |
| Resolved and closed tickets stay in the escalation list | The filter never looked at the status, and the team queue holds every status | `isOpenTicket` feeds both lists; escalations = open and SLA BREACHED, AT_RISK or WARNING; unassigned = open and no assignee |
| Card says "> 90% Threshold" and "Tier 2"; a P1 on track is listed; a missing priority shows "P1" | Title and filter did not match | Title "SLA Escalation Monitor (At Risk or Breached)", no P1 shortcut, priority shows "-" when missing |

Files: `UserService.java` (+1 method), `AdminController.java` (roster call), `app.js` (roster card, two lists, `isOpenTicket`, constants), `index.html` (card title, subtitle, empty text, comment, asset version 4.3).

## Tests
- `TeamLeadRosterScopeTest` (5, new): lead sees only the own team, two leads do not overlap, administrator sees everyone, technician and employee get 403, the JSON has the fields the screen reads.
- `TeamLeadPanelMarkupTest` (5, new): real load field, real fields only, open-ticket filters, no P1 shortcut or fake priority, card title.
- Changed on purpose: the nine markup tests that pin the asset version (4.3).
- Mutation checks (each made tests fail, then undone): misspelt load field; open filter removed; roster scope removed.

## Phase M2: the panel stays current (2026-11-19)
| Finding | Cause | Fix |
|---|---|---|
| A technician's or the administrator's change does not show until Refresh is pressed | Only the clock and the notification badge were polled | `autoRefreshTeamLead` reloads the tab every 10 s (`TEAM_LEAD_REFRESH_MS`) while it is the active tab, the browser tab is visible, no dialog is open, no write is running and no load is waiting; it also reloads when the browser tab becomes visible again |
| The panel flickers: every reload replaced the lists with "Loading..." | `loadTeamLeadView` always wrote the loading text first | Loading text only before the first real content; later loads keep the old content until the new one replaces it |
| One failed call blanked everything | Roster and queue loaded one after the other in one try block | `Promise.allSettled`; each area has its own error, shown only when it has nothing real yet |
| Nobody could tell how fresh the data was | No stamp | "Updated hh:mm:ss" or "Update failed at hh:mm:ss; showing the last data received" beside Refresh |

Also: a sequence number discards a late answer or one that arrives after sign-out (`resetTeamLeadView`); `apiCall` counts writes in flight (`pendingWrites`); the Refresh button no longer passes the click event to the loader; asset version 4.4.

Files: `app.js` (state, timer, `loadTeamLeadView`, `autoRefreshTeamLead`, `resetTeamLeadView`, `pendingWrites`), `index.html` (`#teamlead-updated`, asset version 4.4), the nine markup tests that pin the asset version (4.4).

Tests: `TeamLeadRefreshMarkupTest` (8, new). Browser: `scripts/live-checks/team_lead_refresh_browser.py` (Edge, 15 checks: only the 4 Network members, the load total rises with no click, the update time moves, the panel never shows "Loading..." during automatic reloads, no reload while another tab is open and a reload at once on return, a failed roster call keeps the 4 cards and says so, a roster failure on a fresh sign-in does not hide the ticket lists, no console error). Mutation checks: the timer ignoring the open tab, and loading text on every reload, each made the browser script fail; three structure mutations are covered by the markup test.

## Verification (2026-11-19)
- M1: `mvn -o clean verify` 1608 tests, 0 failures, coverage gate met.
- M2: `mvn -o clean verify` 1616 tests, 0 failures, 0 skipped, coverage gate met; every class in its own JVM (232 classes) 1616 tests, 0 failures, 0 skipped; Edge run of `team_lead_refresh_browser.py` 15 of 15; no ERROR in the app log. Not run: CI on JDK 21, browsers other than Edge. Nothing is committed (user's instruction).

## Phase M3: the lead can change the technician (2026-11-19)
`POST /api/technician/incidents/{id}/reassign` with `{technicianId, reason}` (team lead only) gives a ticket of the team to another technician of the same team. The four open questions of the plan were not answered (the user was away and asked for the recommended choices): triage tickets stay with the administrator, own team only, "Auto-Dispatch All" removed, `PENDING_INFO` not reassignable by a lead (ADR-045, M3.1 to M3.4).

| Rule | Answer |
|---|---|
| Who | Only a `TeamLead` (`User.canReassignTeamTickets()`, checked in the service as well as by the role annotation); the ticket must be one the lead may act on (`AccessPolicy`, action `REASSIGN`) |
| Which tickets | `CLASSIFIED`, `ASSIGNED`, `ACKNOWLEDGED`, `IN_PROGRESS`; `PENDING_INFO` and finished tickets 409; a ticket in triage 403 |
| To whom | A technician of the lead's own team (403 otherwise), active and not offline (409), with a free slot (409, everything undone), not the current holder (400) |
| Reason | Required (400); kept as an internal note on the ticket |
| What happens | Same mechanics as the administrator's assignment (ADR-026): old slot released, new slot taken, status `ASSIGNED` (the new technician acknowledges again; notes and the recorded first response stay), one `MANUAL` decision naming the lead (visible to administrators in "Why This Technician"), `IncidentAssigned` published |

Files: `TeamLeadReassignmentService`, `ReassignRequest`, `TechnicianController.reassign`, `User.canReassignTeamTickets` and `TeamLead`, `IncidentStatus.canBeReassignedByTeamLead`, `Incident.canBeReassignedBy`, `TechnicianIncidentView.reassignable` and `IncidentDtoMapper`, `app.js` (`openTeamReassignModal` and helpers), `styles.css`, `docs/uml/team-lead-reassignment.puml`. The dialog reads the live roster and the ticket when it opens, disables members who cannot take the ticket with the reason, requires a technician and a reason, opens one at a time and reloads the open tab after the move. The button follows the server's `reassignable` flag.

## Phase M4: buttons that do what they say (2026-11-19)
| Finding | Fix |
|---|---|
| "Auto-Dispatch All" had no handler and no endpoint | Removed; the card says unassigned confident tickets are assigned automatically within about 30 seconds and a lead can assign one |
| "Acknowledge & Handle" failed with 409 on every unassigned ticket, and on a held ticket silently acknowledged for the technician | Replaced by "Assign" (unassigned list; a ticket in triage shows "In triage: an administrator decides" and no button); a lead acknowledging a colleague's ticket sees "Acknowledge for <name>" |
| "Inspect Escalation" only switched tab and left the mode buttons showing "my queue" | Opens the team queue on the Technician tab at that ticket: `setTechQueueMode` keeps state and buttons together, the status filter is cleared, the card is scrolled to and highlighted once |

## Phase M5: proof (2026-11-19)
- **Tests (suite 1598 -> 1668, 70 new)**: `TeamLeadRosterScopeTest` (5), `TeamLeadPanelMarkupTest` (5), `TeamLeadRefreshMarkupTest` (8), `TeamLeadReassignMarkupTest` (13, includes "every button the tab draws has a click handler"), `IncidentReassignabilityTest` (20: the whole status table, triage, roles), `TeamLeadReassignmentContractTest` (16, through HTTP: every allowed status, every refusal, both technicians' slots, the decision, the note, a race, the server flag, the employee view), `TeamLeadReassignmentSequenceTest` (3: three seeded random walks of 40 steps each, the capacity invariant and "exactly one holder per open ticket" after every step; the administrator's detail shows the lead's move by name; the queues follow the move).
- **Mutation checks** (each made tests fail, then undone): misspelt load field; open filter removed; roster scope removed; timer ignoring the open tab; loading text on every reload; target-team check removed; access-policy check removed; previous holder never released; triage check removed; decision not recorded; `PENDING_INFO` allowed; offline check removed.
- **Edge**: `team_lead_refresh_browser.py` 15 of 15; `team_lead_reassign_browser.py` 19 of 19 (start the app with `--iitsm.sla.scan-interval-ms=3000`); `action_buttons_browser.py` 35 of 35 after updating its team-lead scenarios to the new behaviour (a P1 that is on track is not an escalation, a triage ticket has no button); `triage_browser.py` 14; `suggestions_browser.py` 24; `team_view_browser.py` 7.
- **API live checks**: `assignment_explanation_check.py` 22, `routing_check.py` 17, `capacity_burst.py`, `security_checks.py` 22 (the last two scripts that check registration and Swagger need the production-style start arguments in their headers). A scratch stress run over HTTP (8 tickets, 120 moves from 4 threads): 49 moved, the rest were the documented 409 (conflict or status) and 400 (same technician), no 500, the capacity audit showed 0 discrepancies and every newest decision matched its holder.
- **Gate**: `mvn -o clean verify` 1668 tests, 0 failures, 0 skipped, coverage gate met; every class in its own JVM (236 classes) 1668 tests, 0 failures, 0 skipped; no ERROR in the app logs. Not run: CI on JDK 21 and browsers other than Edge. Nothing is committed (user's instruction).
- Found on the way and not part of this plan: the administrator's own reassign dialog (`openReassignModal`) shows "(Tech)" for everyone because it reads a `domain` field the API does not send; the old live-check scripts `security_checks.py` and `team_view_browser.py` give false failures unless the app is started with the arguments in their headers (documented there).

## Known gaps
- The reassignment reason is an internal note, not a field of the decision.
- A lead cannot move a ticket in triage, waiting for the employee, or to another team; the administrator does that.
- The move returns the ticket to `ASSIGNED`; the new technician acknowledges again.
- The tab reloads only while it is the active tab; each reload reads the whole team queue including finished tickets (two requests every 10 s per open tab).
- A lead without a support team sees an empty roster.
- Not run: CI on JDK 21 and browsers other than Edge.
