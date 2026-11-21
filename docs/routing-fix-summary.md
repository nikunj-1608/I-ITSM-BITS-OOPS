# Routing in one place (Phase G)

Remaining-defects plan, Phase G. ADR-036 (stress-audit H9 cause 1, L6; decisions D17 and D18). No migration, no API or UI
change.

## What was wrong

Which team gets a ticket was decided in four places by two rules, and neither followed the classification:

1. The intake and the dispatcher took the owning team of the ticket's matched **service**; a ticket that matched no service
   fell back to the general service `SRV-GEN-001`, owned by the Service Desk. A DNS ticket, correctly classified NETWORK,
   went to the Service Desk (13 of the 36 held-out tickets match no service).
2. When that team was full, `TicketDispatchService` and `AdminIncidentService.acceptTriageSuggestion` each offered the ticket
   to **anyone** with capacity, an unskilled technician of any team.
3. The administrator's reclassify rerouted by the service's owner when a service was sent, by the category when not.
4. The access rules (single-ticket access, the team queue of Phase E, the support team in the admin detail view) used the
   service owner too, so "whose ticket is it" and "who was offered it" could differ.

## What changed

| Area | Change |
|---|---|
| `RoutingPolicy` (domain) | the team that handles the category (smallest id when several do); the owner of the service only when no team handles the category |
| `TicketRouter` (application) | the one place that names the owning team and the candidates: `owningTeamId`, `candidates`, `categoriesHandledBy` |
| `CandidateFilter` | `filterCandidates(technicians, owningTeamId, category, exclusions)`: the owning team first; when it is full, technicians of other teams with the category skill; otherwise nobody |
| `TicketDispatchService` | asks the router; the "anyone" fallback is gone, a ticket nobody may take is `NOT_ASSIGNED` and waits in `CLASSIFIED` |
| `AdminIncidentService` | reclassify and accept-suggestion ask the router (candidates are read before the ticket changes); the duplicate fallback and `owningTeamFor` are gone |
| `IncidentLifecycleService` | `resolveOwningTeamId` by category; the team queue by category (`IncidentRepository.findByCategoryIn`) |
| `AdminIncidentDetailService` | the support team of an unassigned ticket is the team of its category |
| `ItService.GENERAL_SERVICE_ID`, `IncidentStatus.canBeRerouted()` | the general service id in one constant; the "may this ticket be moved" status rule on the status, not in the service |

The service still decides business criticality, priority and the SLA policy.

## Measured

| Measure (Phase A routing replay through the real router) | Before | After |
|---|---|---|
| Held-out auto-routed tickets that reach the team of their true category | 15/36 | **31/33** (two classification misses, H8 and H17) |
| Audit examples | 5/8 | **7/7** |
| Held-out and audit together (exit target 95 %) | | **38/40 = 95.0 %** |

By construction every auto-assigned ticket is with the team of the category it was given: `TicketRoutingContractTest`
proves it for six categories through all four callers (24 cases).

## Behaviour changed on purpose

- A ticket goes to the team that handles its category, whatever its service; a DNS ticket without a service goes to Network.
- A full team no longer sends its tickets to an unskilled technician elsewhere: they go to a skilled technician of another
  team, otherwise they wait (the dispatcher retries with the same rule; the SLA escalation covers a long wait).
- The Service Desk receives only HARDWARE tickets and what it is skilled for.

## Tests (suite 1086 to 1157)

| Class | Tests | What it proves |
|---|---|---|
| `RoutingPolicyTest` | 5 | category decides, service only a fallback, stable choice |
| `CandidateFilterTest` | 7 | team first, skilled overflow, unskilled never, exclusions, case, missing team or category |
| `TicketRouterTest` | 6 | category team, service fallback, no service lookup when a team exists, candidates, exclusions |
| `TicketRoutingContractTest` | 30 | the shared contract across intake, dispatcher, reclassify and accept-suggestion (24), the DNS ticket, a service owned by another team, skilled overflow, waiting and the dispatcher retry, accept-suggestion waiting, reclassify without a service |
| `IncidentStatusReroutingTest` | 18 | which statuses can be rerouted |
| `TeamQueueAccessRegressionTest` (+2) | 8 | the team queue and single-ticket access follow the category |
| `AdminIncidentDetailServiceTest` (+1), `TicketDispatchServiceTest` (+2, 1 changed) | | the support team by category; skilled overflow and "unskilled never" |

Changed on purpose: `ReclassifyRoutingRegressionTest` (T1, T4, T5), `ReclassifyEndpointRegressionTest` E3 (was "the service
wins", now "the category's team wins"), `ReclassifyStateRegressionTest` R5, `CorrectnessRegressionTest.F7` (the single free
slot is a network technician and both reports are network tickets), `CapacityRaceRegressionTest` (clears the slots each
round), `AssignmentStrategyTest` (new filter signature), the constructors of the three `AdminIncidentService*Test` classes and
`AdminIncidentDetailServiceTest`. `TeamQueueAccessRegressionTest` now places its ticket explicitly: its earlier skip came
from the same text linking later tickets as duplicates that ride their parent's technician (F4).

Mutation checks (each made tests fail, then undone): service owner first, incident owner by service, unfiltered overflow, the
"anyone" fallback back, accept-suggestion without the router, reclassify by the service owner, single-ticket access by the
service owner (found a gap, test added), the admin detail by the service owner, an empty team queue, the policy choosing the
largest team id.

## Verification

- Full `mvn -o clean verify`: 1157 tests, 0 failures, JaCoCo gate met; its one skip (`TeamQueueAccessRegressionTest`, the
  duplicate effect above) was removed afterwards by the test change.
- Every class in its own JVM (147 classes): 1157 tests, 0 failures, 0 skipped.
- Live, against the running app (`scripts/live-checks/routing_check.py` and `classification_audit.py`): one ticket per
  category, every assigned ticket is with the team that handles its category and the ticket detail reports that team. The DNS
  ticket of the stress audit is with the Network team (it went to the Service Desk before), the checkout ticket A12 is with
  the Application team (it went to the Cloud team); all 11 audit examples still behave as expected. No ERROR in the app log.
- Not run: CI on JDK 21; a browser run (no screen changed).

## Known limits

- The seeded technicians have skills only for their own team's category, so no overflow happens until an administrator gives a
  technician another category's skill (there is no screen for skills).
- A multi-category team's members carry the skill of one of its categories only (the seeder takes the first); routing inside
  the team is unaffected.
- A waiting ticket is visible to its owning team and the administrators, not to skilled outsiders.
- A burst of network faults can fill the Network team (3 technicians and a lead); new network tickets then wait.
- The two held-out tickets that still reach the wrong team (H8, H17) are classification errors (Phase F limits).
