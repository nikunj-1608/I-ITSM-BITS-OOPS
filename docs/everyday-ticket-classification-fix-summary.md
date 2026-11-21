# Everyday tickets are not assigned automatically (ADR-052)

Reported 2026-11-21: "the tickets are not being assigned automatically once the employee is raising a ticket".
This file records each phase of the fix. All six phases are done (2026-11-21). **Result: 21 -> 31 of 35 clear tickets auto-routed to the right team (60.0 % -> 88.6 %), 2 -> 0 routed to a wrong team; the 90 % target was not reached.**

## The finding

The report is true for about a third of short, plain-language tickets, and false for the assignment code.

- **Live run (2026-11-21, dev profile, fresh seed)**: 26 tickets raised through `POST /api/employee/incidents`;
  25 accepted ("Help" was refused: titles need 5 to 120 characters); 17 assigned to a technician within milliseconds,
  **8 left unassigned** (32 %): Printer not working, Printer jammed, Internet is down, Server unreachable,
  Email not sending, Account locked, Need software installed, Teams calls dropping.
- **The assignment path works**: `IncidentService.createIncident` -> `TicketDispatchService.dispatchOne` -> `TicketRouter`
  -> `TechnicianAssignmentService`; capacity was free; `AutoAssignmentRegressionTest` and
  `IncidentServiceIntakeAssignmentTest` pass (10 tests).
- **Every unassigned ticket was below the classification gate** (`iitsm.classification.min-confidence`, 0.60, ADR-009).
  `IncidentIntakePipeline` then flags it `needsTriage` and leaves it in the administrator's triage queue, and
  `UnassignedIncidentDispatcher` skips triage tickets on purpose. The employee sees the ticket at "NEW" with no
  technician, and nobody is alerted.

### Why each ticket fell below the gate (classifier rationale)

| Ticket | Keyword model | Naive Bayes | Cause |
|---|---|---|---|
| Printer not working | no term | HARDWARE 0.67 | no lexicon word ("printer"); no term always caps at 0.55 |
| Printer jammed | no term | AUTHENTICATION 0.28 | no lexicon word; Naive Bayes wrong |
| Internet is down | no term | NETWORK 0.25 | no lexicon word ("internet") |
| Server unreachable | no term | NETWORK 0.65 | no lexicon word |
| Email not sending | no term | AUTHENTICATION 0.63 | no lexicon word ("email", "outbox"); Naive Bayes wrong |
| Account locked | no term | AUTHENTICATION 0.96 | "locked" is a generic word (ADR-034), "account" not listed |
| Need software installed | SOFTWARE 0.75 (2 terms) | HARDWARE 0.73 | fusion rule caps two keywords against an unsupported Naive Bayes answer |
| Teams calls dropping | SOFTWARE 0.55 (1 term) | DATABASE 0.79 | single keyword plus disagreement is capped; the triage label is the Naive Bayes guess |

### Files at the root of it

1. `src/main/java/com/iitsm/domain/strategy/classification/KeywordClassifier.java`: the lexicon (`initLexicon`) lacks
   everyday words; `GENERIC_WORDS` excludes "locked".
2. `src/main/java/com/iitsm/domain/strategy/classification/CompositeClassifier.java`: the cap rules for "no keyword",
   "models disagree with weak keyword support" and "Naive Bayes not corroborated", and the choice of the triage label.
3. `src/main/resources/sim/corpus.csv`: the Naive Bayes training data has almost no printer, e-mail, internet or lockout
   examples.
4. `ServiceMatcher` and `IncidentIntakePipeline`: the matched IT service (SVC-PRINT for "printer") is never used as
   evidence (CLAUDE.md section 7 item 4).

## Phase 1: reproduce and measure (done 2026-11-21, tests and a script only)

### What was built

- `src/test/resources/classification/everyday_tickets.tsv`: 42 labelled tickets, in the format of `audit_examples.tsv`.
  25 from the live run, E37 (the refused "Help" with a longer title), 10 clear control tickets so every category is
  covered, 1 mixed security control (`SECURITY|TRIAGE`) and 5 vague or non-IT controls (`TRIAGE`). 35 clear tickets,
  7 that must stay with a person. Labels were written from the text, not from the classifier's answers.
