# Ticket Nomenclature - Phase 3: Migration of Existing Tickets

Scope: tickets that still have the old random id (`INC-XXXXXXXX`) are renamed to `DEPT-YEAR-SEQUENCE`, with every reference carried along. Decision record: ADR-013 (item 5).

## What was built
| Piece | Where | Role |
|---|---|---|
| `V4__rename_legacy_tickets` | `src/main/java/db/migration` | Thin Flyway Java migration (Flyway needs this package and class name). |
| `LegacyTicketRenamer` | `infrastructure/persistence/migration` | The logic, plain JDBC so it behaves the same on PostgreSQL and H2. |
| `TestPostgres` | `src/test/.../testsupport` | Starts a real PostgreSQL for tests (see "Testing on PostgreSQL"). |
| `embedded-postgres` 2.0.7 | `pom.xml`, test scope | Runs PostgreSQL inside the test JVM; no Docker or install needed. |

## What the migration does (in this order, one transaction)
1. Plan: read every incident with its reporter's department and creation time. Ids already in the new format only raise the counters. Each old id gets `CODE-YEAR-SEQ`: code from the resolver (blank -> `MISC`), year from `created_at` in UTC, sequence in creation order, continuing after the highest number already used for that code and year (from `ticket_sequences` and from ids already in the table).
2. If there is nothing to rename, stop. A fresh database is left exactly as it was.
3. Re-create the nine foreign keys pointing at `incidents(id)` with `ON UPDATE CASCADE`, keeping each delete rule. Names are read from the JDBC metadata.
4. Update ids in `technician_active_incidents`, `incident_linked_ids.linked_incident_id` and `audit_records` (no foreign key there), then `incidents.id` itself (the cascade updates the foreign-key children).
5. Rewrite old ids quoted in `notifications` (subject, message) and `audit_records.description`. Ids that are not tickets in this database are left alone. User-written text (descriptions, notes) is not touched.
6. Save the counters and check that no incident still has an old id; otherwise fail and roll back.

## Safety
- All or nothing on PostgreSQL: a failure rolls back everything and the application refuses to start instead of running on a half-renamed database (tested by rolling the whole rename back).
- Idempotent: a second run renames nothing.
- New tickets keep working afterwards: the counters continue after the renamed tickets, verified through the real application (below).
- On H2 (dev/test, in-memory and empty at start) the migration is a no-op.

## Tests added (suite 318 -> 337)
- `LegacyTicketMigrationH2Test` (8) and `LegacyTicketMigrationPostgresTest` (9 = the same 8 + rollback), sharing `LegacyTicketMigrationTestBase`: ids per department/year/creation order; every dependent row follows (factor contributions, notes, SLA tracker, escalation, reassignment, linked ids in both columns, technician workload, audit); no `INC-` left in any referencing column; text rewrite (unknown ids untouched); counters; coexistence with tickets already in the new format; foreign keys now cascade updates and still cascade deletes; second run is a no-op; fresh database untouched; PostgreSQL rollback.
- `TicketNumberingPostgresRegressionTest` (2, whole application on PostgreSQL): all four migrations apply, Hibernate validates the schema, a new ticket is stored and readable; old tickets are renamed, load with their notes and audit trail, and the next new ticket continues after them.

## Testing on PostgreSQL (what was and was not verified)
Verified on a real PostgreSQL 14 (embedded, in-process): the migration scenarios and the whole application. Not verified: a production-sized database or your own PostgreSQL server. Run `mvn verify` there before deploying, and take a backup first.

Machine-specific problem found on the way: PostgreSQL could not listen on 127.0.0.1 ("Permission denied") when its program files were extracted to the Windows Temp folder, so the connection was refused; running it from `target/embedded-pg` works. `TestPostgres` therefore uses `target/embedded-pg`, picks a port by binding it on 127.0.0.1 first, waits up to 45 s and retries. On Linux CI nothing special is needed.

## Known gaps
- Priority display name and UI are still to do (Phases 4 and 5).
- Very large databases: renames run one statement per ticket in batches inside one transaction; fine for tens of thousands of tickets, untested at larger scale.
- Text written by users that mentions an old ticket id is not rewritten.
- Ticket links in already-sent emails or bookmarks to old ids stop working (old ids no longer exist).
