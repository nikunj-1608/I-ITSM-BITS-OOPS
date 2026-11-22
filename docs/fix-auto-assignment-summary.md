# Fix summary: automatic ticket assignment

## Problem
New tickets stayed unassigned. Intake auto-assigns only when classification confidence is >= 0.60
(`iitsm.classification.min-confidence`); lower-confidence tickets are flagged `needsTriage` and the
`UnassignedIncidentDispatcher` intentionally never retries them.

## Root cause (confirmed by running the app)
`CompositeClassifier` capped confidence at 0.55 whenever the keyword classifier scored <= 0.50
(zero or one keyword hit), regardless of Naive Bayes agreement. Short, natural-language tickets
therefore landed in the triage queue and were never auto-assigned. The technician pool itself was
healthy (24 technicians, all ONLINE with free capacity).

Naive Bayes is badly overconfident without keyword support (0.84 to 0.97 on vague text), so it
cannot simply be trusted to decide alone; keyword evidence must corroborate it.

## What changed
| Area | Change |
|---|---|
| `KeywordClassifier` | A single keyword hit in the title scores 0.55; a body-only hit stays 0.50. |
| `CompositeClassifier` | Evidence-based triage cap with named constants (see ADR-009). |
| `TechnicianAssignmentService` | Absorbs only `TechnicianOverloadedException`; other failures propagate. |
| `IncidentIntakePipeline` | Catches only expected contention; WARN when no technician has capacity. |
| `UnassignedIncidentDispatcher` | One lost lock race no longer aborts the cycle; WARN and stop when the pool is empty. |
| `SpringDataUserRepository` | Null-safe `findAvailableTechnicians` (NULL status / NULL capacity). |

## Where each pattern lives and why
- **Strategy / Composite**: `CompositeClassifier` fuses `KeywordClassifier` and `NaiveBayesClassifier`
  behind `IncidentClassifier`; callers are unchanged (Open/Closed).
- **Single responsibility**: the keyword classifier decides how strong its own evidence is
  (title vs body); the composite decides what that means for triage.
- **Fail-safe dispatcher**: the scheduled dispatcher is the self-healing second chance, so a failure
  on one ticket must not poison the rest of the cycle.

## Tests added (suite: 229 tests, `mvn verify` green, JaCoCo checks met)
- `CompositeClassifierTest` (9): each fusion rule plus real-model cases.
- `TechnicianAssignmentServiceTest` (4), `UnassignedIncidentDispatcherTest` (4).
- `AvailableTechniciansQueryTest` (2): NULL-safe query semantics.
- `AutoAssignmentRegressionTest` (3): end-to-end on the seeded company. The VPN-title test fails
  against the original classifier and passes now.

## Known gaps
- A ticket that is clear to a human but has no keyword hit (e.g. "printer thing") still goes to triage;
  the matched IT service ("Printing") is not used as evidence.
- A ticket whose title contains a keyword by accident, with Naive Bayes agreeing, will auto-assign.
- `UserMapper` defaults a NULL capacity to 5 while the query treats it as uncapped; the domain
  `hasCapacity()` check runs afterwards, so over-assignment is not possible.
- Notification emails log WARN when no mail server (Mailpit, port 1025) is running; unrelated to assignment.
- PlantUML diagrams in `docs/uml/` are unchanged: no classes, relationships or states were added.