- `BenchmarkData.everydayTickets()`: loader; the audit-example parser is now shared (`labelledTsv`).
- `src/test/java/com/iitsm/benchmark/EverydayTicketBenchmarkTest.java` (tag `benchmark`, so not in the fast lane): classifies
  and routes every ticket with the production beans, gives each a verdict, writes `target/benchmarks/everyday-tickets.txt`
  (totals, then one line per ticket with the keyword and Naive Bayes answers) and asserts that no verdict count is worse
  than the baseline below. It shares the Spring context and `benchmarkdb` of `ClassificationBenchmarkTest`, so it adds no
  context start-up.
- `scripts/live-checks/everyday_tickets_check.py` (standard library only): raises the same tickets (it reads the same TSV)
  against the running application, reads each back as the administrator, prints the verdicts and totals, and checks that
  every unassigned ticket marked `needsTriage` is in `GET /api/admin/triage-queue`. `--target 0.90` turns on the Phase 6
  target.

### Verdicts

Success is measured by team, because the team decides who works on the ticket (ADR-036).

| Verdict | Meaning |
|---|---|
| `AUTO_RIGHT_TEAM` | clear ticket auto-routed to the team that handles its expected category (the goal) |
| `MISSED_TO_TRIAGE` | clear ticket left in triage, no technician (the reported problem) |
| `AUTO_WRONG_TEAM` | clear ticket auto-routed to another team (the worst outcome) |
| `KEPT_FOR_PERSON` | vague, non-IT or mixed ticket left for a person (correct) |
| `LEAKED` | ticket labelled `TRIAGE` auto-routed anyway |
| `WAITING` | live script only: confident ticket with no technician because its team is full |

### Baseline (2026-11-21)

| Measure | Value |
|---|---|
| clear tickets auto-routed to the right team | **21 / 35 (60.0 %)**, exact category 20 |
| clear tickets left in triage | 12 / 35 |
| clear tickets auto-routed to a wrong team | **2 / 35**: E11 "Wifi not working" to the Service Desk, E32 "AWS console access" to Security |
| vague, non-IT or mixed kept for a person | 7 / 7 |
| vague or non-IT auto-routed | 0 / 7 |

The benchmark and the live script agreed ticket for ticket. The baseline is lower than the live sample (68 %) because
the control tickets found four more misses: E08 "Webcam not detected" (no lexicon word; Naive Bayes says SECURITY 0.90),
E27 "Antivirus found a virus" ("virus" not listed), E29 "Report fails with database error" (single keyword, weak Naive
Bayes), E33 "Azure VM not starting" (single keyword, weak Naive Bayes).

Two more findings for the fix phases: E11 is a keyword tie (wifi = NETWORK, laptop = HARDWARE) that Naive Bayes breaks
toward HARDWARE; E38 "Kitchen tap leaking" gets a SECURITY keyword from "leak" (it stays in triage today, but a lexicon
change must not make it worse).

Existing figures, unchanged: held-out 33 / 36 met, 3 / 36 in triage, 31 / 33 auto-routed to the team of the true
category; audit examples 12 / 12 met, 7 / 7 routed correctly.

### Verification

- `mvn -o test -Dtest=EverydayTicketBenchmarkTest,ClassificationBenchmarkTest,ClassificationBudgetTest`: 4 tests, 0 failures.
- `everyday_tickets_check.py` against a freshly started application: 42 reports accepted, 19 of 19 unassigned triage tickets
  in the triage queue, totals equal to the benchmark, exit code 0.
- Full `mvn -o clean verify` on the Phase 1 code: 2004 tests, 0 failures, 0 skipped, coverage gate met (also the first
  full Maven run since the employee and technician portal rebuilds, ADR-050 and ADR-051).
- Not done: a mutation check of the ratchet (the floors were read from one run; the benchmark is deterministic).

## Phase 2: lexicon gaps (done 2026-11-21)

### What changed

Production: `src/main/java/com/iitsm/domain/strategy/classification/KeywordClassifier.java` only.

| Category | New terms |
|---|---|
| HARDWARE | printer, print, toner, jam, scanner, webcam, camera, charger, usb |
| NETWORK | internet, unreachable, connectivity, proxy |
| SOFTWARE | email, mail, inbox, outbox, zoom, webex |
| AUTHENTICATION | unlock |
| SECURITY | virus, antivirus |
| CLOUD | vm |

