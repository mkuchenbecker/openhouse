# Applying review 16 to the commit-protocol appendices

Branch `claude/openhouse-commit-protocol-cl3xg9`, five commits pushed (shas at the end).
Every finding in `16-appendix-writing-review.md` is dispositioned below, plus the three
operator rulings.

## Per-finding disposition

### `appendix-d-rest-native-migration.md`

| Finding | Disposition |
|---|---|
| D1 `blocker` (requirements absent) | **Applied**, under Ruling 2. §2 states four musts, four shoulds, and six declared non-goals, each traced to the problem rather than to the design. See "The requirement set" below for why each must discriminates and what was dropped as decoration. |
| D2 `suggestion` (alternatives absent) | **Applied.** §3 is an options table with five rows and the must-requirements as columns; the four rejected options are developed in Appendix 1, including the `RESTCatalogAdapter` rejection the old §1.3 buried in a table cell. |
| D3 `blocker` (citations to nonexistent reports) | **Applied.** All nine citations resolved: "smell #3"/"report 01 §7" → appendix B finding 2; "smell #2" → finding 5; "smell #4" → finding 3; "smell #6" → finding 4; "report 03 §4.5" → finding 15; "report 03 §3.3" → appendix C §3.3; "report 02 §2" → appendix A §2; "per report 02" on the `:609` guard → dropped, the guard is stated directly. |
| D4 `blocker` (§6 specifies shipped work) | **Applied**, under Ruling 3. See "Prototype and estimate". |
| D5 `suggestion` (addressing the commissioner) | **Applied.** Cut "one honest correction of framing" (the paragraph is now the problem statement), "Yes — that is the acceptance criterion" (now must M-none: it became the client-story lead), "(feeds the follow-up implementation task)", "Key verified fact making this cheap", "the smallest honest proof". |
| D6 `suggestion` (§4.5 buried) | **Applied.** The bug-class-elimination argument is now §4.3, inside the sketch layer, immediately after the commit loop it depends on, and no longer numbered as a sixth phase. |
| D7 `suggestion` (bold-label lists that are tables) | **Applied.** The six policy hooks are a five-column table (§5.2). The phase material keeps prose per phase but each phase now carries its testing and rollback inline, and §5.6's cost table is adjacent with a lead sentence. "What stays exactly as-is" became a single sentence in §1 rather than three labelled bullets. |
| D8 `suggestion` (companion list omits appendix B) | **Applied.** Header line names appendix B as "the defects this design removes or inherits". |
| D9 `nit` (stray space) | **Applied.** |
| D10 `nit` (effort table lacks its point) | **Applied.** The table is introduced by the remaining total and by P2 as the dominant phase; the assumptions follow rather than lead. |

### `appendix-b-code-review.md`

| Finding | Disposition |
|---|---|
| B1 `blocker` (attribution to nonexistent reports) | **Applied.** All 29 "Found by:" lines now name reviewers descriptively ("the architecture reviewer alone", "all three reviewers", "the blind protocol reviewer alone"). The merges table's numbered inputs are gone; the ledger states counts and reviewer names. "The stated deployment posture", "the anchoring rule", and "principle 4" are replaced by the substance they pointed at. In-body pointers ("testing F7's pin test", "testing F1's two unit tests") restated inline. |
| B2 `suggestion` (production machinery) | **Partially applied per Ruling 1** — see below. |
| B3 `suggestion` (no scannable index) | **Applied.** A 29-row index table (number, tier, location, one-line claim) follows the file-abbreviation table. |
| B4 `suggestion` (tiering rationale stated twice) | **Applied.** The posture facts are stated once, in §1; the "Tiering posture facts" section is gone, and the per-finding tier arguments live in the ledger's ruling column. |
| B5 `nit` (record layout changes) | **Applied.** Findings 25-29 have "Action:" on its own line; the schema (evidence, attribution, action, failure scenario where the damage is not obvious) is now declared. |
| B6 `nit` ("the cells") | **Applied.** "thinnest exactly on the paths adjacent to the incident". |

### `appendix-e-tla.md`

