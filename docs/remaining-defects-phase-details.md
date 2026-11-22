# Remaining defects: detailed plan for phases F to J

Taken from the planning session of 2026-11-05 and kept here so the phases can be resumed from the repository alone.
The decisions D1-D22 are in `docs/remaining-defects-plan.md`; phases A to E are done (see that file for their status).
Rules for every phase: failing tests first, a proof that each test can fail (mutation check), full `mvn -o clean verify`,
the one-JVM-per-class run, a live check against the running app (a real browser for UI changes), an ADR, a summary document,
CLAUDE.md and the plan status updated, commits per concern, never pushed.

### Phase F: classification quality (L)
- **Assumes:** D13-A, D14 (accuracy not lower, all audit examples fixed, triage rate up by at most 10 points), D15-B, D16-A.
- **Key design move: separate the duplicate vocabulary from the classifier's word list.** Today `DomainVocabulary` is built from the classifier lexicon. The current lexicon is frozen as its own resource for duplicate detection, so cleaning up the lexicon **cannot move duplicate scores**. Only the corpus repair (L7) still affects duplicates, through the TF-IDF weights.
- **Changes, in order, each measured separately with the Phase A harness:**
  1. Count each keyword once per ticket.
  2. The 3-or-more-keywords shortcut also requires a clear lead over the next category; otherwise the ticket goes through the combination of both models.
  3. When the models disagree and the keyword score is at least 0.60, cap the ticket into triage instead of letting Naive Bayes win.
  4. Remove the generic words from the classifier lexicon (the duplicate vocabulary is untouched).
  5. Repair `corpus.csv`.
- **Tests written first:**
  - The audit examples: phishing with an Excel macro → SECURITY or triage; desk drawer PIN, parking garage door, coffee machine → triage.
  - "vpn vpn vpn" → single-keyword-in-title behaviour.
  - Boundary tests at 0.55 / 0.60 / 0.61.
  - **The benchmark test now asserts** the D14 budget.
- **Proof each test can fail:** undo each rule.
- **Existing tests changed on purpose:**
  - `CompositeClassifierTest` cases.
  - `KeywordClassifierDomainTermTest` (the vocabulary's source moves).
  - `NaiveBayesClassifierTest` numbers.
  - `DuplicatePairSetTest` **only** if you approve a new recall baseline at the checkpoint.
  - **Intent must be kept:** F5, `AutoAssignmentRegressionTest`, the duplicate triage gate tests, simulation S3 and S4.
- **Docs:** ADR-034 (classification evidence rules), ADR-035 (vocabulary split and corpus repair). §7 items 4 and 5 are re-checked and closed or kept with numbers.
- **What could go wrong:** the triage queue floods; recall falls below 59%; the 0.05 safety margin shrinks; the known false link moves. Each of these is measured after every step; if a step breaks the budget, undo that step.
- **Exit:** the budget is met; the duplicate pair-set targets are met (or a new baseline is approved); S1–S5 pass.
- **Your checkpoint:** a before/after table of accuracy, triage rate, recall, precision and margin, and the audit examples.

### Phase G: routing (M)
- **Assumes:** D17-A (category decides the team), D18-B (overflow to skilled technicians only, otherwise wait).
- **Changes:**
  - One routing rule (a pure-domain `RoutingPolicy` plus an application `TicketRouter`) used by all four callers: intake, the dispatcher, reroute and accept-suggestion.
  - `CandidateFilter` filters cross-team overflow by the category skill.
  - Remove the duplicated fallback lines.
  - The service still decides the SLA criticality.
- **Tests written first:**
  - A DNS ticket with no matching service → Network team. Fails today: it goes to the Service Desk.
  - A ticket whose service owner differs from its category's team → the category's team.
  - The owning team is full → a skilled technician from another team, never an unskilled one.
  - Nobody skilled is free → the ticket waits CLASSIFIED, and the dispatcher applies the same rule.
  - **One shared contract test, run against all four callers.**
- **Proof each test can fail:** go back to the service owner; remove the skill filter.
- **Existing tests changed on purpose:** team expectations in `AutoAssignmentRegressionTest`, `UnassignedIncidentDispatcherTest`, `AssignmentStrategyTest`, `CorrectnessRegressionTest` F4/F7 (team only, not capacity). `ReclassifyRoutingRegressionTest` should stay green, since it already routes by category.
- **Docs:** ADR-036 (routing); §1.1 rule-2 deviations removed from the code touched.
- **What could go wrong:**
  - Tickets wait forever: watch the age of the oldest waiting ticket and the SLA response escalations.
  - Service Desk volume drops sharply: expected.
  - Confident misrouting: compare against the F numbers.
- **Exit:** at least 95% of auto-assigned tickets in the audit replay go to their category's team; no ticket waits longer than its response target without escalating.

### Phase H: privacy and actors (M)
- **Assumes:** D19-B, D20-A (plus B if D21 is yes).
- **Changes:**
  - Neutral employee wording for the duplicate and reassignment entries.
  - A single web-layer helper that resolves the signed-in user's id; every controller passes ids.
  - Staff views resolve names in one batch lookup (no query per row).
  - `overriddenByName` field.
  - Optional V8 to convert old e-mail actors to ids.
- **Tests written first:**
  - No employee view contains another ticket's id or a technician's reason (checked across every employee view of a seeded run).
  - After assign, override, note and resolve, the stored actor is the user's id.
- **Proof each test can fail:** put the e-mail back at one controller.
- **Existing tests changed on purpose:** `EmployeeAuditTrailPrivacyTest` (new texts), `AdminIncidentDetailMapperTest`, plus any test asserting an e-mail actor (found by grep at the phase start).
- **Docs:** ADR-037; §7 items 2 and 13 closed.
- **Migration:** V8 tested on H2 and PostgreSQL; idempotent; it never touches `audit_records.description`, which V6 reads.
- **UI:** names shown in the staff views; asset version bump.

### Phase I: old data and the event history (S–M)
- **Changes:**
  - **V7** (only if D21 = yes): unescape the five sequences the old code produced, only on rows saved before the fix, repeated until nothing changes. Tested on H2 and PostgreSQL with fixtures: escaped once, twice and three times; a legitimate `&amp;` in a row saved after the fix (left alone); a second run that changes nothing; titles within 255 characters.
  - **P4:** the event history becomes a bounded buffer with a configurable size (0 in production).
  - **R-N:** the pending and dropped notification counters become Actuator metrics.
  - **T1:** the pinning test for a confident duplicate of a parent that sits in triage.
- **Docs:** ADR-038; the R-C1 and P4 entries in CLAUDE.md closed.
- **Note:** without D21 = yes, this phase shrinks to P4, the metrics and the T1 test.

### Phase J: front-end closure (M)
- **Changes:**
  - Login and registration go through the double-submit guard.
  - Refresh the list after 400 and 403 too.
  - Keep keyboard focus on the button after it re-enables.
  - **Click every action button in a real browser:** Resolve, Verify, Reject, Reassign, Override, Request info, Add note, and the admin actions.
  - Optional: keyboard focus trap in the dialog, and the 2- vs 3-decimal display (§7 item 6); `displayId` in the triage list (§7 item 14).
- **Tests:** markup contract tests, a DOM test harness, and a browser run through the real login as each persona. **I recommend committing the browser scripts this time**: earlier ones were never added to the repository, so they can't be re-run.
- **Exit:** zero console errors except none (the favicon error is fixed in C1).
