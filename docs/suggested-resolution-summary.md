# Suggested Resolution (spec feature G)

Plan: four phases, K1 to K4, each stopped for approval. Decisions: ADR-040 (K1), ADR-041 (K2), ADR-042 (K3), ADR-043 (K4).
Diagram: `docs/uml/suggested-resolution.puml`.

## Phase K1: suggestions from resolved tickets (2026-11-07)

### What was built
- `GET /api/technician/incidents/{id}/suggested-resolutions` returns at most 3 past fixes for a ticket, best first.
  Each one has: source type (`RESOLVED_TICKET`), source id and staff display name, title, root cause, steps,
  preventive action, similarity score, and the matched terms that explain the score. If nothing is similar enough
  the answer is an empty list, never a weak match.
- Who may call it: technicians, team leads and administrators. Employees get 403 and a missing token gets 401. On top
  of that the ticket's own access rules apply: someone who may not view the ticket gets 403
  `ERR_UNAUTHORIZED_OPERATION`, and an unknown ticket gets 404 `ERR_SERVICE_NOT_FOUND`.
- Candidates are tickets in RESOLVED, VERIFIED or CLOSED. A rejected fix is never suggested: that ticket is back in
  IN_PROGRESS even though its old resolution is still stored. Never suggested either: the ticket itself and its linked
  duplicates. By default a candidate must also have the ticket's category.
- No schema change, no migration, no new error code, no new event, and no change to any employee view.

### Where the patterns are, and why
| Pattern / OOP | Class | Why |
|---|---|---|
| Strategy | `ResolutionSuggester` (domain interface), `TfIdfResolutionSuggester` | Another ranking plugs in as a new bean with no edit to the service or the web layer; `ResolutionSuggestionServiceTest.anotherSuggesterPlugsIn` proves it. |
| Strategy (reused) | `SimilarityMeasure` / `CosineTfIdfSimilarity`, `DomainVocabulary` | The same TF-IDF cosine and frozen vocabulary as duplicate detection (ADR-016, ADR-035), so scores and explanations behave consistently. |
| Value objects (records, invariants) | `SuggestionPolicy`, `SuggestionQuery`, `SuggestionCandidate`, `ResolutionSuggestion` | Every rule lives in the domain and is unit-tested: category filter, exclusions, minimum score, shared-term guard, top-N cut, explanation. |
| Tell, don't ask | `IncidentStatus.hasConfirmedResolution()`, `SuggestionQuery.forIncident`, `SuggestionPolicy.accepts/admitsCategory` | The service never compares statuses or scores itself. |
| Repository port + Adapter | `ResolvedCaseRepository` -> `ResolvedCaseRepositoryAdapter` -> `SpringDataResolvedCaseRepository` | A light read-only projection (`ResolvedCaseRow`): the ranking never loads a whole ticket. |

