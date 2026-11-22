# Concurrent-update conflicts (stress audit H3) - summary

Date: 2026-10-31 - decisions in ADR-017 (with its amendment) and ADR-018. Done in phases with a human checkpoint after
each; the plan, the evidence and every correction made along the way are recorded here.

## Why
The stress audit reported that two writes to the same record at the same moment end as HTTP 500 "An unexpected server
error occurred". Measured with six race scenarios (every request released together behind a barrier, 572 requests,
every exception recorded with Java Flight Recorder), 423 requests (74 %) returned a 500:

- **Conflicts thrown at commit** (252 of 419 optimistic-lock failures): the service method has already returned, so no
  adapter can catch them; they reached the catch-all handler.
- **Conflicts thrown inside a read query** (167): the next query auto-flushes the pending update; the adapter wrapped the
  failure as `PersistenceFailureException("Failed to retrieve incidents for reporter...")`, also a 500.
- The only existing mapping to 409 (`IncidentRepositoryAdapter.save`) fired only when `save` ran outside a service
  transaction, which never happens in production.
- **Notifications did not roll back:** 233 "Incident Acknowledged" SMS were sent for 60 committed acknowledgements.
- In the real UI the 500 needs two writes within about 50 ms (a browser double-click at 0 ms gave a 500; 50 ms or more
  gave a clean 409), so it is rare for people and common for bursts, above all several employees reporting the same
  outage at once (7 of 8 reporters got no ticket).

## What changed
| Area | Change |
|---|---|
| Error mapping (ADR-017) | New `ConcurrentUpdateException` (`ERR_CONCURRENT_MODIFICATION`, HTTP 409, a fixed user-safe message). `GlobalExceptionHandler` maps Spring's `ConcurrencyFailureException` (commit path) and searches the cause chain of `PersistenceFailureException` (query path). The dead `java.util.ConcurrentModificationException` handler is gone. |
| Notifications (ADR-017) | `DomainEventListener.deferUntilCommit()` (default `false`); `NotificationObserver` opts in. New `TransactionAwareDomainEventPublisher`: audit-style listeners run immediately in the transaction; deferred listeners go from `afterCommit` to **one dedicated worker thread** (bounded queue, in publishing order, failures logged and swallowed, drained on shutdown, nothing delivered for a rolled-back transaction). |
| Intake (ADR-018) | New `IntakeGate`: one ticket intake at a time per application instance, first come first served; `IncidentService.createIncident` is `Propagation.SUPPORTS` so the gate encloses the pipeline's own transaction. A report that waits longer than `iitsm.intake.gate-timeout-ms` gets 503 `ERR_SERVICE_BUSY` (`ServiceBusyException`). |
| UI | `app.js`: the clicked button is tracked in the capture phase and disabled by `apiCall` for every write until 600 ms after the request settles; failed writes carry `status` and `code`; 409 shows a friendly message and the active list is refreshed (also after 404 and 5xx). Cache-bust `?v=3.5`. |
| Configuration | `iitsm.events.delivery-queue-capacity` (10000), `iitsm.intake.serialized` (true), `iitsm.intake.gate-timeout-ms` (30000). Environment overrides `EVENT_DELIVERY_QUEUE_CAPACITY`, `INTAKE_SERIALIZED`, `INTAKE_GATE_TIMEOUT_MS`. |

## Results
Same six barrier scenarios, same 572 requests, on the real build:

| Measure | Before | After |
|---|---|---|
| HTTP 500 | 423 | **0** |
| "Acknowledged" SMS vs acknowledgements committed | 237 vs 60 | **100 vs 100** |
| 8 identical reports (parent exists) | 12 / 96 created | **96 / 96** (all linked to the parent) |
| 24 identical reports | 28 / 144 | **144 / 144** |
| 8 simultaneous first reports | 12 / 96 | **96 / 96** |
| 30 simultaneous distinct intakes, default pool | 24 created, 6 x 500, 4.3 s | **30 / 30, slowest 1.2 s** |
| 40 simultaneous independent writes | (stalled at pool size, see below) | 40 x 200, slowest 308 ms |
| Browser: double-click at 0 / 50 / 200 ms | 500 / 409 / 409 | one request, no error |
| Browser: 8 employees submit the same report at once | not possible | 8 x "Ticket submitted successfully!" |
| Audit intake set (202 tickets, 4 workers) | 199 / 202 | **202 / 202** |

