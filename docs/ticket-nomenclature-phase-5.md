# Ticket Nomenclature - Phase 5: Web Display, Documentation and Final Verification

Scope: show the staff ticket name next to the plain ticket id in the web app (as chosen: "alongside"), update the project documentation, and verify the whole feature end to end. Decision records: ADR-013, ADR-014.

## What was built
| Piece | Where | Role |
|---|---|---|
| `ticketNameHtml(ticket, asBlock)` | `static/app.js` | One helper: renders the staff name, escapes it, returns nothing when `displayId` is missing or equals the id. |
| Technician cards, team lead lists (unassigned, escalations) | `app.js` | Name shown right after the plain id. |
| Command Centre table | `app.js` | Plain id stays the clickable link (and the `data-id` key of every button); the name is a muted second line under it. |
| Admin detail dialog | `index.html`, `app.js` (`renderTicketDetailHeader`) | New `#ticket-detail-display-name` next to the id. |
| Search box hint | `index.html` | "Search title, ID or ticket name..." (the backend search accepts either form since Phase 4). |
| Styles | `styles.css` | `.ticket-display-name`, `.ticket-display-name-block`, hidden when empty. |
| Cache-bust | `index.html` | `?v=3.4` for `styles.css` and `app.js`. |
| Employee screens | unchanged | Plain id only; no staff name anywhere. |
| UML | `docs/uml/class-diagram.puml`, both lifecycle sequence diagrams | New classes and the numbering step in the creation flow. |
| `CLAUDE.md` | root | Sections 0, 2, 3.2, 3.3, 3.10 (new), 4, 5, 6 and 7 brought in line with the code. |

## Tests added (suite 373 -> 380)
- `TicketDisplayNameMarkupTest` (7): helper escapes and hides a redundant name; staff screens use it; plain id stays the link and button key; employee render functions never use it; dialog element and styles exist; search hint; assets at `?v=3.4`, no inline handlers, no external resources.
- `MultiSelectFilterMarkupTest.assetsAreCacheBusted` now expects `?v=3.4` (the project rule is to bump the version whenever these files change).

## Verified in a real browser against the running app (headless Edge, real login form and clicks)
12 of 12 checks, no console errors:
- Admin: every Command Centre row shows the name; the id is still the link text and key; name = priority + plain id (`P3-ENG-2026-000001`); the detail dialog shows id and name together; typing the name in the search box finds the ticket.
- Technician: the queue card shows the plain id and the name.
- Employee: ticket list shows plain ids only; no ticket name element anywhere; no priority-prefixed name in the page text; the details dialog contains no "Calculated priority", "Score:", "SLA Warning/Violated" or "Priority changed" text.
(The script is not in the repository.)

## Feature summary (all phases)
New tickets are `DEPT-YEAR-SEQUENCE` (e.g. `FIN-2026-000142`), gap-free, restarting each year, per department. Old `INC-` tickets are renamed by migration V4 with every reference carried along. Staff see `P2-FIN-2026-000142` next to the id; employees never see the priority (and, after ADR-014, no priority text in their activity history).

## Known gaps
- Notifications and emails for staff still use the plain id; the triage queue has no staff name.
- `GET /favicon.ico` returns 500 instead of 404 (pre-existing, see CLAUDE.md section 7).
- The browser check script is not part of the repository.
