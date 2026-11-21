# A bounded event history, notification metrics and a pinned duplicate case (Phase I)

Remaining-defects plan, Phase I. ADR-038 (stress audit P4, item R-N, test T1; decisions D21 and D22). No schema change, no
migration, no screen changed.

## In plain words

1. **Memory that never stopped growing (P4).** The part of the system that announces events ("ticket assigned", "ticket
   resolved") also kept a copy of every event it had ever announced, in a list that was never emptied. It was meant for tests,
   but the running application did it too, so memory grew with every ticket action. Now it keeps only the newest events
   (1000 by default) and **none at all in production**.
2. **Nobody could see a notification backlog.** Notifications go out through a queue worked by one background thread. If the
   queue backed up or overflowed, only the code knew. Now two numbers are published as metrics:
   `iitsm.notifications.pending` (waiting) and `iitsm.notifications.dropped` (lost). An administrator reads them at
   `/actuator/metrics/iitsm.notifications.pending`. While adding this we found that **any signed-in user** could read those
   Actuator pages; they are now administrators only (the health page stays public for monitoring probes).
3. **A documented but untested case (T1).** When a clear report duplicates a ticket that is waiting for a human decision
   (triage), what happens? Today: the report is linked to it, is not in triage, waits a moment and then the dispatcher gives it
   its own technician; the original stays with the humans. A test now pins that so nobody changes it by accident.

Skipped on purpose: the one-off clean-up of old database rows whose text was escaped before ADR-015 (R-C1 / "V7"). It is only
needed if a production database from before 2026-10-31 exists (decision D21), and nobody has said so. The instructions for
writing it are in `docs/remaining-defects-phase-details.md`.

## What changed

| Area | Change |
|---|---|
| `InMemoryDomainEventPublisher` | history limit (default 1000, 0 keeps nothing), immutable snapshot, thread-safe |
| `TransactionAwareDomainEventPublisher` | third constructor argument, the history limit |
| `DomainEventConfig` | reads `iitsm.events.history-limit` (env `EVENT_HISTORY_LIMIT`); registers the metrics bean; returns the concrete publisher type |
| `NotificationMetrics` (new) | the two metrics above |
| `application.yml` | `iitsm.events.history-limit`: 1000 by default, 0 in the `prod` profile |
| `SecurityConfig` | `/actuator/**` needs the administrator role; health stays public |
| test `application.yml` | exposes the `metrics` endpoint (the test configuration hides the shipped one) |

## How to check it yourself

- Automated: `mvn -o test "-Dtest=InMemoryDomainEventPublisherHistoryTest,NotificationMetricsTest,EventHistoryWiringTest,ObservabilityEndpointsRegressionTest,DuplicateOfTriageParentRegressionTest"`.
- Live: start the app, sign in as the administrator, call `GET /actuator/metrics/iitsm.notifications.pending` with the token
  (`scripts/live-checks/observability_check.py` does this and also checks that an employee gets 403).

## Behaviour changed on purpose

- Production no longer keeps an event history; other profiles keep the newest 1000 events.
- Actuator pages other than health need the administrator role (before: any signed-in user).

## Tests

| Class | Tests | What it proves |
|---|---|---|
| `InMemoryDomainEventPublisherHistoryTest` | 7 | bound, zero, default, snapshot, negative refused, clear, parallel publishers |
| `TransactionAwareDomainEventPublisherTest` (+1) | 12 | the limit passes through the wrapper |
| `NotificationMetricsTest` | 4 | names, pending follows the queue, dropped follows overflow, back to zero |
| `EventHistoryConfigContractTest` | 2 | shipped configuration: base 1000, prod 0 |
| `EventHistoryWiringTest` | 2 | the configured limit reaches the real publisher, metrics are in the registry |
| `ObservabilityEndpointsRegressionTest` | 3 | administrator reads, anonymous 401, employee and technician 403, health public |
| `DuplicateOfTriageParentRegressionTest` | 2 | the T1 behaviour |

## Known limits

- The metrics are per application instance and restart at zero; events in the queue when the process dies are still lost (ADR-017).
- R-C1 stays open until decision D21 is answered.

## Verification

- Full `mvn -o clean verify`: 1200 tests (1179 before), 0 failures, 0 skipped, JaCoCo gate met.
- Every class in its own JVM: 158 classes, 1200 tests, 0 failures, 0 skipped.
- Live, against the running app (`scripts/live-checks/observability_check.py`): 8 of 8 checks against the running app (administrator reads both metrics, the pending gauge returns to 0 after a report, employee and technician get 403, no token gets 401, health stays public), no ERROR in the app log.
- Mutation checks (each made tests fail, then undone): history never trimmed, the history limit ignored by the configuration,
  dropped counter reading the wrong value, pending gauge not registered, Actuator open to every signed-in user, production
  keeping the history, the dispatcher picking up triage tickets. One more (dropping the early return for a limit of zero)
  survived because the trim loop gives the same result, so it is not a behaviour.
- Not run: CI on JDK 21.
