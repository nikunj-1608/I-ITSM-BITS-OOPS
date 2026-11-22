# Ticket Nomenclature - Phase 2: Persistence and Generation for New Tickets

Scope: new tickets now get `DEPT-YEAR-SEQUENCE` ids (e.g. `ENG-2026-000001`). Existing tickets keep their `INC-XXXXXXXX` ids until Phase 3. Decision record: ADR-013 (item 6).

## What was built
| Piece | Where | Role |
|---|---|---|
| `V3__ticket_sequences.sql` | `db/migration` | Table `ticket_sequences(department_code, ticket_year, last_sequence)`, PK (code, year). |
| `TicketSequenceJpaEntity` | `infrastructure/persistence/entity` | Maps the table (`@IdClass` composite key). |
| `SpringDataTicketSequenceRepository` | `infrastructure/persistence/repository` | `increment` (atomic UPDATE), `currentValue`, `insertEmpty` (plain INSERT at 0). |
| `TicketSequenceRepositoryAdapter` | `infrastructure/persistence/adapter` | Implements the domain port. Runs inside the caller's transaction (propagation MANDATORY). |
| `TicketSequenceRowCreator` | `infrastructure/persistence/adapter` | Creates a missing counter row in its own short transaction (first ticket of a department/year only). |
| `TicketNumberConfig` | `infrastructure/config` | Registers the `DepartmentCodeResolver` bean. |
| `TicketNumberGenerator` | `application/service` | reporter -> department code (resolver) + year (`Clock`) + sequence (port) -> `TicketNumber`. |
| `IncidentFactory` | `application/service` | Takes the reporter `User`; the ticket number is the incident id. |
| `IncidentIntakePipeline` | `application/service` | Passes the loaded reporter to the factory (one-line change). |
| `User.getDepartment()` | `domain/model` | `null` by default, overridden by `Employee`. |

## How a number is issued (no gaps)
1. The resolver turns the reporter's department into a code (blank or non-employee -> `MISC`); the year comes from the injected `Clock`.
2. Inside the ticket's own transaction: atomic `UPDATE ... last_sequence + 1`, then read the value. The row lock makes a concurrent ticket for the same department/year wait for this transaction to finish.
3. If the intake rolls back, the increment rolls back with it, so the number is handed out again: **the sequence never skips a number.**
4. If no counter row exists yet, it is created at 0 in a separate short transaction (0 is not an issued number) and the increment is repeated. If two callers race to create it, the loser's duplicate-key error is ignored.
5. Calling the port outside a transaction is refused (MANDATORY), because the increment and the read could otherwise interleave with another caller.

## Trade-off to know about
Intake is one long transaction (classification, priority, assignment, notifications), so tickets of one department and year are created one after another; the next waits for the previous to commit. Different departments do not wait for each other. A side benefit: a ticket raised right after a similar one now sees it for duplicate detection.

## Existing test changed: `CorrectnessRegressionTest.F7`
F7 raced two near-identical tickets from the same employee to prove a technician with one free slot is never overloaded. With same-department tickets now issued one at a time, the second sees the first, is linked to it as a duplicate and inherits its assignee, which is the designed duplicate behaviour and not an overload. The test now races two reporters from different departments (employee: Engineering, CEO: Executive) so it still exercises a real concurrent race. Assertions are unchanged.

## Bugs found by tests along the way (both fixed)
- The first-row creation used `saveAndFlush`; for a composite key Spring Data uses `merge`, which silently overwrote a row another thread had just created and issued a duplicate. Replaced by a native `INSERT`.
- A first design reserved the number in a separate transaction (no waiting), which left gaps on rollback; replaced by the design above.

## Tests (suite 301 -> 318)
- `TicketNumberGeneratorTest` (6): per-department independence, year rollover with fixed clocks, `MISC`, null handling, factory uses the ticket number as the id.
- `TicketSequenceRepositoryAdapterTest` (7, H2 + Flyway): starts at 1, departments and years independent, a rolled-back number is reused (also for the very first number), outside-a-transaction is refused, 8 threads x 15 calls unique and gap-free including the first-row race.
- `TicketNumberIntakeRegressionTest` (4, full pipeline): `ENG-<year>-<seq>`, consecutive numbers, separate counter for Executive, a failed intake leaves no gap, 6 parallel intakes get unique consecutive numbers.

## Known gaps (later phases)
- Existing tickets still have `INC-` ids (Phase 3).
- No priority display name yet (Phase 4); display name will use hyphens only, e.g. `P2-FIN-2026-000142`.
- `DomainLifecycleDemo` still builds an incident with a hard-coded `INC-1001` (demo, excluded from coverage).
- Departments are free text: a typo gets its own fallback code and counter. Technicians or admins raising a ticket would get `MISC`.
- Maven wrapper's 3.9.9 download is incomplete on this machine; builds were run with Maven 3.10.0 from `~/.m2`.
