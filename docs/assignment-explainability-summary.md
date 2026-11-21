# Explainable Technician Assignment and Strategy Comparison (spec feature D)

Plan: phases L1 to L4, each stopped for approval (L4, the service-experience factor, was optional and approved after L3).
Decisions: ADR-044. Diagram: `docs/uml/assignment-explainability.puml`.

| Phase | Content | Status |
|---|---|---|
| L1 | Explainable ranking in the domain: value objects, `rank` on all five strategies, factor extraction with characterization tests | Done 2026-11-08 |
| L2 | Record a decision on every assignment path, migration, admin read side, "Why this technician" dialog | Done 2026-11-08 |
| L3 | Offline strategy comparison: in-memory simulator, endpoint, Simulation tab panel, benchmark file | Done 2026-11-08 |
| L4 | `ServiceExperienceFactor`, weight chosen by the offline simulation | Done 2026-11-08 (approved after L3) |

## Phase L4: the service-experience factor (2026-11-08)

### What was built
- A **fourth assignment factor**, `ServiceExperienceFactor` ("Service experience"): how many tickets of the ticket's IT
  service the technician has already resolved (5 or more gives the full score). The weighted composite, the system's
  default strategy, now includes it at a weight of **0.10**; the three historic weights are scaled by 0.9, so every
  priority's weights still sum to 1.0. It shows in the administrator's "Why this technician" table as one more column.
- It reads its numbers through a **domain port** (`ServiceExperienceSource`), never a repository: the composite asks
  once per assignment for all candidates; the adapter answers with one read-only query over the resolved tickets (a
  ticket the employee sent back does not count). A ticket without a specific service (the "general" service) makes the
  factor score everyone 0.
- **Configurable and switchable**: `iitsm.assignment.service-experience-weight` (env
  `ASSIGNMENT_SERVICE_EXPERIENCE_WEIGHT`, default 0.10, allowed 0 to 0.5). **0 is exactly the historic behaviour** (a
  recorded 456-line golden file still matches bit for bit).
- The **weight was chosen with the offline simulation** (Phase L3's engine, extended with IT services and an optional
  "familiarity" assumption). The replay re-binds a service-experience strategy to its own in-memory counts, so a
  simulation never reads the real database.
- No schema or API change; asset version **4.2** (one label in the dialog).

### Where the patterns are, and why
| Pattern / OOP | Class | Why |
|---|---|---|
| Strategy (a new factor) | `ServiceExperienceFactor` | A new class registered with its weight; no existing factor or strategy was edited (Open/Closed). |
| Port and adapter | `ServiceExperienceSource` -> `ServiceExperienceAdapter` -> `SpringDataServiceExperienceRepository` | Strategies depend on an interface; the offline simulation plugs in its own in-memory source. |
| Value objects | `ServiceExperience`, `FactorWeights.withFactor` | One immutable answer per ranking; a weight table that must sum to 1 for every priority. |
| Optional capability | `ServiceExperienceBindable` | Lets a simulation replace the source of a strategy without knowing which strategy it is. |

### How the weight was chosen
Weights 0 to 0.5 were replayed on the same seeded streams (1,000 tickets x 10 replications, 2 IT services per
category) under three assumptions about familiarity (no speed-up, 15 %, 30 % faster) and three loads, on an invented
pool and on a **control pool** where every technician has all their team's skills (like the seeded roster).

**Two things the first sweep taught, and one honest admission.** On the invented pool the factor helped even with no
familiarity speed-up (mean time -3.8 min, unskilled assignments 10 % -> 6 %): there, "resolved this service before" also
reveals who has the right skill, so that benefit is a proxy effect of the pool, not familiarity. On the control pool, where
that proxy cannot exist, the factor is speed-neutral when familiarity is not real but makes the work less even. The
selection rule therefore changed twice after I saw numbers; the final rule and its history are in ADR-044.

**Final rule** (control pool, 15 tickets per hour): the largest weight that (a) speeds up the mean time to resolve
distinguishably when familiarity is real (30 %), (b) changes it by less than 1 % when familiarity is not real, and (c)
at 30 % raises the Gini of tickets handled by at most 0.05 and the Gini of busy time by at most 0.10.