| Finding | Disposition |
|---|---|
| E1 `suggestion` (manifest and feasibility verdict lead) | **Applied.** The document opens with the result; the artifact table became a configuration/result table with a lead sentence naming the two rows that matter; §1 is now "Why the protocol fits in a small model" and no longer answers a feasibility question. |
| E2 `suggestion` (recommends the built model) | **Applied**, see below. |
| E3 `suggestion` (bold-label recommendation list) | **Applied.** §7 is a four-column table (extension, value, effort, verdict); the rename item is lifted out of it entirely because it is no longer a recommendation; the regression-spec sentence is a closing sentence, not a row. |
| E4 `nit` ("see §6") | **Applied.** Now §5. |
| E5 `nit` (prediction; wrong large-run figure) | **Applied and corrected against the logs.** The pre-run prediction is cut. Verified figures: pre-fix 223/103 complete depth 11; post-fix 221/99 complete depth 11; post-fix large 11,536/4,197 complete depth 20; **pre-fix large 389 generated / 218 distinct with 119 states left on queue, depth 7** — stated as an incomplete graph because the violation halts the search; shared-cache 105/45. |
| E6 `nit` ("honestly", "bonus", "honest topology") | **Applied.** §5's heading is "The dedup cache masks the bug on a single JVM"; the abstraction list and the topology sentence state their content plainly. |
| E7 `nit` (appendix B cited by description) | **Applied.** Now "finding 5" and "finding 13". |

### PR mkuchenbecker/openhouse#35

| Finding | Disposition |
|---|---|
| P1 `suggestion` (summary is an inventory) | **Applied.** The summary is three numbered claims a reviewer can act on; the file inventory moved under "Changes". |
| P2 `suggestion` (`docs:` understates) | **Applied.** Title is now `feat(tables): REST-native commit prototype, with the commit-protocol analysis behind it`. |
| P3 `suggestion` (unlinked companion PR) | **Applied.** "Related: #36 carries the TLA+-driven fix for the CAS-exempt rename path". |
| P4 `nit` ("the CAS trio" coinage) | **Applied.** The mechanism is spelled out: full snapshot list, subtractive merge, three string comparisons of the previous metadata.json path. |
| P5 `nit` (8 cases versus 15 tests) | **Applied.** The 15 tests are split explicitly into the design matrix's 8 cases and the request-validation cases the matrix did not enumerate. |
| P6 `nit` (generated-by footer) | **Declined per instruction.** Exactly one footer plus the session URL is retained. |

Also added to the PR body, not from the review: a "Reviewers should know" line stating that the prototype route carries no operation-level authorization. The review's own P2 failure scenario turns on that fact, and a reviewer cannot verify it from the diff without reading the service.

## Ruling 1 — appendix B adjudication log: partial decline recorded

