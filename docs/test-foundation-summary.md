# Test foundation (remaining-defects plan, Phase A): summary

Branch `bug-fixes`, 2026-11-05. ADR-025. Plan and decisions: `docs/remaining-defects-plan.md`.
No production code changed in this phase (the only file under `src/main` touched is `Incident.java`, whose line endings were restored to CRLF; its content is unchanged).

## Why
The later phases change classification, routing, capacity accounting and SLA rules on purpose. Before that, the suite had to show regressions reliably (no test that passes or fails depending on order), quickly (a short lane for routine work), and against recorded numbers (benchmarks).

## What was found
| # | Finding | Evidence |
|---|---|---|
| 1 | `IncidentRepositoryAdapterTest` fails alone (P5): its tickets reference the seeded reporter and service, and it never seeded | Run alone before the fix: 2 errors (`PersistenceFailure` at lines 63 and 89) |
| 2 | **New:** `SimulationControllerTest.testRunScenarioS1` fails alone. The class is `@Transactional` and seeded inside the test transaction; ticket intake assigns the technician in a separate transaction (`REQUIRES_NEW`) that cannot see uncommitted rows, so S1's ticket got no technician and the next step failed with 400 "User identifier cannot be blank" | Whole suite with one JVM per class before the fix: 808 tests, 1 failure, 2 errors; the intake log shows `assigned to null` with no triage or capacity message |
| 3 | P6 (shared SMTP port): the five mail tests already count only their own messages (by subject or ticket id), so a late message from another class cannot fail them | Read `CorrectnessRegressionTest.F8`, `NotificationAfterCommitRegressionTest`, `NotificationPoolStarvationRegressionTest`, `AutoAssignmentRegressionTest` |
| 4 | About 2,000 WARN lines per full run, 1,600 of them failed e-mail deliveries in classes that run no SMTP server | Baseline log: 806 `EmailChannel` + about 800 `NotificationRouter` lines |
| 5 | **New (for Phase C1):** a wrong HTTP method returns 500 instead of 405 | `SecurityRegressionTest.S8` sends `GET /api/auth/login`; the catch-all logs `Unhandled server exception: Request method 'GET' is not supported` |
| 6 | Full run time is dominated by the embedded-PostgreSQL classes | `OverrideDeadlineRepairPostgresTest` 90 s, `LegacyTicketMigrationPostgresTest` 85 s, `TicketNumberingPostgresRegressionTest` 12 s |

## What changed
- `IncidentRepositoryAdapterTest`: seeds the company data itself (`@BeforeEach`, the pattern other classes use).
- `SimulationControllerTest`: seeds in `@BeforeTransaction`, so the seed is committed before the test transaction starts.
- `@Tag("postgres")` on the three embedded-PostgreSQL classes; `pom.xml` profile `fast` excludes `postgres` and `benchmark` and skips JaCoCo.
- Test `application.yml`: `EmailChannel` and `NotificationRouter` log at ERROR.
- New `com.iitsm.benchmark` (test scope): `ClassificationBenchmarkTest`, `DuplicateBenchmarkTest`, `BenchmarkData`, `BenchmarkReport`; new data file `src/test/resources/classification/audit_examples.tsv` (12 tickets: the four misclassified audit titles, the repeated keyword, the DNS ticket, one vague ticket and five controls that use the generic lexicon words correctly).
- New `com.iitsm.testsupport.CapacityInvariant` and `CapacityInvariantTest`.
- Docs: ADR-025, `docs/remaining-defects-plan.md`, this file, CLAUDE.md sections 0, 4, 6, 7.
- No UML change: no production class was added or changed.

## Baseline numbers (2026-11-05, recorded by the benchmarks)
Classification (gate 0.60), held-out `test_tickets.csv`, 36 tickets:
- correct category 32/36 (misses: Zoom video frozen -> HARDWARE, EKS node NotReady -> STORAGE, CFO wire-transfer phishing -> APPLICATION, SAN switch port -> HARDWARE);
- sent to triage 0/36;
- routed (by today's rule: matched service, else the general service) to the team that handles the true category: **15/36**;
- no service matched, so the general service (Service Desk) was used: 13/36.

Audit examples, 12 tickets: 8/12 meet the expectation. A01 phishing + Excel macro -> SOFTWARE 0.90; A02 desk drawer PIN -> AUTHENTICATION 0.90; A03 parking garage door -> APPLICATION 0.75; A04 coffee machine power switch -> NETWORK 0.84 (all four reproduce audit finding H9). The five controls with generic words (locked, connection, power, port, order) are all correct; they guard the lexicon clean-up in Phase F.

Duplicates (threshold 0.30, one shared domain term): identical 15/15, S4 demo 15/15, adjudicated live 9/9, paraphrases 52/87 (target 59 % needs 52: **no link to spare**), every negative group 0 links, precision 91/92 (the known false link L02, cosine 0.3291). Three filler-only pairs score above the threshold (up to 0.954) and are blocked only by the content guard. Highest non-duplicate that passes the guard: 0.2455, margin 0.0545 (ADR-016 requires 0.05).

## Proof that the new checks can fail
- `IncidentRepositoryAdapterTest` and `SimulationControllerTest` failed in the one-JVM-per-class run before the fix and pass after it.
- `CapacityInvariantTest`: disabling the phantom-slot and the missing-slot detection in `CapacityInvariant` made `reportsPhantomSlot` and `reportsMissingSlot` fail (2 failures); the file was restored byte for byte.

## Verification
| Run | Result | Time |
|---|---|---|
| Fast lane `mvn -o test -Pfast` | 784 tests, 0 failures, 0 errors, 0 skipped | 46 s |
| Full `mvn -o clean verify` | 813 tests, 0 failures, 0 errors, 0 skipped, JaCoCo gate met | 4 min 13 s |
| One JVM per class (`-DforkCount=1 -DreuseForks=false`) | 813 tests in 105 classes, each in a fresh JVM (empty database): 0 failures, 0 errors, 0 skipped | 12 min 19 s |
| Targeted, one JVM per class: the two fixed classes and the four new ones | 12 tests, 0 failures | |

Log of the full run: 395 WARN lines (2,019 before), 10 ERROR lines, the same 10 as before, all explained: 4 lines from two deliberate start-up failures (weak JWT secret: `Round3RegressionTest.N3`, `SecurityRegressionTest.S1`), 2 deliberate channel failures (`NotificationSystemTest`, `TransactionAwareDomainEventPublisherTest`), 1 deliberate "boom" in the handler test, 2 ticket-counter primary-key races from the parallel counter test (audit L1, expected, the request recovers), and 1 line for the 405-as-500 case above.

Not run: CI on JDK 21 (local JDK 24.0.2).

## Known limits
- Six other `@Transactional` classes seed inside the test transaction (listed in ADR-025); they pass alone today.
- The fast lane does not run the PostgreSQL migration tests; phase gates run the full verify.
- The benchmark's routing figure replays today's intake rule in the test; Phase G replaces the rule and the benchmark must then call the shared router.
- The benchmarks report numbers but enforce nothing yet (phases F and G add the acceptance checks).