| Weight | (a) gain at 30 % | (b) change with no familiarity | (c) Gini tickets / busy time | Result |
|---|---|---|---|---|
| 0.05 | -2.2 min | -0.30 % | +0.021 / +0.040 | passes |
| **0.10** | **-4.3 min (3.3 %)** | **-0.45 %** | **+0.036 / +0.073** | **passes, largest passing: chosen** |
| 0.15 | -5.9 min | -0.49 % | +0.046 / +0.107 | fails (c) |
| 0.20 | -6.6 min | -0.36 % | +0.052 / +0.126 | fails (c) |
| 0.30 | -7.5 min | -0.13 % | +0.070 / +0.174 | fails (c) |
| 0.40 | -7.5 min | +0.12 % | +0.080 / +0.194 | fails (c) |
| 0.50 | -7.2 min | +0.31 % | +0.076 / +0.197 | fails (c) |

Control pool, mean time to resolve with weight 0.10 against weight 0 (minutes): no familiarity 171.7 -> 170.9 (neutral),
15 % 150.0 -> 147.5 (-1.7 %), 30 % 128.6 -> 124.3 (-3.3 %). Full tables: `target/benchmarks/service-experience-weights.txt`.

**What this means, plainly.** The speed gain exists only if familiarity really makes technicians faster, which is an
assumption of the model, not a measurement. The cost, work concentrating on technicians with history (the Gini of tickets
handled rises by 0.03 to 0.04 and of busy time by 0.05 to 0.07 at the default weight), is certain. The default is a small,
conservative weight; it is one environment variable to change or turn off, and the real effect should be measured in
production before relying on it. In the report you can say: *the weight was chosen by offline simulation under a stated
assumption, with a fairness limit, and the factor is configurable and can be disabled*.

### Tests added in L4
| Class | Tests | What it pins |
|---|---|---|
| `ServiceExperienceTest` | 8 | Applicability (not for no service, blank or the general one), copied counts, the port, the factor's score and wording, the weight table sums to 1 and keeps proportions |
| `WeightedCompositeServiceExperienceTest` | 13 | Weight 0 is the historic strategy and never asks; one lookup per ranking; none without a specific service; experience breaks a tie and shows in the breakdown; no or equal history changes no ranking; rank = recommend; `bindExperience`, `named`, bounds, the registry constructor |
| `ServiceExperienceCharacterizationTest` | 3 | The pre-L4 golden file still matches at weight 0; the 88 recorded observations at weight 0.10; history changes some choices and none for tickets without a service |
| `ServiceFamiliaritySimulationTest` | 12 | What the replay shows a strategy, counting at completion, no experience without familiarity, the real source never read, the speed-up, services leave the base stream unchanged, a deterministic experiment with services |
| `IntelligenceConfigServiceExperienceTest`, `ServiceExperienceConfigContractTest` | 4 + 1 | How the weight is wired, bad weights stop the start-up, yml and code defaults agree with the chosen weight |
| `ServiceExperienceAdapterTest` | 4 | The real query: per technician, per service, only fixes that stand |
| `ServiceExperienceAssignmentRegressionTest` | 3 | End to end through the real intake and the administrator's detail: the factor, its weight, per-service history, weights sum to 1 |
| `ServiceExperienceWeightBenchmarkTest` | 1 | Report-only: the sweep and the selection |

Mutation checks (each made tests fail, then undone): weights not scaled, lookup for a ticket without a service, a
sent-back ticket counted, the replay not re-binding (it would read the real source), the replay never counting, a zero
weight that is not the historic strategy, the general service treated as specific, a production default of 0.

### Verification
Full `mvn -o clean verify`: 1598 tests, 0 failures, 0 skipped, JaCoCo gate met (91.7 % lines). Every class in its own JVM: 229 classes, 1598 tests, 0 failures, 0 skipped. Live checks against the running app: `service_experience_check.py` (API and Edge) passed, `assignment_explanation_check.py` (script updated for the fourth factor), `assignment_strategies_check.py`, `routing_check.py`, `strategy_comparison_check.py` and `action_buttons_browser.py` passed; no ERROR in the app log. Not run: CI on JDK 21, browsers other than Edge.

### Known gaps after L4
- The benefit depends on an unmeasured familiarity assumption; the fairness cost is certain (see above).
- History counts every resolved ticket since the database began, with no decay and no weighting by difficulty;
  resolutions stored with an e-mail as resolver (before ADR-037) count for nobody.
- One grouped query per assignment, with no index on `resolutions.resolved_by` (fine at this size).
- The comparison endpoint's body is unchanged: the service model (services and familiarity) is available to the
  benchmark and the tests, not to the administrator's panel.