### Cost
One request runs one query that reads at most `candidate-window` (200) rows, newest resolution first. The ranking then
does about 400 TF-IDF transforms. Measured on the development laptop with a full 200-candidate window: median 5 to 6 ms,
worst 12 to 13 ms (ranking only, without the database). Fixes older than the newest 200 of their category are not seen
(Phase K3's knowledge base keeps a good fix suggestable).

### Configuration (`application.yml`, `iitsm.suggestions.*`)
| Key | Default | Meaning |
|---|---|---|
| `max-results` | 3 | Most suggestions returned |
| `min-similarity` | 0.20 | Lowest similarity suggested |
| `min-shared-terms` | 1 | Domain terms ticket and fix must share |
| `same-category-only` | true | Hard category filter |
| `candidate-window` | 200 | Newest resolved tickets compared |
| `explanation-terms` | 5 | Most matched terms shown |

### Measured quality (`SuggestionBenchmarkTest`, report in `target/benchmarks/suggestion-benchmark.txt`)
Fixture: `src/test/resources/suggestions/` holds 27 synthetic past fixes (3 per category) and 24 new tickets written in
other words (19 with a fitting fix, 5 with none).

| min-similarity | top-3 hits | "no fit" tickets that got a suggestion |
|---|---|---|
| 0.10 to 0.25 | 18/19 | 0/5 |
| 0.30 | 17/19 | 0/5 |
| 0.35 | 16/19 | 0/5 |
| 0.40 | 15/19 | 0/5 |

At the default (0.20): top-3 hit rate 18/19 (0.947), top-1 18/19, no-fit 0/5. The assertions are loose floors: hit
rate at least 0.80, no-fit at most 0.40. The one miss is a printer ticket, because `printer` and `toner` are not in
the duplicate vocabulary, so the content guard refuses it.

### Tests (K1)
| Class | Tests | What |
|---|---|---|
| `TfIdfResolutionSuggesterTest` | 15 | ranking, ties, top-N, minimum, category filter on/off, no category, self and linked exclusion, content guard, explanation (shared terms, words not stems, bounded, unmodifiable), real TF-IDF, empty inputs |
| `SuggestionValueObjectsTest` | 17 | query from a ticket, exclusions, copies, matching text, invariants, policy ranges, window category, `hasConfirmedResolution` for all 9 statuses |
| `ResolutionSuggestionServiceTest` | 6 | bounded window, 403 and 404 before any read, no category, filter off, extension point |
| `ResolvedCaseRepositoryAdapterTest` | 4 | H2: confirmed statuses only, newest first, limit, category filter, projection content |
| `SuggestedResolutionRegressionTest` | 4 | HTTP end to end: A is suggested for a similar B; a dissimilar ticket gets []; lead and admin 200, other team 403, employee 403, no token 401, unknown 404; the employee view has nothing about it |
| `SuggestionBenchmarkTest` | 1 | quality and cost report (benchmark tag) |

**Mutation checks**: each of these was applied by hand, made at least one test fail, and was then undone:
1. Category filter removed.
2. Ticket itself not excluded.
3. Minimum similarity ignored.
4. Top-N cut removed.
5. Access check removed from `IncidentLifecycleService.getIncident`.
6. Shared-term guard off.
7. Employees allowed through both the URL rule and `@PreAuthorize`.

**How to check by hand**: start the app (`-Dmaven.test.skip=true spring-boot:run`), then run
`python scripts/live-checks/suggestions_check.py`.

### Known gaps (K1)
- The vocabulary is the frozen duplicate vocabulary (ADR-035) and lacks some hardware words, so printer and toner tickets are refused.
- The "no fit" benchmark tickets are kept out by the shared-term guard, not by the score threshold.
- A fix older than the window is not seen (fixed for promoted fixes in K3).
- No index on `resolutions.resolved_at`.
- Stems can make near-identical words appear twice (`connection`, `connections`).
- A technician can see the technical content of fixes on tickets they may not open (deliberate, ADR-040 decision 8).
- Not run: CI on JDK 21.

## Phase K2: votes (2026-11-07)

### What was built
- `POST /api/technician/incidents/{id}/suggested-resolutions/votes` with `{sourceType, sourceId, worked}`. It stores the
  signed-in user's verdict on a suggested fix for this ticket and answers with the source's vote counts.
- Each voter gets one vote per (voter, source, ticket). Voting again changes the earlier vote; it does not add a second one.
- The suggestion list now also shows `score`, `workedVotes` and `didNotWorkVotes`.
- Flyway **V9** adds the table `suggestion_votes`: a unique key on voter + source type + source id + target ticket, a
  cascade on the target ticket, and an index for the counts. V7 and V8 stay reserved.
- New error code path: `ResourceNotFoundException` -> 404 `ERR_NOT_FOUND`. There is no new event and nothing changed in
  any employee view.

### How votes change the ranking
`score = similarity x factor`, where `factor = 0.8 + 0.4 x (worked + 1) / (worked + didNotWork + 2)`. That means:

- With no votes, or an even split, the factor is exactly 1, so the score equals the similarity.
- With a billion "worked" votes the factor is still below 1.2; with a billion "didn't" votes it is still above 0.8.
- The 0.20 minimum is checked on the similarity alone. Praise never lets a weak match in, and complaints never push a
  qualified match out.
- Votes are applied before the top-3 cut, so a praised close fourth can enter the list.
- Votes cannot overturn a clear gap: a 0.30 match at the top of the band scores at most 0.36, and a 0.50 match at the
  bottom still scores 0.40.

Counts are per source, added up over every ticket and voter, so a fix that worked on earlier tickets ranks higher on
the next one. The weight is `iitsm.suggestions.vote-weight` (0.20); 0 switches votes off.

### Error answers
| Case | Answer |
|---|---|
| unknown `sourceType`, broken JSON | 400 `ERR_MALFORMED_JSON` |
| missing `sourceType`, `sourceId` or `worked`; blank `sourceId` | 400 `ERR_VALIDATION_FAILED` |
| a resolved ticket voted as its own fix | 400 `ERR_VALIDATION_FAILED` |
| source does not exist, or is an open ticket / rejected fix | 404 `ERR_NOT_FOUND` |
| `KB_ARTICLE` source (until K3) | 404 `ERR_NOT_FOUND` |
| unknown target ticket | 404 `ERR_SERVICE_NOT_FOUND` |
| target ticket not viewable (other team) | 403 `ERR_UNAUTHORIZED_OPERATION` |
| employee / no token | 403 / 401 |
| two first votes of one voter on one key at the same moment | 409 `ERR_CONCURRENT_MODIFICATION` for the second |

### Where the patterns are
| Pattern / OOP | Class | Why |
|---|---|---|
| Value objects with invariants | `SuggestionVote`, `SuggestionSourceKey`, `VoteTally`, `VoteTallies`, `VoteBand` | The smoothing, the band and the self-vote rule are domain rules with unit tests. |
| Strategy (extended) | `ResolutionSuggester.suggest(query, candidates, votes)` | Another ranking receives the same vote counts; the two-argument default means "no votes". |
| Repository port + Adapter | `SuggestionVoteRepository` -> `SuggestionVoteRepositoryAdapter` -> `SpringDataSuggestionVoteRepository` | The upsert and the grouped count query stay in infrastructure. |

### Tests (K2)
| Class | Tests | What |
|---|---|---|
| `SuggestionVoteRulesTest` | 17 | formula, neutrality, strict bounds, monotonic, weight range, tallies; vote recast, self vote, invariants; votes reorder close suggestions, cannot overturn a clear gap, cannot lift a weak match or drop a qualified one, apply before the cut |
| `ResolutionSuggestionServiceTest` | 12 (6 new) | first vote stored with voter id and clock time, second vote replaces the first, unknown source 404, article 404, no access 403 before any lookup, self vote 400 |
| `SuggestionVoteRepositoryAdapterTest` | 6 | H2: read back, upsert keeps one row, unique key, counts per source and type, cascade on ticket delete, `isResolvedCase` |
| `SuggestionVotesMigrationH2Test` / `SuggestionVotesMigrationPostgresTest` | 4 + 4 | V9 applied with V7/V8 free; unique key; voter, ticket and source-type checks; rename and delete cascade |
| `SuggestionVoteRegressionTest` | 5 | HTTP end to end: upsert under the voter's id, counts and bounded score in the list, every 400, 404, 403 and 401 |

**Mutation checks**: each of these made at least one test fail and was then undone:
1. Unbounded factor.
2. Minimum applied to the adjusted score.
3. Votes ignored in the order.
4. Upsert turned into insert.
5. Unknown source accepted.
6. Self vote allowed.

**How to check by hand**: `python scripts/live-checks/suggestion_votes_check.py` against the running app.

### Known gaps (K2)
- Any staff member who may view the ticket can vote, including an administrator who never worked on it.
- A vote can be changed but not withdrawn.
- Votes are not reset when a ticket is reopened or reclassified.
- One technician voting on many tickets can push a source to the top of the band (at most +20 %).
- Votes on a ticket that is later promoted to the knowledge base stay with the ticket, not the article (K3).

## Phase K3: knowledge base (2026-11-07)

### What was built
- `POST /api/technician/knowledge-articles` with `{sourceIncidentId, title?}` answers **201** with the new article. The
  article copies the resolved ticket's technical content: title, problem, root cause, steps and preventive action. Its id
  is `KB-` followed by the ticket id.
- Who may promote: the technician who resolved the ticket, any team lead, or an administrator. They must also be allowed
  to view the ticket.
- Flyway **V10** adds the table `knowledge_articles`. It has a unique source ticket, a cascade on the ticket, an index on
  (category, created_at), and no column for reporter data.
- Articles are a second suggestion source (`KB_ARTICLE`). Each request reads the newest `article-window` (200) articles
  of the category. A promoted ticket is offered once, as its article. A ticket is never offered an article made from
  itself or from a ticket it is linked to as a duplicate. Articles can be voted on.
- There is no list, edit or delete endpoint (out of scope), no event, and nothing in any employee view.

### Privacy
- Every copied text loses the reporter's full name, every name part of at least 3 letters, the e-mail address and its
  local part, and the department. Each is matched as a whole word, ignoring case, and replaced by `[removed]`.
- A title that was nothing but personal data becomes "Fix from ticket <id>".
- `KnowledgeBaseRegressionTest` reports a ticket whose title, description, root cause and steps all name the reporter,
  then checks that the article contains none of it.

### Error answers
| Case | Answer |
|---|---|
| ticket not RESOLVED/VERIFIED/CLOSED, no resolution report, or no category | 409 `ERR_NOT_RESOLVED` |
| ticket already promoted | 409 `ERR_ALREADY_PROMOTED` |
| a technician who did not resolve it | 403 `ERR_UNAUTHORIZED_OPERATION` |
| the ticket is not viewable (another team) | 403 `ERR_UNAUTHORIZED_OPERATION` |
| employee / no token | 403 / 401 |
| unknown ticket | 404 `ERR_SERVICE_NOT_FOUND` |
| missing `sourceIncidentId`, title over 255 characters | 400 `ERR_VALIDATION_FAILED` |
| broken JSON | 400 `ERR_MALFORMED_JSON` |
| two promotions of one ticket racing | 409 `ERR_CONCURRENT_MODIFICATION` for the second |

### Where the patterns are
| Pattern / OOP | Class | Why |
|---|---|---|
| Polymorphism (Tell, Don't Ask) | `User.canPromoteResolution` overridden in `Technician`, `TeamLead`, `Administrator` | The "who may promote" rule lives with the user types; there are no role checks in the service. |
| Immutable entity with invariants | `KnowledgeArticle` (`promote` factory) | The status and actor rules and the redaction are applied in one place. |
| Policy object | `PersonalDataRedactor` | The privacy rule is one unit-tested class. |
| Value object | `CandidatePool` | Merging tickets and articles, and offering a promoted fix only once, is a domain rule. |
| Repository port + Adapter | `KnowledgeArticleRepository` -> `KnowledgeArticleRepositoryAdapter` | The adapter never overwrites an article; the article window is one query. |

### Tests (K3)
| Class | Tests | What |
|---|---|---|
| `KnowledgeArticleTest` | 18 | who may promote (and who may not); every status that is not resolved; no report; content and redaction; titles; invariants; the redactor's whole-word, e-mail and short-name rules; the candidate pool |
| `ResolutionSuggestionServiceTest` | 14 (2 new) | articles join the window and replace their ticket; an article vote needs an existing article |
| `SuggestionValueObjectsTest` | 18 (2 new, 1 replaced) | exclusion by origin ticket; defaults of origin and article window |
| `KnowledgeArticleRepositoryAdapterTest` | 4 | H2: store and read; never overwrite; newest-first window by category or all; candidate shape |
| `KnowledgeArticlesMigrationH2Test` / `...PostgresTest` | 4 + 4 | V10 applied with V7/V8 free; one article per ticket; references; no personal columns; rename and delete cascade |
| `KnowledgeBaseRegressionTest` | 6 | HTTP: resolver promotes without personal data; second promotion 409; lead and admin 201, open ticket 409; every 400/401/403/404; B gets A's article (not A) and the vote counts; a reopened ticket never gets its own article |
| `KnowledgeBaseWindowRegressionTest` | 1 | with `candidate-window=1`, A has left the ticket window but its article is still suggested |

**Test isolation**: the three suggestion regression classes now delete the resolutions, votes and articles of the
tickets they create. Otherwise their leftover resolved tickets, which all have the same text, could crowd another class's
top 3 in the shared H2 database, depending on test order.

**Mutation checks**: each of these made tests fail and was then undone:
1. Redaction off.
2. Second promotion allowed (both the service and the adapter checks removed).
3. Any technician may promote.
4. An unresolved ticket may be promoted.
5. A promoted ticket offered twice.
6. A ticket's own article not excluded.
7. Articles left out of the suggestions.

**How to check by hand**: `python scripts/live-checks/knowledge_base_check.py` against the running app.

### Known gaps (K3)
- Only the reporter's known data is removed. A colleague's name, a phone number or a customer name written in the text
  is copied.
- A department word used in a technical sense is removed too.
- Articles cannot be listed, edited or deleted.
- An article does not follow later changes of its ticket.
- Resolutions stored before ADR-037 name the resolver by e-mail, so only a team lead or an administrator can promote
  them.
- More than 200 articles in one category hide the oldest.
- The live script checks the department only when `/api/auth/me` returns one; it currently does not.

## Phase K4: the web app (2026-11-08)

### What was built
- **"Suggested fixes" button** on every technician card of an open ticket (my queue, team queue, team lead). It opens
  a dialog and only then loads the suggestions, so drawing the queue costs nothing extra.
- **The dialog** shows up to 3 fixes, best first. Each one has:
  - its source: "Resolved ticket" with the staff ticket name, or "Knowledge base" with the article id;
  - the similarity and the ranking score with three decimals;
  - title, root cause, steps, preventive action and the matched terms;
  - **This worked** and **Didn't work** buttons with their counts.
  - If nothing is similar enough it says "No similar past fix was found for this ticket."
- **"Promote to knowledge base" button** on a resolved card, shown only when the server says `promotable`. It opens a small
  dialog with an optional title and a note that the reporter's name, e-mail and department are removed.
- **`promotable`** is a new field of the technician view. It is `Incident.canBePromotedBy(viewer)`, the same rule the promote
  endpoint applies, so the page keeps no copy of the rule. Employees get no such field and no control.
- Asset version **3.9** (`index.html`), new section 16 in `app.js`, new `.suggestion-*` styles.

### How it follows the front-end rules
| Rule | How |
|---|---|
| Vanilla JS, no inline handlers, no CDN | listeners are attached with `addEventListener`; `SuggestedResolutionMarkupTest` rejects `on...=` in the new section |
| Every dynamic value escaped | `escapeHtml` for text, `formatSuggestionScore` / `suggestionVoteCount` for numbers; the test rejects a raw `${s.field}` |
| In-flight guard | votes go through `apiCall`; promotion has its own flag because Enter pressed twice is not a click |
| Friendly refusals and refresh (ADR-039) | 409 already promoted / not resolved, 404, 403 each have a message; a refused write refreshes the open tab |
| Focus | the dialog uses `openModal`/`closeModal` (Tab trap, focus returns to the opener); vote buttons carry the `btn-tech-` prefix and the source id, so `restoreFocus` finds the redrawn twin |
| Late answers | `STATE.suggestions.requestSeq` discards an answer for a dialog that was closed or replaced |

### Tests (K4)
| Class | Tests | What |
|---|---|---|
| `SuggestedResolutionMarkupTest` | 8 | card buttons and the `promotable` rule; on-demand loading; late answers; escaping; writes through `apiCall` and the promote guard; friendly messages; focus prefix; styles, no inline handlers, version 3.9 |
| `KnowledgeArticleTest` | 19 (1 new) | `Incident.canBePromotedBy` follows the same rules as `promote` |
| `KnowledgeBaseRegressionTest` | 7 (1 new) | `promotable` over HTTP for the resolver, a team lead and an open ticket, in the queue, and absent from the employee view |
| six existing markup tests | changed on purpose | they pin the asset version, now 3.9 |

### Real-browser run (`scripts/live-checks/suggestions_browser.py`, Microsoft Edge, 24 checks)
Signs in through the real login form and checks: the buttons on open and resolved cards, the dialog content, a hostile
root cause (`<img ...>`) shown as text with no element created, "This worked" recorded and the dialog drawn again with
the new count, focus back on the vote button, Tab and Shift+Tab staying inside, Close returning focus to "Suggested
fixes", the empty answer, a late answer being discarded, Enter twice on Promote sending ONE request, the article
replacing the ticket in the suggestions, the friendly 409 message, the team lead's "Promote", an employee screen with
none of it, and no console error. `action_buttons_browser.py` (Phase J) still passes 33 of 33.

**Mutation checks.** Each made a check fail and was then undone:
- Front end (against the browser script):
  1. root cause not escaped;
  2. late-answer guard removed;
  3. promote guard removed;
  4. no redraw after a vote;
  5. suggestions offered on resolved tickets.
- Back end (against the tests):
  6. `promotable` ignores who the viewer is;
  7. the controller passes no viewer.

### What the live runs found
- A repeated run of the live checks failed: the open tickets of earlier runs, once resolved, are word-for-word clones of
  the new ticket B, so three of them tied at similarity 0.636 and filled the whole top 3. Printing the scores showed it.
  The product behaved as specified. The scripts now give A and B four words only that run uses and resolve the tickets
  they open. Four rounds in a row pass.
- It exposed a real limit: ranking has no diversification, so three near-identical past fixes show the same fix three
  times and can hide a slightly less similar one, including a knowledge-base article.

### Known gaps (K4)
- The controls are on technician cards only, not in the administrator's Command Centre.
- "Promote" stays visible after a promotion; the second click gets the friendly 409 message.
- Near-identical past fixes can fill the list (no diversification).
- One early browser run failed once on the first card lookup and did not reproduce in later runs (not explained).
- The browser run uses Microsoft Edge only; there is no JavaScript unit-test runner (structure is pinned as text).
- The API live checks need a few seconds after start-up before the seeded accounts accept a login.
- Not run: CI on JDK 21.

### Verification status (K4), 2026-11-08
| Check | Result |
|---|---|
| `mvn -o clean verify` | 1350 tests, 0 failures, JaCoCo gate met (89 % lines) |
| Every class in its own JVM (`mvn -o test -Djacoco.skip=true -DforkCount=1 -DreuseForks=false`) | **NOT finished**: it was started and stopped on request before it completed. Run it again before treating K4 as closed. |
| `suggestions_browser.py` (Edge) | 24 of 24, passed on two rounds in a row on one database |
| `action_buttons_browser.py` (Phase J) | 33 of 33 |
| `suggestions_check.py`, `suggestion_votes_check.py`, `knowledge_base_check.py` | 12 of 12 each, four rounds in a row on one database |
| App log during the live runs | no ERROR lines |
| CI on JDK 21 | not run |