Intake latency is additive, since reports queue: 8 simultaneous reporters median 150 ms and slowest 294 ms; 24
simultaneous reporters median 832 ms and slowest 2.5 s.

## Corrections made along the way (kept on purpose)
1. **Phase 3 was redone.** The first version ran the after-commit listeners on the request thread, each in a
   `REQUIRES_NEW` transaction. Spring releases the request's connection only after the after-commit callbacks finish, so
   every committing request held one connection while asking for a second; with as many simultaneous commits as pool
   connections (default 10) the application stalled until the pool timeout, and a valid acknowledge returned 500 in a
   3-connection test. The phase 3 checks had missed it because they never had more than one successful commit at once.
   Fixed by the worker thread; guarded by `NotificationPoolStarvationRegressionTest`.
2. **My first phase 5 measurements were invalid** (a leftover app held the port, so every "mode" hit the same
   instance); they were discarded and the harness now refuses to run if the port is taken.
3. **`F7` was adapted** (see ADR-018): with intake serialised the second of two identical reports is linked to the first
   as a duplicate, so the two reports now differ in content; the capacity assertions are unchanged.

## Tests added (404 -> 442)
- `GlobalExceptionHandlerConflictTest` (7): commit-time, query-time and deeply wrapped conflicts, `ConcurrentUpdateException`,
  lock timeout, no entity internals in the message, 503 busy, other errors stay 500.
- `ConcurrentConflictRegressionTest` (4, real HTTP, barrier threads, 5 repeats): parallel acknowledges, verify vs reject,
  parallel reassigns, an 8-department report burst (all 201).
- `TransactionAwareDomainEventPublisherTest` (11): waits for commit, drops on rollback, runs on the worker thread,
  transaction opened on the worker only, bounded queue, shutdown drains, isolation of failing listeners.
- `NotificationAfterCommitRegressionTest` (2, real SMTP via GreenMail): one email per committed change, one for ten
  simultaneous acknowledges. `NotificationPoolStarvationRegressionTest` (1): pool of 3, 8 simultaneous writes.
- `IntakeGateTest` (7) and `IntakeConnectionStarvationRegressionTest` (1): pool of 3, 12 simultaneous distinct reports.
- `WriteGuardMarkupTest` (5): pins the structure of the in-flight guard and conflict handling in `app.js`.
- Mutation checks (each fails the named tests, then restored): old handler (all 4 race tests), observer immediate
  (`lostRacesSendNoNotification`), ignore the commit (3 tests), old synchronous `REQUIRES_NEW` delivery
  (`NotificationPoolStarvationRegressionTest`), gate bypassed (burst and starvation tests).
- Updated: `A4` and `IncidentRepositoryAdapterTest` expect `ConcurrentUpdateException`; the two cache-bust assertions
  expect `?v=3.5`; `F7` as above.

## Known limits and open items
1. A notification can arrive a moment after the response, is lost if the server dies with events still queued (no
   outbox), and is dropped with a warning and a counter if the 10000-event queue is full.
2. The intake gate protects one application instance; with several the optimistic lock stays the safety net (409). A
   database-level lock would be the follow-up when scaling out.
3. Each intake vectorises every open candidate, so intake cost grows with open tickets; about 300 simultaneous reports
   would reach the 30 s limit and start receiving 503. A vector cache would raise the ceiling.
4. The nested `REQUIRES_NEW` pattern (a connection requested while one is held) remains in `LoginAttemptService` and
   `TicketSequenceRowCreator` (first ticket of a department and year); outside ticket-intake bursts.
5. The UI guard covers every write made through `apiCall`; login and registration call `fetch` directly. Only
   Acknowledge, Start Investigation and Submit were driven in a browser; the other buttons share the same code path.
6. A refresh after a failed write happens for 409, 404 and 5xx, not for 400 or 403 (a validation error in a modal
   should not redraw the list behind it).
7. `InMemoryDomainEventPublisher.getPublishedHistory()` still records events of rolled-back transactions and grows
   without bound (pre-existing).
8. Not part of this work and still open from the audit: SLA tracker rules (H4-H7), duplicates skipping the triage gate
   (H2), the one-transaction SLA scan (H10, the phantom-notification half is closed here), the team queue bypassing the
   access policy (M2), bad input returning 500 (M5, M6), reclassify not closing wrong-domain requests (M10).