- With the default weight, composite totals are smaller for a ticket without a specific service (every candidate
  gets 0 of the 0.10); the order of candidates is not affected.

## Phase L3: offline strategy comparison (2026-11-08)

### What was built
- **`POST /api/simulation/strategy-comparison`** (administrators only, read-only, idempotent). It replays one seeded,
  synthetic ticket stream through **every registered assignment strategy**, in memory, and compares them on eight
  metrics, over several independent replications so the differences mean something. Every field of the body is
  optional: `{seed, ticketCount, replications, arrivalsPerHour, strategies}` (defaults seed 42, 500 tickets, 10
  replications, 15 tickets per hour, all strategies).
- **Nothing outside the request is touched**: no row is written, no event published, no notification sent, the live
  clock is not read or moved, the active strategy is not changed and the live round-robin rotation does not advance
  (each strategy is replayed through `freshInstance()`). Tests prove each of these over the real database, clock,
  publisher and registry.
- **Metrics** (all "lower is better"): mean time to resolve, 95th percentile time to resolve, SLA resolution breach
  rate, mean wait before assignment, maximum queue length, share of tickets given to a technician without the
  category skill, and the **Gini coefficient** of the tickets handled and of the busy time (fairness).
- **Statistics**: per strategy and metric the mean over the replications, the standard deviation, the 95 % interval,
  every replication's value and, against the reference strategy (WEIGHTED_COMPOSITE), the **paired difference** with
  its interval and a flag "larger than chance". The winner of each metric is named.
- **The pool** is copied by value from the current roster (24 technicians in the seeded company; an invented pool of 12
  when there is no roster); candidates follow the live routing rule of ADR-036.
- **Simulation tab panel** "Assignment Strategy Comparison": seed, tickets, replications, a run button, the results
  table with the best value of every column highlighted and the real differences marked, the assumptions, and a
  notice that these are simulated results, not production data. No charts, no CDN; asset version **4.1**.

### Where the patterns are, and why
| Pattern / OOP | Class | Why |
|---|---|---|
| Discrete-event simulation | `StrategyReplay` | One `PriorityQueue` of events and one waiting `PriorityQueue` per category (urgent first), as the brief asks for collections. |
| Template of an experiment | `StrategyComparison` | One stream per replication replayed through every strategy (common random numbers), then the statistics. |
| Factory | `TicketStreamGenerator` | Seeded ticket stream and the in-memory incidents (`SIM-000001`, no sequence, no repository). |
| Value objects (records) | `ComparisonParameters`, `SimulationPool`, `TechnicianProfile`, `SyntheticTicket`, `ReplayResult`, `ComparisonReport`, `StrategyReport` | Every bound and invariant lives in the domain; the profile copies the roster by value. |
| Utility computation | `GiniCoefficient`, `ReplicationStatistics`, `ReplayMetrics`, `WorkTimeModel` | Small, separately tested, no state. |
| Strategy (reused) | `AssignmentStrategy`, `AssignmentStrategyRegistry`, `CandidateFilter` | A strategy registered later is compared automatically (Open/Closed, tested); the real routing rule is reused. |
| Facade | `StrategyComparisonService` | Reads three inputs once in a short read-only transaction and runs the pure experiment outside it. |

### Model and assumptions (all returned with every result)
Service time = base minutes of the priority (P1 40, P2 75, P3 150, P4 240) x category factor (0.8 to 1.3) x skill
factor (1, or 1.6 without the category skill) x experience factor (1.25 at 0 years down to 0.80 at 12 years) x
concurrency factor x the ticket's own noise (log-normal, mean 1). A technician works on **several tickets at once up
to the capacity** (your decision of 2026-11-08); a ticket is slower by 30 % x (tickets already in service /
capacity) when it starts, so load costs time (my addition: without it the load-based strategy would have no effect
on speed). Tickets arrive as a Poisson process; priority mix 5 / 15 / 50 / 30 %. Full list and alternatives: ADR-044.

### Results: seeded roster, seed 42, 500 tickets x 10 replications, 15 tickets per hour
Mean over the replications, 95 % half width after the sign; **bold** = best of the row. Source:
`target/benchmarks/strategy-comparison.txt` (written by `StrategyComparisonBenchmarkTest`).

