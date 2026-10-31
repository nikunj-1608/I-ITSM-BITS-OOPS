# Ticket Nomenclature - Phase 4: Staff Display Name with Priority

Scope: technicians and admins get a ticket name with the current priority in front (`P2-FIN-2026-000142`). The stored id never changes. Employees see the plain id. Decision record: ADR-013 (item 4), chosen as "Option A" earlier.

## What was built
| Piece | Where | Role |
|---|---|---|
| `TicketDisplayName` | `domain/model` | `of(priority, id)` builds `P2-<id>`; `canonicalId(text)` reduces a complete display name back to the plain id and leaves anything else unchanged. |
| `displayId` field | `TechnicianIncidentView`, `AdminIncidentView` (so also `AdminIncidentDetailView`) | Next to `id`. `EmployeeIncidentView` is deliberately untouched. |
| `IncidentDtoMapper` | `web/mapper` | Fills `displayId` from the incident's current priority decision; a ticket without a priority shows its plain id. |
| `loadIncident` | `IncidentLifecycleService`, `AdminIncidentService` | Accept the plain id or the display name (covers every endpoint that takes a ticket id). |
| `SearchService`, `IncidentSpecifications.containsText` | `application`, `infrastructure` | The text search accepts a complete display name and now also matches the ticket id. |

## Rules
- Hyphens only, no spaces or underscores. Priority comes first.
- Derived on every render, never stored: an override changes the name immediately, the id inside it does not change.
- Only a complete name is recognised (`P1`-`P4` followed by a full ticket id). `P2-FIN`, `P5-...`, `P2 FIN-...` and ordinary words are searched as typed.

## Tests added (suite 337 -> 368)
- `TicketDisplayNameTest` (21 cases): format, no priority, follows the priority, canonical id for all priorities and case/blank variants, partial/other text unchanged, legacy ids.
- `TicketDisplayIdMapperTest` (5): technician and admin views, override changes the name not the id, unclassified ticket, admin detail JSON has `displayId` at top level, employee view has no priority or display name (by type and in its JSON).
- `TicketDisplayNameLookupRegressionTest` (2, real pipeline): ticket opens by plain id and by display name; an admin override addressed by display name works and the display name then shows the new priority.
- `SearchServiceTest` (+1) and `IncidentSpecificationsTest` (+1): search by id, by display name, half-typed name not guessed.

## Verified in the running app (dev profile, real HTTP)
Raised `ENG-2026-000001`. Admin detail, admin search (by plain id and by `P3-ENG-2026-000001`) and the technician queue show `displayId: P3-ENG-2026-000001`. Admin detail by display name: 200. Override to P1 addressed by display name: 200, and the detail then shows `P1-ENG-2026-000001` with the same id. The employee response has no `displayId`.

## Finding: priority already leaks to employees through the audit trail (pre-existing, NOT fixed here)
The employee ticket view includes the activity history, which contains an entry like `PriorityCalculated: "Calculated priority P3 (Score: 0.3975)"`. So an employee can already see their ticket's priority and score, against the project's privacy invariant. This phase does not make it worse (the display name is not in employee responses) and does not change it. A likely fix is to leave `PriorityCalculated` and `PriorityOverridden` out of the employee audit trail in `IncidentDtoMapper.toEmployeeView`. Decision for the project owner.

## Known gaps
- The web UI does not show `displayId` yet (Phase 5).
- Notification and email texts still use the plain id.
- Search by a half-typed display name finds nothing by design.