**Pair terms** (`KeywordClassifier.PAIR_TERMS`, record `PairTerm`): two ordinary words that count as one term only when
both appear anywhere in the ticket, in any order. Declared: `account`+`locked` and `account`+`disabled`
(AUTHENTICATION). A pair is reported as `account+locked` among the matched terms; both words in the title make it a title
match (0.55), otherwise a weak match (0.50). "locked" stays in `GENERIC_WORDS` and "account" stays out of the lexicon,
so the locked desk drawer (A02) and a bank account balance are still no evidence. Adding a pair is one more list entry.

Left out on purpose: "account" alone, "server", "call", "console" (an Okta admin console would tie CLOUD with
AUTHENTICATION), "locked out" (impossible: "out" is a stop word). The duplicate vocabulary was not touched (ADR-035).

### Tests

- `KeywordClassifierRulesTest` (+6, now 19): every new word lands in its category; inflected forms (jammed, printers,
  emails, viruses); the pair in either order and spread over title and description; the pair's title rule (0.55 vs 0.50);
  either pair word alone is no evidence; no pair word is a lexicon term of its own category.
- `EverydayTicketBenchmarkTest`: floor for the right team raised 21 -> 24.
- Mutation checks (each made tests fail, then undone): the `account`+`locked` pair removed (3 failures, including the
  benchmark); bare "locked" put back in AUTHENTICATION (6 failures); "printer" moved to NETWORK (the benchmark).

### Measured

| Measure | Phase 1 | Phase 2 |
|---|---|---|
| everyday clear tickets auto-routed to the right team | 21 / 35 (60.0 %) | **24 / 35 (68.6 %)** |
| everyday clear tickets left in triage | 12 | 9 |
| everyday clear tickets to a wrong team | 2 | 2 |
| vague or non-IT auto-routed | 0 / 7 | 0 / 7 |
| held-out met / in triage / right team | 33 / 3 / 31 of 33 | 33 / 3 / 31 of 33 |
| audit examples met | 12 / 12 | 12 / 12 |
| duplicates: recall, precision, margin | 54/87, 93/94, 0.0564 | unchanged |

Fixed: E01 Printer not working, E13 Server unreachable, E23 Account locked. Not fixed, as predicted: E02 Printer jammed
(printer, jam), E18 Need software installed (software, install), E19 Email not sending (email, outbox), E27 Antivirus
found a virus (antivirus, virus) and E33 Azure VM not starting (azure, vm) now have two or more keywords of one category,
but `CompositeClassifier` still caps them because Naive Bayes names another category that no keyword supports (Phase 3).
Still without enough evidence: E08 webcam (one keyword; Naive Bayes says SECURITY 0.90), E12 internet (one keyword;
Naive Bayes 0.25), E17 Teams calls, E29 database error.

Found on the way: "Bank" is APPLICATION evidence, because the stemmer reduces the lexicon word "banking" to "bank".
That was so before this change and is left alone (a banking-application ticket benefits from it).

## Phase 3: fusion rules (done 2026-11-21)

Production: `CompositeClassifier`, `ClassificationResult` (`leadsAllOtherCategories`), `KeywordClassifier` ("virus" added to the security indicators, `titleCategories`).

1. **Decisive keywords**: two or more distinct terms of one category, more than any other, stand against a Naive Bayes answer
   no keyword supports (Need software installed, Email not sending, Antivirus found a virus, Azure VM not starting). Everything
   less decisive keeps the ADR-034 cap.
2. **Label of a capped ticket**: the suggestion shown to the administrator is the keyword category, not an unsupported Naive
   Bayes guess (Teams calls dropping was suggested as DATABASE), unless the keyword evidence is a lone description word
   (`Round3RegressionTest.N2` pins that "Wi-Fi access points ... firmware update" stays NETWORK).
3. **Title-led tie**: when the keywords tie and the title names exactly one of the tied categories, Naive Bayes may not break
   the tie against the title ("Wifi not working ... on my laptop" was sent confidently to the Service Desk). The ticket goes
   to a person with the title's category.
4. **"virus"** is a security indicator.
5. The fused result carries the keyword support and the security flag.