| Metric | EXPERIENCE_BASED | LOAD_BASED | ROUND_ROBIN | SKILL_BASED | WEIGHTED_COMPOSITE (default) |
|---|---|---|---|---|---|
| Mean time to resolve (min) | **191.7** +-4.1 | 206.3 +-4.1 | 221.7 +-3.6 | 203.1 +-3.3 | 193.6 +-3.5 |
| 95th percentile time to resolve (min) | **404.6** +-18.0 | 473.6 +-21.2 | 517.5 +-19.2 | 438.2 +-18.2 | 413.4 +-10.7 |
| SLA resolution breach rate | 6.8 % +-0.7 | 8.0 % +-0.6 | 9.1 % +-0.9 | 7.6 % +-0.9 | **6.4 %** +-0.8 |
| Mean wait before assignment (min) | **0.00** | 0.01 +-0.01 | 0.01 +-0.01 | **0.00** | **0.00** |
| Maximum queue length (tickets) | **0.0** | 0.3 +-0.3 | 0.4 +-0.5 | **0.0** | **0.0** |
| Tickets to a technician without the skill | 9.3 % +-1.0 | 19.3 % +-1.2 | 22.4 % +-1.2 | 11.4 % +-0.6 | **8.9 %** +-0.7 |
| Gini of tickets handled (0 = even) | 0.674 +-0.013 | 0.286 +-0.016 | **0.253** +-0.018 | 0.479 +-0.010 | 0.461 +-0.011 |
| Gini of busy time | 0.650 +-0.012 | **0.249** +-0.024 | 0.283 +-0.019 | 0.467 +-0.010 | 0.380 +-0.013 |

Against the default, every difference above is larger than chance except: EXPERIENCE_BASED on the 95th percentile
(-8.7 min), its unskilled share (+0.4 points), and every difference in wait and queue length (the system is not busy
enough at 15 per hour to queue). Strategies tied at 0.00 wait are all marked best.

**Sensitivity** (invented 12-technician pool, mean minutes / breach rate, penalty = the concurrency assumption): at
every one of nine settings (penalty 0, 0.30, 0.60 x 8, 15, 25 tickets per hour) SKILL_BASED and WEIGHTED_COMPOSITE
stay within 4 % of each other and ahead of the three simpler strategies; for example penalty 0.30 at 15 per hour:
SKILL_BASED 190 / 6.5 %, WEIGHTED_COMPOSITE 194 / 6.6 %, EXPERIENCE_BASED 261 / 12.1 %, LOAD_BASED 262 / 12.5 %,
ROUND_ROBIN 271 / 13.7 %. At a penalty of 0.60 LOAD_BASED overtakes EXPERIENCE_BASED at low load.

### Why strategy X? (a paragraph for the report)
> We compared the five assignment strategies offline. The same seeded stream of 500 synthetic tickets, 15 per hour,
> was replayed through each strategy, ten times with independent seeds, on the 24-technician roster and under the
> production routing rule, so every difference comes from the strategy and not from luck. The default,
> WEIGHTED_COMPOSITE (skill 50 %, load 30 %, experience 20 %, re-weighted by priority), resolved tickets in 193.6
> minutes on average, within about 1 % of the fastest strategy (EXPERIENCE_BASED, 191.7 minutes), and it had the
> lowest SLA breach rate (6.4 %) and the fewest tickets given to a technician without the category skill (8.9 %).
> EXPERIENCE_BASED buys its speed with unfairness: its Gini coefficient of tickets handled is 0.67 against 0.46 for
> the default, because it sends most of the work to its few most experienced technicians. ROUND_ROBIN and LOAD_BASED
> spread the work most evenly (Gini 0.25 to 0.29) but are 7 to 15 % slower, breach the SLA more often (8.0 to 9.1 %)
> and give 19 to 22 % of the tickets to technicians without the skill, because they ignore it. SKILL_BASED is close to
> the default on speed (203 minutes) but ignores load, so it is less fair and slower. The default is therefore the
> best compromise between speed, SLA compliance, skill fit and fairness. These results come from a synthetic
> work-time model (the skill, experience and concurrency factors are assumptions, not measurements), and the two best
> strategies swap places on speed by 1 to 3 % with the roster, so the claim is that the default is never clearly
> beaten, not that it is the fastest in production.

