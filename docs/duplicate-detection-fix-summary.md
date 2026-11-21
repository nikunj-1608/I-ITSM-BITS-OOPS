# Duplicate detection fix (stress audit H1) - summary

Date: 2026-10-31 - decisions in ADR-016. Work was done in phases with a human checkpoint after each.

## Why
The stress audit reported that duplicate detection merges unrelated tickets and called it High. Measurement
(Phase 0) corrected that: realistic tickets were not linked wrongly, but

1. tickets whose specific details were words absent from the training corpus were linked en masse
   (`TfIdfVectorizer` gave unseen words IDF 1.0 while corpus words got 0.77-4.38), which is what produced the audit's
   542-of-652 clustering and the 56-tickets-on-one-technician result (those tickets were the audit's own generator:
   three keywords plus random strings);
2. near-empty generic tickets ("Problem since this morning") could link on filler words;
3. real duplicates were rarely caught: 9 of 87 natural paraphrases and 5 of 58 in a live replay (9 %).

## What changed
| Area | Change |
|---|---|
| `TfIdfVectorizer` | An unseen word gets IDF `ln(1 + N)` (the formula at document frequency 0) instead of 1.0; a repeated word counts `1 + ln(count)` (sublinear). An untrained vectorizer falls back to IDF 1.0. |
| `DomainVocabulary` (new interface), `KeywordClassifier` | The classifier's curated lexicon (words and stems) is exposed as the project's list of technical terms. |
| `DuplicateMatchPolicy` (new, domain) | A pair links only if similarity >= threshold **and** both texts share at least N domain terms (N = 0 turns the guard off). Validates its configuration. |
| `DuplicateDetectionService` | Uses the policy instead of a fixed 0.70. Candidates are only open tickets of the same service and category inside the recency window; the all-time `findByCategory` fallback is gone. A new ticket joins the cluster root only if it also matches the root, otherwise it links to its best match alone (no chain drift). Split into short methods. |
| Configuration | `iitsm.duplicates.similarity-threshold` (0.30), `min-shared-terms` (1), `recency-window-hours` (72). Environment overrides: `DUPLICATE_SIMILARITY_THRESHOLD`, `DUPLICATE_MIN_SHARED_TERMS`, `DUPLICATE_RECENCY_WINDOW_HOURS`. |

Unchanged on purpose: duplicates still use no technician capacity slot (`CorrectnessRegressionTest.F4`); the P1 avalanche
rule counts links on the cluster root (F3); the intake pipeline is untouched.

## Results
All numbers come from a synthetic pair set written for this work plus pairs adjudicated by hand from a live replay.
They are evidence, not a guarantee on real ticket data.

| Measure | Before | After |
|---|---|---|
| Natural paraphrases linked | 9 / 87 | 52 / 87 (59.8 %) |
| Identical text / S4 demo pairs | 100 % | 100 % |
| Same category, different fault / different category | 0 links | 0 links |
| Near-empty generic pairs linked | 2 / 4 | 0 / 4 |
| Random-word template tickets linked (live) | 43 / 45 | 0 / 45 |
| Real duplicates caught (live replay) | 5 / 58 | 29 / 58 |
| Largest cluster (live replay) | 8 | 3 |
| Overall recall on the 290-pair set | 33 % | 72 % at 98.9 % precision |

Phase 5 re-ran the original audit scripts on the fixed code: 202 of 202 intake tickets created (3 were lost before),
no unhandled server exceptions, worst technician load 15 / 8 instead of 56 / 6, and the technician pool genuinely
exhausted, so the waiting-ticket path is now exercised.

## Tests
- `DuplicatePairSetTest` (4): production classes over `src/test/resources/duplicates/labelled_pairs.tsv` (290 pairs).
- `DuplicateDetectionRegressionTest` (3): template tickets not clustered; identical filler-only tickets not linked;
  a resolved ticket is not a candidate.
- `DuplicateMatchPolicyTest` (6), `KeywordClassifierDomainTermTest` (1), `TfIdfVectorizerTest` (+4),
  `DuplicateDetectionTest` (3 -> 6: no fallback, configurable window, chain A-B-C).
- Mutation checks: restoring the old unseen-word weight fails 3 of the new tests; forcing root-always clustering fails
  the chain test.
- Suite 387 -> 404, 0 failures. One existing assertion was loosened: `TfIdfVectorizerTest.testCosineSimilarity` used a
  3-document corpus where almost every word is unseen; its bound moved from > 0.70 to > 0.50 and 0.30 above the
  unrelated pair (intent unchanged).

## Known limits and open items
1. **One known false link**: "External screens dead on dock" / "Dell USB-C dock Ethernet port completely dead" scores
   0.33. Only a threshold of 0.34 or more blocks it, at about 9 points of recall. Kept at 0.30 (precision still
   98.9 % on the pair set); the pair is listed as `KNOWN_FALSE_LINKS` in `DuplicatePairSetTest`.
2. **Margin**: the highest-scoring genuine non-duplicate in the original set is 0.2455 ("Cannot connect to corporate
   VPN" vs "VPN certificate expired"), so the 0.05 margin to 0.30 is thin; the threshold is configurable.
3. **Paraphrases with no shared words** ("Antivirus found malware" vs "EDR alert trojan detected") cannot be caught by
   word overlap; roughly a third of natural paraphrases stay missed. No synonym list or embeddings (ADR-016).
4. **Identical filler-only tickets never link**, because they contain no domain term (precision first).
5. **Capacity**: more correct duplicates now ride their parent's technician without a slot (F4), so technicians can
   exceed capacity more often (17 of 24 in the Phase 5 stress run). Whether duplicates should count is a separate
   product decision.
6. **Option (c) deferred**: about 14 of 58 duplicates in the replay are never compared because the two reports got a
   different category or IT service.
7. Pre-existing links are not recomputed.
8. Out of scope here: duplicates skipping the triage gate (audit H2), concurrent duplicate reports returning HTTP 500
   (H3), the one-transaction SLA scan (H10).
9. Each intake still vectorizes every candidate; cache vectors if the open-ticket volume grows.