Tests: `CompositeClassifierEverydayRulesTest` (15, stub-based, independent of the corpus); two `CompositeClassifierTest` cases
changed on purpose (they pinned the old cap); `DuplicateTriageGateRegressionTest` vague text shortened (its tickets now share a
category, so a long shared sentence made them duplicates of each other). Five mutation checks (decisive rule, title-led tie,
label rule, "virus", the lone-description guard) each failed a test.

New validation set: `src/test/resources/classification/everyday_validation.tsv` (24 tickets, written before the corpus was
touched, never used to choose a word or rule) and a second benchmark test; on the original code 11 of 19 clear tickets reached
the right team, 2 a wrong team.

Measured after Phase 3: tuning set 29 / 35 right, 0 wrong (E11 and E32 now go to a person: a title-led tie and an
"unauthorized" security word); validation 13 / 19, 1 wrong; held-out routing 31/33 -> 32/34; duplicates unchanged.

## Phase 4: corpus (tried, reverted)

72 new rows (8 per category) for printers, e-mail, internet, lockouts, Teams, antivirus and cloud consoles. Tuning set 29 -> 31,
but: the rows are near-paraphrases of those tickets (leakage); the validation set did not improve (13 / 19) and E13 got worse;
duplicate-detection recall fell from 54/87 to 52/87, the minimum, because the TF-IDF vectoriser is trained on the same corpus.
No validated gain and a measured risk to another feature: **`sim/corpus.csv` is unchanged**, `CorpusPairingTest` is unchanged.

## Phase 5: the matched IT service as evidence (done 2026-11-21)

New: `domain/strategy/classification/ServiceCorroboration` (pure policy). Changed: `IncidentIntakePipeline` (calls it after the
service match; new `TicketRouter` constructor argument, nothing builds the pipeline by hand).

A capped ticket is lifted to exactly the gate when a keyword supports the final category, the matched IT service (not the
general one) is owned by the team that handles that category, and there is no security indicator. A service match alone routes
nothing and cannot change the category. Fixes Report fails with database error (SVC-CORE-DB), Wifi not working (SVC-VPN, same
team), Scanner on second floor broken.

Tests: `ServiceCorroborationTest` (8), `EverydayTicketAssignmentRegressionTest` (12, whole application: 8 clear tickets assigned,
3 for a person stay in triage, a security term stays with a person). Both benchmarks replay the same steps as the pipeline.
Four mutation checks (owning-team check, security guard, keyword requirement, the pipeline call) each failed tests.

## Phase 6: result

| Measure | Original code | Phase 1 | Final |
|---|---|---|---|
| tuning set (35 clear): right team | 21 (60.0 %) | 21 | **31 (88.6 %)** |
| tuning set: left in triage | 12 | 12 | 4 |
| tuning set: wrong team | 2 | 2 | **0** |
| tuning set: vague/non-IT kept for a person | 7 / 7 | 7 / 7 | 7 / 7 |
| validation set (19 clear): right team | 11 (57.9 %) | | **14 (73.7 %)** |
| validation set: wrong team | 2 | | 1 (V14, a lost laptop, classified HARDWARE) |
| held-out met / in triage / right team | 33 / 3 / 31 of 33 | | 33 / 2 / 32 of 34 |
| audit examples | 12 / 12 | | 12 / 12 |
| duplicates: recall, precision, margin | 54/87, 93/94, 0.0564 | | unchanged |

**The 90 % target of ADR-052 was not reached** (88.6 % on the tuning set, 73.7 % on the validation set). The four tuning tickets
still in triage are Webcam not detected, Internet is down, Teams calls dropping and AWS console access ("unauthorized" is a
security indicator, so a person decides). Each has one keyword and a Naive Bayes answer that is wrong, weak or security-flagged.
Reaching 90 % would need a retrained model with its own held-out evidence (Phase 4 showed the corpus change tried here does not
generalise) or a lower gate (rejected in ADR-052). The benchmark floors are the measured values.

The validation set was looked at twice (Phase 4 decision, Phase 5 what-if), so it is no longer completely blind.

A triage ticket is still silent: no alert reaches an administrator and the employee sees "NEW". That is a product decision
outside this fix and was not implemented.

### Verification

See the 2026-11-21 entry of the CLAUDE.md change log for the final gate numbers.