### Tests added in L3
| Class | Tests | What it pins |
|---|---|---|
| `StrategyReplayTest` | 9 | Urgent-first waiting queue, concurrency up to capacity, completion before arrival, unskilled flag, unservable ticket and rogue strategy refused, determinism, invariants for every strategy (everyone served, no technician over capacity at any virtual time by a sweep, totals add up) |
| `StrategyComparisonTest` | 11 | Report shape and ranges, reference, same seed equal report and byte-identical text, another seed differs, replication r independent of the number of replications, common random numbers (a spy sees the same tickets in every strategy), a stateful strategy not advanced, winners consistent, one replication, a custom strategy compared |
| `TicketStreamGeneratorTest` | 10 | Determinism, order, arrival rate, priority and category mix, servable categories only, noise, prepared incidents |
| `WorkTimeModelTest`, `GiniCoefficientTest`, `ReplicationStatisticsTest`, `ReplayMetricsTest` | 7 + 6 + 6 + 4 | Every assumption, Gini (all equal 0, one has all (n-1)/n, empty/zero defined), t values and paired difference, each metric on a worked example |
| `SimulationPoolTest`, `ComparisonParametersTest`, `SimulationSourceContractTest` | 7 + 6 + 3 | Pool rules and profiles, every bound with its field name and the product cap, no wall clock / unseeded random / outside layers in the package |
| `StrategyComparisonServiceTest` | 8 | One read-only transaction, empty roster falls back, validation before any read, registry and live rotation untouched, a later-registered strategy compared, repository SLA targets used, roster order irrelevant, no way to write |
| `StrategyComparisonEndpointTest` | 21 | Shape, defaults, no body, echo, idempotent, every 400 with its code, malformed bodies, 401 / 403 / 405 |
| `StrategyComparisonNoSideEffectsRegressionTest` | 4 | Database counts and versions, no SIM ticket anywhere, clock, events, active strategy, live rotation |
| `StrategyComparisonMarkupTest` | 7 | Panel markup, submit guard, friendly errors, notice and assumptions, best/significance marks, escaping, sign-out clear, CSP, asset version |
| `StrategyComparisonBenchmarkTest` | 1 | Report-only: the table above, the sensitivity, the run times |

Mutation checks (each broke a test or a browser check, then undone): supplied strategy instance replayed (the live
rotation would advance); service writes a row; service changes the active strategy; waiting queue last-in first-out
and first-come first-served; an arrival before a completion at the same instant; Gini formula; unseeded stream;
a different stream per strategy; every user allowed on the endpoint; roster not sorted.

### Run time (measured)
Defaults (500 x 10, five strategies): 431 ms for the first request, 253 to 281 ms after it; the running application
answered in 0.74 s. The largest allowed request (25,000 ticket replays) took 1.2 s. A first measurement on a busier
machine had given 1.6 to 4.4 s and, for the 50,000 replays of my first proposal, 16 s; the cap of 25,000 tickets x
replications came from that figure and is conservative (`ComparisonParameters.MAX_TICKETS_TIMES_REPLICATIONS`).

### Verification
`mvn -o clean verify`: 1549 tests (1439 before L3, 110 new), 0 failures, 0 skipped, JaCoCo gate met (90.4 % lines).
Every class in its own JVM: 220 classes, 1549 tests, 0 failures, 0 skipped. Live checks against the running app: `strategy_comparison_check.py` 34 of 34 (API and Edge) on five runs, straight after
the buttons check too; `assignment_explanation_check.py`, `assignment_strategies_check.py`, `routing_check.py` and
`action_buttons_browser.py` (33 checks) still pass; no ERROR in the app log apart from one harmless aborted notification
stream when a test browser closed. Two things found by the checks, both in scripts: the new check's "no effects" test
failed once because the dispatcher assigned waiting tickets in the background (it now probes around one request and
retries; a real side effect would show every time), and `action_buttons_browser.py` fails with
`ERR_TECHNICIAN_OVERLOADED` after several runs on one database because it leaves its tickets open (it passes on a fresh
database; this is older than L3). Not run: CI on JDK 21, browsers other than Edge.

### Known gaps after L3
- Every number comes from a synthetic model; only the comparison between strategies is meaningful, and it can change
  with the assumptions (see the sensitivity).
- The seeded technicians have skills only for their own team's categories, so skilled overflow never happens in the
  roster pool, and an unskilled assignment can only occur inside a team that handles several categories.
- The pool starts empty (no warm-up), the mixes and the text templates are fixed, availability status, working hours and
  the SLA response target are not modelled.
- The development database's roster differs slightly from the test roster: on it the default was also the fastest
  (185.5 against 190.2 minutes for EXPERIENCE_BASED).