B2 asked for roughly a fifth of the document to go. **Partially declined per the operator ruling.** Retained: the adjudication log (the protocol's required deliverable, and a ledger is where history belongs) and the blind-vs-briefed convergence figure (the evidence that justifies trusting the blocking tier). It is now a compact ledger: one table of the eleven findings whose tier or shape was contested, with columns for reviewers, inputs merged, and the contest and ruling; one table of the three evidence corrections; one short convergence section.

Cut as instructed: the "Generated by the synthesis-review procedure" footer; "This synthesis re-verified all 44 findings…"; "All 44 findings verified; none were killed"; "the strongest corroboration this review produced"; "Protocol F15 was a no-defect contract note" as a process aside (its content is now a plain statement); the free-standing "Verification", "Merges", "Disagreements and rulings", and "Tiering posture facts" subsections, whose substance is in the ledger.

## Ruling 2 — the requirement set for appendix D

The reviewer's drafted tiers were used as a starting point and then tested one by one. What survived:

| Must | Grounded in | What it discriminates |
|---|---|---|
| M1 deletion requires naming | the loss mechanism itself (appendix A §2, protocol.md §5) | rejects "harden in place" and "do nothing" |
| M2 no exempt write path | rename and replace surviving fix #612 (appendix B findings 3, 14; appendix A §3) | **rejects the recommendation itself** — REST adoption leaves rename outside the commit route |
| M3 policy gates keep their hook at the commit point | the gates exist only in the Spring service layer today | rejects the off-the-shelf REST catalog option |
| M4 HTS `@Version` CAS stays sole arbiter, no schema/layout/migration | protocol.md §4: the atomicity argument rests on that row | rejects the off-the-shelf option a second way |

Dropped as decoration or demoted: "legacy clients keep committing unmodified, per-table rollback" is satisfied identically by every option that keeps the legacy endpoints, so it is stated as a constraint on the plan and is **not** a table column. "Re-enable server-side retry", "spec-conformant unknown-state rendering", "requirement-scoped conflict granularity", and "idempotent answer to ambiguous retries" are shoulds, not musts — none of them is defensible from the #612 class as a hard requirement.

**The honest set does not uniquely select REST adoption**, so the recommendation is conditional, as the ruling directs. M1 rejects (b) and (e); M3 and M4 reject (d); (a) REST adoption and (c) server-side derivation of typed updates from the writer's declared base both satisfy M1 and both fail M2 the same way. The deciding criterion between them is a should — retiring the bespoke client protocol and its five client-side findings — so §3 says so and states the trade: (c) reaches M1 with no client change at all, in a fraction of the effort and without the client-version-skew risk, and is the better decision if the retirement commitment is not made. Two further honest admissions are recorded rather than smoothed over: S4 (idempotency) is **not met** by the recommendation because the REST spec shares the gap, and M2's rename hole is marked in the options table and again in §5.3 row 11.

Option (c) is not invented for the table. The document already computes exactly that mapping in Phase 2's shadow validation ("compute the equivalent `(requirements, updates)` server-side from (base, payload)"); the option makes it the commit path instead of a checker.

## Ruling 3 — prototype and estimate

§5.7 now describes the prototype as delivered, read from the code, not from the old task brief: the real route `POST /v1/rest/namespaces/{namespace}/tables/{tableId}/commit`, the five real classes (`IcebergRestCommitController`, `IcebergRestCommitService` with its local `ValidationFailureException`, `RestUpdateValidator`, `IcebergRestExceptionHandler`, `IcebergRestSerde` — not the drafted `IcebergRestJacksonConfig`), and the 35 tests in their real homes (15 in `…/e2e/h2/IcebergRestCommitControllerTest`, 14 in `…/resthandler/RestUpdateValidatorTest`, 6 in the internal-catalog `RestNativeCommitOperationsTest`).

Five things are named as unbuilt: operation-level authorization (the token interceptor covers the route at `TablesMvcConfigurer.java:44`, but no OPA privilege classification runs in the service), the per-table feature toggle, the spec-exact route and the read plane, MySQL HTS certification (so the design's own exit criterion is not fully met), and everything marked Phase 2 or later.

**Restated estimate: 13-22 engineering-weeks remain of the 15-25 originally scoped.** The cost table marks P0 "delivered (scoped 2-3)" and totals the remainder; the row for spent effort states what was budgeted, not an invented actual. Nothing was silently shrunk: P1 through P4 keep their original ranges.

## Contradictions found and where they were fixed

1. **Estimate.** README quoted "15–25 engineering-weeks" forward from appendix D §5. Fixed in **README** (claim 4), which now states 13-22 remaining of the original 15-25 with the prototype phase delivered. Appendix D is the source of the figure and was fixed first.
2. **Recommendation strength.** README asserted REST adoption flatly; appendix D now recommends it conditionally. Fixed in **README**, which names both routes to server-side authorship and the condition attached to the REST one.
3. **Rename-model status.** README already stated the present ("a prediction an extended model has since confirmed"); **appendix E** §7 was the stale document, recommending the model as future work. Fixed in appendix E: the model exists at `specs/tla/` on branch `claude/tla-driven-commit-fixes`, the unguarded configuration yields a 7-step `NoSnapshotLoss` counterexample at 183 distinct states, and the guarded model holds across 6,130 distinct states.
4. **Prototype status.** README claim 4 and its reading-guide row were correct; **appendix D** was the stale document. Fixed there.
5. **Terminology.** Appendix B now says "reviewer" throughout while README said "expert". Fixed in **README** (cosmetic, own commit).

Checked and found consistent, no change needed: protocol.md §5 and §7 (which describe appendix D as *evaluating* the move and remain accurate under a conditional recommendation), appendix C §4 and §7, appendix A throughout, the 29/9 finding counts, and every relative link.

## Pushed commits

| sha | Subject |
|---|---|
| `402da190` | docs: restructure appendix D as a checkable decision record |
| `eca36c8c` | docs: make appendix B usable without its source reports |
| `ee23776d` | docs: lead appendix E with its result and record the rename model |
| `2b4dafee` | docs: align README with the restated estimate and recommendation |
| `eed502f6` | docs: match README review wording to appendix B |

All five carry the required `Co-Authored-By` and `Claude-Session` trailers and are pushed to
`claude/openhouse-commit-protocol-cl3xg9`. PR #35's title and body were updated through the
GitHub API. No model name appears in any document or in the PR text.
