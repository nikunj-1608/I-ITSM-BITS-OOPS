# Ticket Nomenclature - Phase 2: Persistence and Generation for New Tickets

Scope: new tickets now get `DEPT-YEAR-SEQUENCE` ids (e.g. `ENG-2026-000001`). Existing tickets keep their `INC-XXXXXXXX` ids until Phase 3. Decision record: ADR-013 (item 6 added in this phase).

## What was built
| Piece | Where | Role |
|---|---|---|
| `V3__ticket_sequences.sql` | `db/migration` | Table `ticket_sequences(department_code, ticket_year, last_sequence)`, PK (code, year). |
| `TicketSequenceJpaEntity` | `infrastructure/persistence/entity` | Maps the table (`@IdClass` composite key). |
| `SpringDataTicketSequenceRepository` | `infrastructure/persistence/repository` | `increment` (atomic UPDATE), `currentValue`, `insertFirst` (plain INSERT). |
| `TicketSequenceCounter` | `infrastructure/persistence/adapter` | One reservation attempt in its own `REQUIRES_NEW` transaction. |
| `TicketSequenceRepositoryAdapter` | `infrastructure/persistence/adapter` | Implements the domain port; retries when two callers race to create a row. |
| `TicketNumberConfig` | `infrastructure/config` | Registers the `DepartmentCodeResolver` bean. |
| `TicketNumberGenerator` | `application/service` | reporter -> department code (resolver) + year (`Clock`) + sequence (port) -> `TicketNumber`. |
| `IncidentFactory` | `application/service` | Now takes the reporter `User` and uses the ticket number as the incident id. |
| `IncidentIntakePipeline` | `application/service` | Passes the loaded reporter to the factory (one-line change). |
| `User.getDepartment()` | `domain/model` | Returns `null` by default; `Employee` overrides it. Lets the generator ask instead of testing the type. |

## How a number is issued
1. Resolver turns the reporter's department into a code (blank or non-employee -> `MISC`).
2. Year comes from the injected `Clock` (so the simulation's virtual clock drives it too).
3. `reserve` runs in its own transaction: atomic `UPDATE ... last_sequence + 1`, then read the value; if no row exists, `INSERT` it at 1. If another thread created the row first the INSERT fails on the duplicate key, the adapter retries (up to 3 attempts) and takes the increment path.

## Decision worth knowing: gaps are possible
Intake is one long transaction (classification, priority, assignment, notifications). Reserving the number inside it would hold the counter row lock for the whole intake and make every raise for a department queue behind the previous one. So a number is reserved in its own short transaction and committed at once. If the intake later rolls back, that number is not reused (a gap). Uniqueness is never at risk. The Phase 1 port Javadoc and ADR-013 were updated to say this.

## Bug found by the new concurrency test (and fixed)
The first version created the first row with `saveAndFlush`. For an entity with a composite id Spring Data uses `merge`, which silently overwrote a row another thread had just created, resetting the counter and issuing a duplicate (1 duplicate in 120 parallel calls). Replaced with a native `INSERT` that fails on the duplicate key. The test now passes repeatedly (5 extra runs on fresh databases).

## Tests added (suite 301 -> 313)
- `TicketNumberGeneratorTest` (6): per-department independence, year rollover with fixed clocks, `MISC` for a reporter without a department, null handling, `IncidentFactory` uses the ticket number as the id.
- `TicketSequenceRepositoryAdapterTest` (4, H2 + Flyway): starts at 1, departments independent, years independent, 8 threads x 15 calls give unique gap-free numbers including the first-row race.
- `TicketNumberIntakeRegressionTest` (2, full pipeline): id is `ENG-<year>-<seq>` for the Engineering demo employee, consecutive numbers within a department, separate counter for Executive.

## Verified in the running app (dev profile)
Employee raised 3 tickets and the CEO 1: `ENG-2026-000001`, `ENG-2026-000002`, `EXE-2026-000001`, `ENG-2026-000003`. Employee GET by the new id: 200; admin detail by id: 200; admin list shows the new ids; another employee's ticket: 403. Auto-assignment still works (tickets with clear text were assigned).

## Known gaps (later phases)
- Existing tickets still have `INC-` ids; the seed does not create tickets, so a fresh dev database has none (Phase 3 migrates persistent databases).
- No priority display name yet (Phase 4); the UI shows ids as plain strings and needs no change for the new format, final checks in Phase 5.
- `DomainLifecycleDemo` still builds an incident with a hard-coded `INC-1001` (a demo, excluded from coverage).
- Departments are free text, so a department with a typo gets its own fallback code and counter.
- Technicians or admins raising a ticket would get `MISC`.

## Verification
`mvn verify` (Maven 3.10.0 from `~/.m2`, the wrapper's 3.9.9 download is incomplete on this machine): 313 tests, 0 failures, JaCoCo gate met.