- The request holds a servlet thread while it runs (a second or two at the limit).

## Phase L2: why this technician, recorded and shown (2026-11-08)

### What was built
- Every way a ticket gets its technician now stores **one assignment decision**, in the same transaction as the
  assignment (if the assignment rolls back, the decision does too):

  | Path | Decision kind | Actor | Ranking stored |
  |---|---|---|---|
  | Intake (second step) | `OWNING_TEAM` or `SKILLED_OVERFLOW` | `SYSTEM` | yes |
  | Background dispatcher | `OWNING_TEAM` or `SKILLED_OVERFLOW` | `DISPATCHER` | yes |
  | Admin accept-suggestion | `OWNING_TEAM` or `SKILLED_OVERFLOW` | the administrator | yes |
  | Admin reclassify into another team | `OWNING_TEAM` or `SKILLED_OVERFLOW` | the administrator | yes |
  | Admin manual assign | `MANUAL` | the administrator | no |
  | Confident duplicate riding its parent | `INHERITED_FROM_PARENT` (names the parent) | `SYSTEM` | no |
  | Admin rejects a wrong-domain request | `REQUEUED_TO_SAME_TECHNICIAN` | the administrator | no |

- The stored ranking is the one the strategy chose from (never recomputed), cut to the best 10 candidates
  (`iitsm.assignment.explanation-max-candidates`, env `ASSIGNMENT_EXPLANATION_MAX_CANDIDATES`); the count before the
  cut is kept.
- The administrator's ticket dialog has a new **"Why This Technician"** section: per decision the pool, the strategy,
  when and by whom, one plain sentence, and for a strategy's decision a table of the ranked candidates with every
  factor as "raw x weight = contribution", the chosen row highlighted. Older tickets say "No assignment record for this
  ticket".
- **Administrators only.** Technicians and employees get HTTP 403 on the admin detail and none of their own responses
  has a field that could carry the ranking (it would reveal colleagues' load and experience).
- New table `assignment_decisions` (Flyway **V11**); the ranking is one JSON text column, written and read only in
  infrastructure. No new endpoint, no new event; asset version **4.0**.

### Where the patterns are, and why
| Pattern / OOP | Class | Why |
|---|---|---|
| Value object (record, invariants, factories) | `AssignmentDecision`, `RoutedCandidates`, `PoolKind` | A strategy decision must start its ranking with the chosen technician; the other kinds cannot carry a ranking; the cut always keeps the choice. |
| Repository port + Adapter | `AssignmentDecisionRepository` -> `AssignmentDecisionRepositoryAdapter` -> `SpringDataAssignmentDecisionRepository` | The domain stays free of JPA and Jackson; the adapter inserts only (`Persistable`). |
| Mapper | `CandidateScoresJson` (infrastructure), `AssignmentDecisionViewMapper` (web) | JSON lives in infrastructure only; the admin view's names and sentences live in the web layer. |
| Transaction script, MANDATORY | `AssignmentDecisionRecorder` | Called from every path inside the assignment's transaction; refuses to run without one. |
| Facade (extended) | `TicketRouter.route`, `CandidateFilter.route` | The routing rule also says which pool the candidates came from. |

### Storage: why one JSON column
The ranking is only ever read whole, for one ticket, in one dialog. One TEXT column keeps it to one row per assignment
and needs no schema change when a factor is added (Phase L4). The alternative, three normalised tables (decision,
candidate, factor), would allow SQL queries per factor but costs up to 40 extra rows per assignment; it is recorded in
ADR-044 as the way to go if reporting ever needs it.

### Tests added in L2
| Class | Tests | What it pins |
|---|---|---|
| `AssignmentExplanationContractTest` | 12 | One expectation through all seven paths; the duplicate names its parent; skilled overflow; rollback leaves no decision and no slot; the recorder refuses to run without a transaction; a waiting ticket records nothing |
| `AssignmentExplanationAccessRegressionTest` | 4 | Admin JSON (pool, strategy, names, factors); technician 403 and nothing in their views; employee 403 and nothing; the response types have no such field |
| `AssignmentExplanationCapRegressionTest` | 1 | With the limit set to 2: two stored, the count says how many were ranked |
| `AssignmentDecisionTest` | 6 | Factories, the cut, older strategies, invariants, the filter's pool kinds |
| `CandidateScoresJsonTest` | 3 | Exact round trip, document shape, unknown format refused |
| `AssignmentDecisionRepositoryAdapterTest` | 3 | Round trip, newest first with a limit, cascade on delete |
| `AssignmentDecisionsMigrationH2Test`, `...PostgresTest` | 4 + 4 | V11 after V10 with V7/V8 free, pool and count checks, references, cascade on rename and delete |
| `AssignmentDecisionViewMapperTest` | 4 | Names, factors, the penalty's sign, one sentence per kind |
| `AssignmentExplanationMarkupTest` | 5 | Section position, empty state, escaping of every value, highlight/penalty/tie-break/cut, no inline handlers, asset version |
| `TechnicianAssignmentServiceTest` | +2 | The stored ranking is the one chosen from; no slot, no decision |

Mutation checks (each broke a test or a browser check, then undone): recorder in its own transaction; the reject path
records nothing; overflow reported as the owning team; technicians allowed on the admin detail; the ranking recomputed
after the choice; the chosen row not highlighted; the section never drawn; no empty state.

### Verification
`mvn -o clean verify`: 1439 tests (1391 before L2, 48 new), 0 failures, 0 skipped, JaCoCo gate met (89.7 % lines).
Every class in its own JVM: 205 classes, 1439 tests, 0 failures, 0 skipped. Live check
`scripts/live-checks/assignment_explanation_check.py`: 22 of 22 (API and Edge) on three consecutive runs against one
database; `assignment_strategies_check.py`, `routing_check.py` and `action_buttons_browser.py` still pass; no ERROR in
the app log. Not run: CI on JDK 21, browsers other than Edge.

### Known gaps after L2
- Tickets assigned before V11 have no decision; only the newest 5 decisions are shown; the ranking cannot be queried
  by factor in SQL (JSON column).
- Names are read when the dialog opens (a renamed technician shows the new name in old decisions).
- Capacity repair changes slots, not assignees, and records no decision.

## Phase L1: explainable ranking in the domain (2026-11-08)

### What was built
- Every assignment strategy can now say how **every** candidate scored, not only who won:
  `AssignmentStrategy.rank(incident, candidates)` returns one `CandidateScore` per candidate, best first. Each one
  lists its `FactorScore`s (raw score, weight, weighted contribution, a short detail such as "4/5 tickets active"),
  the total, the rank and whether it was chosen.
- `recommend` still returns one `AssignmentRecommendation`, now with a fifth field `ranking`: the same ranking the
  choice was made from. Phase L2 stores that ranking; it never recomputes it.
- Asking for an explanation changes nothing: round robin's `rank` shows whose turn it is without moving the rotation.
- `WeightedCompositeStrategy` is built from `AssignmentFactor` objects (`SkillMatchFactor`, `LoadFactor`,
  `ExperienceFactor`) and a `FactorWeights` table per priority. A new factor is a new class registered with its
  weights; no existing class changes.
- No schema, migration, API, event or UI change. Every strategy makes exactly the choices it made before (see
  "How we know nothing changed").

### Where the patterns are, and why
| Pattern / OOP | Class | Why |
|---|---|---|
| Strategy | `AssignmentStrategy` + the five strategies | Unchanged pattern; the contract gains `rank` and `freshInstance` with default methods, so older strategies still compile and still explain themselves (`AssignmentExplanationValuesTest.legacyStrategyGetsAnOpaqueRanking`). |
| Strategy (factors) | `AssignmentFactor`, `SkillMatchFactor`, `LoadFactor`, `ExperienceFactor` | Same shape as `PriorityFactor`; the composite loops over a list, so a fifth factor needs no edit (`AssignmentFactorExtensibilityTest`). |
| Value objects (records, invariants) | `FactorScore`, `CandidateScore`, `AssignmentContext`, `SkillEvidence`, `FactorWeights`, `AssignmentRecommendation` | Contribution is computed, never passed in; only rank 1 may be chosen; weights of a priority must sum to 1; lists are copied. |
| Single place for "rank then choose" | `RankedCandidates` | Every built-in strategy turns its scores into a ranking here and takes the top entry as its choice, so the choice and the explanation are one computation (stable sort: ties keep input order). |
| Prototype-like copy | `AssignmentStrategy.freshInstance()` | Stateless strategies return themselves; round robin returns a new rotation. The L3 replay uses this so it never disturbs the live rotation. |

### How the five strategies explain themselves
| Strategy | Ranked by | Factors shown (weight) | Total |
|---|---|---|---|
| WEIGHTED_COMPOSITE | total, high first | SKILL_MATCH, LOAD, EXPERIENCE (weights by priority: P1/P2 0.45/0.20/0.35, P3 0.50/0.30/0.20, P4 0.30/0.50/0.20) | sum of contributions |
| SKILL_BASED | total, high first | SKILL_MATCH (1.0), LOAD_PENALTY (-0.10) | max(0, skill - 0.10 x load) |
| LOAD_BASED | load ratio, low first; then more experience | LOAD (1.0), EXPERIENCE (0, tie-break) | 1 - load ratio |
| EXPERIENCE_BASED | experience, high first; then lower load | EXPERIENCE (1.0, full at 15 years), LOAD (0, tie-break) | min(1, years / 15) |
| ROUND_ROBIN | rotation order from the current slot | ROTATION (1.0, "queue slot #k of n, turn t") | 0.80 for everyone |

In every strategy, candidates the order cannot tell apart keep their input order, so the first one wins, as before.

### How we know nothing changed
1. **Characterization first.** Before touching the strategies, `AssignmentStrategyCharacterizationTest` recorded 456
   observations from the old code into `src/test/resources/assignment/strategy-characterization.txt`: four
   strategies x 11 tickets (every priority for two categories, a token-only skill match, two tickets without priority
   or category) x 8 candidate lists (all, reversed, one, an exact tie both ways, an experience tie both ways, a mixed
   list) plus the empty and null lists, and a 15-call round-robin sequence. Each line has the chosen technician, the
   exact score (all digits), the strategy name and the rationale text. After the refactor every line matches.
2. **Existing tests unchanged**: `AssignmentStrategyTest`, `TechnicianAssignmentServiceTest`, `CandidateFilterTest`
   and the regression tests pass without edits.
3. **Mutation checks** (each broke a test, then undone): ties given to the last candidate; the composite's `rank`
   ranking a reversed list (so it disagrees with `recommend`); round robin's `rank` advancing the counter; changed
   P1/P2 weights; the skill-based score no longer clamped at 0.

To re-record the golden file on purpose (only when an assignment change is intended and approved), run the test once
with the environment variable `CHARACTERIZATION_RECORD=true` and review the diff.

### Tests added in L1
| Class | Tests | What it pins |
|---|---|---|
| `AssignmentStrategyCharacterizationTest` | 2 | The 456 recorded observations, and that the golden file covers the grid |
| `RankAgreesWithRecommendTest` | 20 | For each strategy: rank 1 = recommended technician over the whole grid, the recommendation carries the same ranking, ties in both orders, empty lists refused, contribution = raw x weight |
| `RoundRobinExplanationTest` | 5 | `rank` has no side effect, rotation order, the recommendation explains its own slot, `freshInstance` is independent, stateless strategies return themselves |
| `AssignmentFactorTest` | 6 | The three factors and `SkillEvidence` (category, full name, token matches) |
| `AssignmentExplanationValuesTest` | 6 | Value-object rules, the weight table, the default `rank` of an older strategy |
| `AssignmentFactorExtensibilityTest` | 2 | A new factor registered with weights changes the choice and appears in the breakdown; the composite total equals the sum of contributions |
| `AssignmentGrid` | (support) | The shared grid of technicians, tickets and candidate lists |

### Verification
- `mvn -o clean verify`: 1391 tests (1350 before L1, 41 new), 0 failures, 0 skipped, JaCoCo gate met (89.5 % lines).
- Every class in its own JVM (`-DforkCount=1 -DreuseForks=false`): 195 classes, 1391 tests, 0 failures, 0 skipped.
- Live check against the running app: `assignment_strategies_check.py` 14 of 14 on two consecutive runs (each of the
  five strategies assigned its ticket to the team of the ticket's category; round robin picked a different technician
  on the second run, as its rotation should), `routing_check.py` all passed, no ERROR in the app log.
- Not run: CI on JDK 21.

### Known gaps after L1
- The explanation is computed but neither stored nor shown (that is L2).
- Round robin's ranking is a rotation order, not a quality order; every candidate scores 0.80.
- The skill-based load penalty is a factor with a negative weight; the L2 dialog must show it as a penalty.
- Skill-name matching keeps its old quirks (any ticket token of two or more letters found inside a skill name counts);
  it is now pinned by the characterization instead of hidden.
