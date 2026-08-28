# Writing review: commit-protocol appendices D, B, E, and PR #35

Reviewer: `writing-review`. Criteria: STRUCTURE.md rules 1-8 and the SKILL.md
sentence-pattern catalog (local copies at `/home/user/mkuchenbecker/humanizer/`),
plus DESIGN-DOCS.md for appendix D. Both rule sets were in context for both passes;
no degraded pass.

Counts: **appendix D — 3 blocker, 5 suggestion, 2 nit. appendix B — 1 blocker,
3 suggestion, 2 nit. appendix E — 0 blocker, 3 suggestion, 4 nit. PR #35 — 0 blocker,
3 suggestion, 3 nit.**

House-style calls made before the passes, so they are not reported as defects: em
dashes (used at high rate across every document in the set, including the already
reviewed `protocol.md` and appendix A, so the corpus is the writer's sample — SKILL.md
§14 guardrail); title-case document titles (consistent set-wide); and appendix B's
finding records with named fields, whose cells carry paragraphs of argument and so
belong in prose rather than a table under STRUCTURE.md rule 2.

---

# Part 1 — Design-doc genre assessment: `appendix-d-rest-native-migration.md`

## Verdict

The document is a research report wearing a decision record's title. It gets the
single hardest genre requirement right — the recommendation is the first thing on the
page, in §0, stated as a disputable claim — and then fails the requirement that makes
that recommendation checkable: **there are no requirements and there are no options.**
The reader is handed a verdict, a very detailed implementation plan for that verdict,
and no way to test whether the verdict follows from anything. DESIGN-DOCS.md grades
"requirements absent" a `blocker` precisely because it turns a decision record into
advocacy, and that is what has happened here: §1 through §6 are 240 lines elaborating
one option that was never compared to another.

Section-by-section against the six genre sections:

| Genre section | Present? | What fills it |
|---|---|---|
| 1. Problem statement | Partly | §0's "Correction of framing" paragraph carries it (client is authoritative over metadata content; the server merges subtractively; this is the #612 bug class). It is framed as a correction of a prior framing rather than as the problem, so it reads as commentary on an earlier document rather than as the reason this one exists. |
| 2. Requirements (must/should/won't/out of scope) | **Absent** | Nothing anywhere states criteria. The material exists, scattered: §0 "What stays exactly as-is", §1.4's six policy hooks, §3's "that is the acceptance criterion", §5's assumptions. None of it is committed before the recommendation. |
| 3. Options with a recommendation, as a table | **Absent** | §1.3 is a table, but its rows are iceberg-core *components* (reuse / adapt / do not use) and its columns are verdict and notes. It is a build-vs-reuse inventory inside the chosen option, not a comparison of options, and its columns are not the requirements. |
| 4. Sketch | Present | §1.1 (wire contract) plus the §1.2 pseudocode block is a good one-screen sketch of the chosen design. |
| 5. Details | Present, oversized | §1.4, §2, §4, §6. §2's 11-row gap table is the strongest section in the document. |
| 6. Appendix | Present | "Appendix: primary code anchors". |

Defects the genre file names, at the severity it assigns:

| DESIGN-DOCS.md defect | Applies? | Severity assigned |
|---|---|---|
| Recommendation absent, buried, or only at the end | No — §0 leads with it | — |
| Options before requirements, or requirements absent | **Yes, requirements absent** | `blocker` (finding D1) |
| No "won't" / out-of-scope statements | **Yes** | folded into D1 (the genre file grades this `suggestion` standalone; here it is part of the missing requirements tier) |
| Criteria columns don't match stated requirements | Vacuously — no columns, no requirements | folded into D1 |
| Rejected alternative elaborated in the main line | No | — |
| Alternatives absent entirely | **Yes** | `suggestion` (finding D2) — the genre file grades this `nit` only when the change is small; at 15-25 engineering-weeks it is not |

## Proposed section ordering

The content needed for a compliant decision record is almost all present; it is in the
wrong order and two sections are unwritten. Proposed ordering, naming what already
fills each slot:

1. **Problem.** Promote the second half of §0 ("Correction of framing") to open the
   document, restated as the problem rather than as a correction: the client is
   authoritative over the *content* of the next metadata, ships the full snapshot list
   as opaque JSON through table properties, and the server merges it subtractively, so
   an append can delete. Cite #612 (appendix A) and the appendix B findings that share
   the carrier. Drop the "correction" framing entirely — see D3.
2. **Requirements** — *this section does not exist and must be written.* The document
   implies the following; each line below names where the implication comes from, so
   the section is a promotion, not an invention:
   - **Must**: eliminate client authority over absolute snapshot state so that an
     append has no vocabulary to delete (§4.5); keep the HTS row `@Version` CAS as the
     single atomic arbiter, unchanged (§0 "What stays exactly as-is"); a stock Iceberg
     `RESTCatalog` at both 1.2 and 1.5 must commit without OpenHouse code (§3 calls
     this "the acceptance criterion" — that is a must, written in the wrong section);
     preserve every OpenHouse policy gate (lock state, preserved `openhouse.*` and
     `policies` keys, authz classification, per-table feature toggle) (§1.4); require
     no data migration and no change to metadata.json naming, storage layout, or HTS
     schema (§0, §4 Phase 2 rollback note); legacy clients must keep committing
     unmodified for the whole window, with per-table rollback (§3, §4).
   - **Should**: re-enable server-side retry for store-level races (§1.2); render
     unknown-commit-state with the spec's 500/502/504 rather than 503 (§2 row 8);
     answer ambiguous client retries idempotently (§5 R5).
   - **Won't / out of scope**: HTS schema or storage-layout change; multi-level
     namespaces (§1.1 rejects them with 400); ACL and policy DDL expressed as
     `TableUpdate`s (§1.4.1 says explicitly it "should not be"); multi-table
     `/v1/transactions/commit` (§1.1 defers it to "phase-2+ optional"); the HTS rename
     CAS bug (§2 row 11 says "tracked separately"); OAuth2 credential flow and
     credential vending (§1.1, §3). All six are stated somewhere as asides; none is
     declared a non-goal, so each will be relitigated in review as an oversight.
3. **Options with the recommendation, as a table** — *this section does not exist and
   must be written.* Rows: (a) REST-catalog-native commit, the recommendation;
   (b) harden the existing CAS trio in place and ship the appendix B blocking fixes,
   keeping the proprietary wire contract; (c) move metadata authorship to the server
   over the *existing* wire contract — a typed internal delta API with no REST
   adoption, which gets the #612 elimination without the client-migration cost;
   (d) adopt an off-the-shelf REST catalog implementation in front of HTS — §1.3
   dismisses `RESTCatalogAdapter` in a single table cell, which is the only trace of
   this option anywhere; (e) do nothing. Columns: the must-requirements from section 2.
   Mark the recommendation and state the deciding criterion in the accompanying
   sentence — on the evidence in the document that criterion is "only (a) and (c)
   eliminate the bug class structurally, and only (a) also retires the bespoke client".
4. **Sketch.** §1.1 plus §1.2, unchanged. This already works.
5. **Details.** §1.3, §1.4, §2, §4 (phases), §5 (estimate and risks). §4.5 belongs at
   the head of this layer or in the problem section, not buried mid-plan (finding D6).
6. **Appendix.** The existing code-anchor appendix, plus the developed form of the
   rejected options from section 3 — §1.3's `RESTCatalogAdapter` rejection is appendix
   evidence, not a table cell in the main line.

§6 ("Prototype scope") does not belong in any of the six slots, because it is a task
brief for work that has already shipped on this branch. See finding D4.

---

# Part 2 — Findings

## `appendix-d-rest-native-migration.md`

### D1 — `blocker`

| Field | Content |
|---|---|
| location | Whole document; the gap sits between §0 (line 9) and §1 (line 23) |
| principle | DESIGN-DOCS.md, "Options presented before requirements, or requirements absent" |
| claim | The document commits to a recommendation and 240 lines of implementation without ever stating the criteria the recommendation is supposed to satisfy, so nothing in it is checkable. |
| evidence | No must/should/won't/out-of-scope tier appears anywhere. The criteria exist but are scattered downstream of the decision and phrased as asides: §3 line 116 "Yes — that is the acceptance criterion"; §0 lines 15-19 "What stays exactly as-is"; §1.4's six policy hooks; §1.1 line 37 "Multi-level namespaces are rejected with 400"; §1.4.1 "it is not expressible as a `TableUpdate` and should not be"; §2 row 11 "tracked separately"; §1.1 "phase-2+ optional". Every one of those is a requirement or a non-goal stated after the choice it was supposed to constrain. |
| failure scenario | A staff engineer asked to approve a 15-25 engineering-week program reads §0, agrees the diagnosis is right, and then has no way to test the leap from "move metadata authorship to the server" to "adopt the Iceberg REST catalog wire contract". They either rubber-stamp it or reconstruct the criteria themselves in review comments. A reviewer who objects that multi-table transactions or policy DDL were not handled gets pointed at a parenthesis, and the scope argument reopens every time a new reviewer arrives. |
| severity | blocker |
| confidence | confirmed |

### D2 — `suggestion`

| Field | Content |
|---|---|
| location | §1.3 (lines 69-81); absence spans the whole document |
| principle | DESIGN-DOCS.md, "Alternatives absent entirely"; STRUCTURE.md rule 2 (a table comes with the sentence stating what it decides) |
| claim | No alternative to REST adoption is developed anywhere, and the one table that occupies the options slot compares components of the chosen design rather than options. |
| evidence | §1.3's columns are "Component / Verdict / Notes" over `MetadataUpdate`, `UpdateRequirement`, `CatalogHandlers.commit`, `RESTCatalogAdapter`, `OpenHouseInternalTableOperations` — an internal build-vs-reuse inventory. The only alternative mentioned in the entire document is `RESTCatalogAdapter`, rejected inside a table cell: "**Do not use** — OpenHouse is Spring MVC; write thin `@RestController`s". The obvious competitors are unmentioned: hardening the existing CAS trio plus the appendix B blocking fixes; server-side metadata authorship over the existing wire contract without REST adoption. §1.3 also has no sentence naming which row matters most. |
| failure scenario | A reviewer who believes the cheaper option (c) also kills the bug class has no developed comparison to argue against, so the objection lands in review as a question rather than in the document as a rejected alternative — and gets re-asked by the next reviewer. DESIGN-DOCS.md grades this `nit` only when the change is small enough not to warrant alternatives; a five-phase, 15-25 engineering-week program is not that. |
| severity | suggestion |
| confidence | confirmed |

### D3 — `blocker`

| Field | Content |
|---|---|
| location | §1.2 line 62; §1.4 line 91; §2 rows 5, 6, 7, 9, 10, 11 (lines 104-110); §4.5 line 168 |
| principle | STRUCTURE.md rule 4 (the document stands on its own — undefined insider terms) |
| claim | Nine load-bearing citations point at "report 01", "report 02", "report 03" and at numbered "smells" that exist in no document in this repository. |
| evidence | "guard on `SNAPSHOTS_JSON_KEY` at `:609` per report 02"; "the non-atomic rewrite (smell #3, report 01 §7) is deleted"; "keyed on base path — smell #2, report 01 §7"; "idempotency ... optionally improved by server-side `AddSnapshot` snapshot-id dedupe (spec gap, report 03 §4.5)"; "`processSchemas` unwind in doCommit (`:686-718`, swallow-bug smell #6)"; "stage-only is native, report 03 §3.3"; "non-CAS JPQL ... — smell #4"; "racing snapshot `S_r` computed as 'to remove' (report 02 §2)". A repo-wide grep finds these strings only in this file. The document's own header names its companions as protocol.md, appendix A and appendix C, and never links appendix B, which is where the "smell" findings actually surfaced (smell #3 is appendix B finding 2, smell #2 is finding 5, smell #4 is finding 3, smell #6 is finding 4). |
| failure scenario | An implementer picking up §2 row 7 wants to know what "smell #3" was and why the rewrite is non-atomic; there is nothing to open. The citation reads as a promise of evidence that the repository cannot keep, which devalues the citations that do resolve — and this document's whole method is citation. |
| severity | blocker |
| confidence | confirmed |

### D4 — `blocker`

| Field | Content |
|---|---|
| location | §4 "Phase 0 — Prototype" (lines 135-137) and §6 (lines 206-236) |
| principle | STRUCTURE.md rule 8 (state the present; history and process go to the ledger); none (internal consistency) for the contradiction |
| claim | §6 specifies as future work a prototype that already exists on this branch, and §4 bills it as an unstarted 2-3 engineering-week phase, contradicting README.md, which says the prototype ships in this same change set. |
| evidence | §6 is written as an assignment: heading "Prototype scope (feeds the follow-up implementation task)", subheadings "Classes to add", "Test matrix (must pass)", "Exit criterion". The three classes it specifies exist at `services/tables/src/main/java/com/linkedin/openhouse/tables/resthandler/` (`IcebergRestCommitController.java`, `IcebergRestCommitService.java`, `RestUpdateValidator.java`, plus two more), and the controller's `@PostMapping` is the exact route §6 proposes, `/v1/rest/namespaces/{namespace}/tables/{tableId}/commit`. The three test classes exist. README.md line 39 says "A working prototype of that commit path is included in the same change set as this document ... passing a 35-test matrix". Meanwhile §5's cost table still bills P0 at "**2-3**" engineering-weeks inside a "**15-25 eng-weeks**" total that README quotes forward as the estimate. Two smaller drifts confirm §6 was never reconciled with the code: it names a class `IcebergRestJacksonConfig.java` (shipped as `IcebergRestSerde.java`) and places controller tests in `.../resthandler/` (shipped in `.../e2e/h2/`). |
| failure scenario | An engineer assigned "Phase 0" from this document writes classes that already exist. A manager sizing the program adds 2-3 weeks of delivered work to the budget. A reader who reaches §6 after README's claim 4 cannot tell which document is describing the present. |
| severity | blocker |
| confidence | confirmed |

### D5 — `suggestion`

| Field | Content |
|---|---|
| location | §0 heading (line 9); §3 line 116; §6 heading (line 206); §1.2 line 62; §6 line 208 |
| principle | STRUCTURE.md rule 4 (echoing the assignment, referencing the conversation); SKILL.md §33 (fake-candid framing) |
| claim | The document repeatedly addresses the person who commissioned it rather than the reader, framing its content as answers, corrections and deliverables in a workflow the reader cannot see. |
| evidence | "## 0. Headline decision and **one honest correction of framing**" — a reader outside the room has no prior framing to correct, and "honest" claims a virtue rather than stating a fact; the paragraph's actual content (the client is authoritative over metadata *content*, not over the file write) is the problem statement and should say so directly. "**Can stock Iceberg REST clients talk to it when done? Yes — that is the acceptance criterion.**" poses and answers a commissioner's question where a requirement belongs. "## 6. Prototype scope **(feeds the follow-up implementation task)**" names a position in a task queue. "**Key verified fact** making this cheap" and "the smallest **honest** proof of the core loop" foreground that the author checked rather than what is true. The same word recurs in appendix E ("What was abstracted away (honestly)", "the honest production topology"), which marks it as a habit rather than a one-off. |
| failure scenario | A reader outside the originating conversation spends attention deciding what framing is being corrected and whose task §6 feeds, and discounts a document that keeps signalling its own diligence — the more so because D3 shows some of its citations do not resolve. |
| severity | suggestion |
| confidence | confirmed |

### D6 — `suggestion`

| Field | Content |
|---|---|
| location | §4.5 (lines 166-175), under "## 4. Phased migration plan" |
| principle | STRUCTURE.md rule 7 (each layer expands the one above and introduces no new conclusions); rule 1 (conclusion on top, at every scale) |
| claim | The document's central justification — why the proposal eliminates the #612 bug class by construction — first appears at the bottom of the implementation-plan section, and is numbered as if it were a sixth migration phase. |
| evidence | The four-point argument at lines 168-175 ("An append literally has no vocabulary to delete `S_r`"; requirements validated against fresh state at the commit point; the silent-rebase machinery ceases to exist; the HTS CAS still backstops) is the reason to do any of this. §0 gestures at it once, in a parenthesis: "That is the structural change that eliminates the #612 bug class (§4.5)". Numbering compounds the misplacement: §4's children are "Phase 0" through "Phase 4", then "4.5", so the section reads as a phase that comes after Phase 4. The invariant test defined at line 175 is likewise a requirement, stated in the details layer. |
| failure scenario | A reviewer who reads §0 and skims §1-§2 to judge whether the migration is worth 15-25 weeks never reaches the argument that makes it worth it, and evaluates the proposal as a protocol-modernization project rather than as a bug-class elimination. |
| severity | suggestion |
| confidence | confirmed |

### D7 — `suggestion`

| Field | Content |
|---|---|
| location | §0 lines 15-19; §1.4 lines 85-92; §4 lines 135-164 against §5 lines 183-190 |
| principle | STRUCTURE.md rule 2 (context-heavy lists are tables refusing to declare their columns); SKILL.md §16 |
| claim | Three passages are tables typeset as bold-label lists, and the phase material is split across two structures that share a key. |
| evidence | §0's "What stays exactly as-is" is three bullets each carrying (component, why it stays, code anchor). §1.4's six numbered hooks each carry (policy, where it hooks, enforcing code, target behaviour) in running prose behind a bold label — the densest and least scannable passage in the document, and the one an implementer needs most. §4's phases each carry a scope paragraph plus bold-labelled "**Testing**:" and "**Rollback**:" fields, while §5's table carries (phase, scope, eng-weeks) for the same five phases: one reader question ("what happens in Phase 2 and what does it cost") requires reading two structures thirty lines apart. §1.4's numbering is right (rule 3 — the items are referenced as "§1.4.1" style addresses elsewhere), so the fix is columns, not de-numbering. |
| failure scenario | An implementer auditing that every current policy gate has a home in the new path cannot scan one column down six hooks; they read six paragraphs and keep their own notes. |
| severity | suggestion |
| confidence | confirmed |

### D8 — `suggestion`

| Field | Content |
|---|---|
| location | Header line 5 (companion documents) |
| principle | STRUCTURE.md rule 4 (the document stands on its own) |
| claim | The companion-document list omits appendix B, which the document depends on for the defects it claims to remove. |
| evidence | Line 5 names protocol.md, appendix A and appendix C. §2 rows 5, 6, 7, 11 and §1.4.5 cite the "smells" that appendix B adjudicates as findings 5, 4, 2 and 3, and §2 row 11's "tracked separately" refers to what appendix B grades a blocking finding. Nothing links there. |
| failure scenario | A reader wanting the evidence that the deleted machinery is genuinely defective is sent to the nonexistent "report 01" (D3) instead of to the appendix that carries it, five files away in the same directory. |
| severity | suggestion |
| confidence | confirmed |

### D9 — `nit`

| Field | Content |
|---|---|
| location | Line 5 |
| principle | none (typography) |
| claim | Stray space after an opening parenthesis. |
| evidence | "an apache/iceberg checkout ( 1.5.2.x line, matching ..." |
| failure scenario | None beyond polish. |
| severity | nit |
| confidence | confirmed |

### D10 — `nit`

| Field | Content |
|---|---|
| location | §5 lines 183-190 |
| principle | STRUCTURE.md rule 2 (a table never appears without a sentence stating what it shows and which entry matters most) |
| claim | The effort table is introduced by its assumptions rather than by its point. |
| evidence | The preceding paragraph is entirely caveats ("Assumptions: 1-2 engineers ... estimates are engineering effort, excluding org-wide client-rollout calendar time"). These are genuine conditions that would change the number, so they belong in the main line, but the table still lacks the sentence naming its dominant row — P2 at 5-8 weeks is a third of the program and the risk register's centre of gravity, and nothing says so. |
| failure scenario | A reader budgeting the program has to derive the critical phase from the arithmetic. |
| severity | nit |
| confidence | probable |

---

## `appendix-b-code-review.md`

### B1 — `blocker`

| Field | Content |
|---|---|
| location | Every finding's "Found by:" line (lines 44, 50, 56, 62, 69, 76, 83, 89, 95, 103, 109, 115, 121, 127, 133, 138, 143, 148, 153, 158, 163, 168, 173, 178, 183, 187, 191, 195, 199); §3 "Merges" table (lines 219-232); lines 13, 103, 239 |
| principle | STRUCTURE.md rule 4 (undefined insider terms; the document stands on its own) |
| claim | Every one of the 29 findings, and the entire merge table, is addressed to three source reports and a rulebook that do not exist in the repository. |
| evidence | "Found by: arch #1, protocol F1 and F8, testing F4"; "Found by: testing F3 (part a) and F10 (folded here per its own recommendation)"; the Merges table maps "1 | arch #1 + protocol F1 + protocol F8 + testing F4" across nine rows and ends "6, 7, 9, 10, 13-21, 23-29 | single-source (see per-finding attribution)". Three further references point at an unnamed governing document: "Tier semantics for this appendix, per **the stated deployment posture**" (line 13); "verified hardest per **the anchoring rule**" (line 103); "Tiered follow-up on proportion (**principle 4**)" (line 239). None of arch, protocol, testing, the anchoring rule, or principle 4 exists in this repository. |
| failure scenario | An engineer triaging finding 11 wants to know what the blind reviewer actually saw that the architecture reviewer did not — "protocol F7 (the refs-wipe default is the blind expert's addition)" promises a source and delivers nothing. Worse, the tiering of findings 4, 5, 10 and 11 is justified *by* those documents ("Adjudication of the severity spread (arch: suggestion; blind: major; testing: blocker)"), so a reader who disagrees with a blocking tier cannot check the reasoning. The document cannot be used without its author, which is the definition of the blocker bar. |
| severity | blocker |
| confidence | confirmed |

### B2 — `suggestion`

| Field | Content |
|---|---|
| location | §3 "Adjudication log" (lines 203-249); trailing italic line 252; line 5 |
| principle | STRUCTURE.md rule 8 (state the present; production history goes to the ledger) |
| claim | Roughly one fifth of the document describes how the review was produced rather than what is wrong with the code. |
| evidence | §3 comprises "Verification" (a table of three corrections made to input findings nobody can read), "Merges" (44 inputs to 29 outputs), "Disagreements and rulings" (six paragraphs of who graded what and who was ruled with), "Tiering posture facts" (a restatement of line 13), and "Blind-vs-briefed convergence" — a paragraph of process metrics: "independently reproduced 8 of the architecture expert's 18 findings ... independently hit 7 of the 10 pre-flagged smells ... Roughly 55% ... and 100% at the most-severe tier". The document closes with generation machinery: "*Generated by the synthesis-review procedure: per-finding verification against `2a9dac8`, duplicate merging with source attribution, disagreement adjudication, and posture-based tiering. Expert inputs: architecture review (18 findings, briefed), testing review (12 findings, briefed), blind protocol-correctness review (14 findings + 1 note...)*". Line 5's "**All 44 findings verified; none were killed**" carries the same procedural vocabulary into the summary. Line 11's "the strongest corroboration this review produced" is the review appraising itself. |
| failure scenario | An engineer who opens this file to fix finding 3 pays for four screens of provenance accounting that changes nothing about the fix, and a reader deciding whether to trust the tiers is offered a reproduction percentage instead of the evidence. The claim the convergence paragraph is really making — that the top three findings are corroborated by an independent reviewer — is already made once, in one sentence, at line 11. |
| severity | suggestion |
| confidence | confirmed |

### B3 — `suggestion`

| Field | Content |
|---|---|
| location | §2 (lines 37-199) |
| principle | STRUCTURE.md rule 2 (items carrying the same attributes become a table); rule 1 (conclusion on top, at document scale) |
| claim | Twenty-nine findings arrive as an undivided prose run with no scannable index, and only three of them are named before the reader reaches it. |
| evidence | §1 names findings 1, 2 and 3, then §2 runs 160 lines with no summary table. Every finding carries the same four fields (tier, source tags, code location, one-line claim) that a reader triaging needs and that the bold headers already encode: "**Finding 12 — [arch+protocol+testing][follow-up] Unknown-outcome commits render inconsistently ...**". The set has exactly the shape rule 2 names — same attributes on every item — even though the finding *bodies* are correctly prose. The file-abbreviation table at lines 17-31 shows the author knows the form. |
| failure scenario | A tech lead deciding what to schedule this quarter needs "the nine blocking findings and where each lives" and must build that list by hand from 160 lines. |
| severity | suggestion |
| confidence | confirmed |

### B4 — `suggestion`

| Field | Content |
|---|---|
| location | Line 13 versus lines 243-245 |
| principle | STRUCTURE.md rule 7 (each layer expands the one above and introduces no new conclusions) |
| claim | The tiering rationale is stated twice, in full, in two layers. |
| evidence | Line 13: "per the stated deployment posture (production Iceberg control plane, correctness-critical path, recent silent-data-loss incident #612 fixed at this commit, multiple replicas behind a load balancer, Spark clients with Iceberg retry machinery, a later fix costs a release cycle)". Lines 243-245 restate the same six facts at greater length under "Tiering posture facts", now attached to finding numbers. The second version carries the information the first should have; the first carries a pointer ("the stated") to a document that does not exist. |
| failure scenario | A reader who accepted the tier semantics at line 13 reads them again at line 243 and has to check whether anything changed. |
| severity | suggestion |
| confidence | confirmed |

### B5 — `nit`

| Field | Content |
|---|---|
| location | Lines 183, 187, 191, 195, 199 versus lines 45, 51, 57, 64 |
| principle | STRUCTURE.md rule 2 (a schema declared once, applied consistently) |
| claim | The finding record's field layout changes partway through the document. |
| evidence | Findings 1-24 put "Action:" on its own line. Findings 25-29 run it into the "Found by:" line: "Found by: arch #15. Action: gate stack-trace fields behind a debug flag". Findings 16-19 and 22 drop "Failure scenario:" entirely; findings 25-29 drop it too, which is defensible for small items but is never declared. |
| failure scenario | A reader scanning for actions loses the anchor in the last five entries. |
| severity | nit |
| confidence | confirmed |

### B6 — `nit`

| Field | Content |
|---|---|
| location | Line 5 |
| principle | STRUCTURE.md rule 4 (undefined insider terms) |
| claim | The summary's central image uses an undefined term. |
| evidence | "the test suite is thinnest exactly in **the cells** adjacent to the incident it was hardened for". No matrix, grid or cell has been introduced at that point; the reader infers a test-coverage matrix that the document never draws. |
| failure scenario | The sentence carrying one of the document's four headline claims is the one sentence a first-time reader has to re-read. |
| severity | nit |
| confidence | probable |

---

## `appendix-e-tla.md`

### E1 — `suggestion`

| Field | Content |
|---|---|
| location | Lines 3-14 (opening) and §1 heading (line 18) |
| principle | STRUCTURE.md rule 1 (conclusion on top; a conclusion is a claim someone could dispute or act on); rule 2 (a table comes with the sentence stating what it shows); rule 4 (echoing the assignment) |
| claim | The document opens with an artifact inventory and then answers a commissioning question ("is modelling feasible?") in the position where its actual result belongs. |
| evidence | The first line of prose is "Artifacts (all under `tla/`; re-run with ...)" — a file manifest. The result is present, but only inside table cells ("Pre-fix, 2 writers — **NoSnapshotLoss violated** (bug #612 trace)"; "Post-fix, same constants — **all invariants hold**"), with no sentence stating what the table shows or which row matters most. The first section is then "## 1. Feasibility verdict: YES — and cheap", followed by "Tractability was never in question" and a state-space budget. Feasibility is a question the commissioner asked; the repository reader's question is what the model proves, which is answered in §3 and §4. §7's "Recommendation on deeper modeling" (whether to invest further) is the same commissioned-study shape at the other end. |
| failure scenario | An engineer who opens appendix E from README's claim 2 wants one sentence — the pre-fix model violates `NoSnapshotLoss` by exactly the #612 interleaving, and the post-fix model checks clean over the same state space — and instead gets a manifest, a feasibility verdict, and a state-count estimate before reaching it in §3. A reader who stops early leaves believing the document is about whether TLA+ was worth trying. |
| severity | suggestion |
| confidence | confirmed |

### E2 — `suggestion`

| Field | Content |
|---|---|
| location | §7 first bullet (b), line 102 |
| principle | none (internal consistency); STRUCTURE.md rule 8 (state the present) |
| claim | The document recommends as future work a model extension that README says has already been built and has already confirmed its prediction. |
| evidence | §7: "(b) model the **rename vs. commit race** ([Appendix B] blocking finding 3) — the JPQL rename bypasses `@Version`, and the same model skeleton **would likely find** a lost-update counterexample there, i.e. a *new* bug candidate". README.md line 19-21: "[Appendix E] names the CAS-exempt rename path as the likely next counterexample of the same class, **a prediction an extended model has since confirmed** (the fix ships separately as its own draft pull request)". |
| failure scenario | A reader arriving from README to see the confirmed counterexample finds a proposal to look for it, and cannot tell whether the extended model exists, where it lives, or whether the rename bug is confirmed or conjectured. |
| severity | suggestion |
| confidence | confirmed |

### E3 — `suggestion`

| Field | Content |
|---|---|
| location | §7 (lines 100-105) |
| principle | STRUCTURE.md rule 2 (bold-label list that is an undeclared table); SKILL.md §16 |
| claim | The recommendations section is a four-column table typeset as bold-label bullets. |
| evidence | "**Worth doing (moderate value, small effort):** (a) ... (b) ..."; "**Worth doing if HTS/client behavior changes are planned:** CommitStateUnknown ..."; "**Not worth doing:** crash-recovery ... multi-table, or refs/schema modeling". Every item carries the same fields — extension, verdict, value, effort, and in two cases a trigger condition — and the first bullet buries two independent recommendations behind one label, one of which (the rename race) is a new-bug candidate rather than a modelling improvement. The closing bullet ("Keep `OpenHouseCommit.tla` ... as the regression spec") is not a recommendation about deeper modelling at all and does not share the schema. |
| failure scenario | A reader deciding what modelling to fund cannot scan effort against value, and the highest-value item in the section — a possible new bug — is the second half of a bullet about expiration semantics. |
| severity | suggestion |
| confidence | confirmed |

### E4 — `nit`

| Field | Content |
|---|---|
| location | Line 10 |
| principle | none (internal consistency) |
| claim | A cross-reference points at the wrong section. |
| evidence | The artifact table's `prefix_sharedcache.cfg` row reads "holds (artifact; see **§6**)". §6 is "Limitations / abstractions"; the shared-cache result is §5, "A TLC-verified bonus observation: the dedup cache masks the bug on a single JVM". §6 item 4 points back correctly ("see §5"), which confirms the direction of the error. |
| failure scenario | A reader chasing the one anomalous row in the results table lands on the wrong section. |
| severity | nit |
| confidence | confirmed |

### E5 — `nit`

| Field | Content |
|---|---|
| location | §1 line 28 |
| principle | STRUCTURE.md rule 8 (production history goes to the ledger) |
| claim | The state-space paragraph reports the author's own pre-run prediction, and its large-configuration figure silently covers only the post-fix run. |
| evidence | "**State space estimate vs. actual**: ... predicted low thousands of reachable states. Actual: 103 distinct states (pre-fix), 99 (post-fix); 4,197 at 3 writers/6 commits." The prediction is session state: it is unverifiable, it changes nothing, and the verifiable claim (TLC finishes in under a second over a complete state graph) is already in the same paragraph. The 4,197 figure is `tlc-postfix-large.log` only; `tlc-prefix-large.log` reports 389 generated / 218 distinct with **119 states left on queue**, because the invariant violation halts the search — so the pre-fix large run has no complete state graph, and the sentence's pre-fix/post-fix pairing implies it does. §4 states the same numbers correctly and attributes them to the post-fix run. |
| failure scenario | A reader re-running the configurations to reproduce the numbers finds 218 where they expected 4,197 and cannot tell whether their run diverged. |
| severity | nit |
| confidence | confirmed |

### E6 — `nit`

| Field | Content |
|---|---|
| location | Line 26; §5 heading (line 88); line 90 |
| principle | STRUCTURE.md rule 4 (echoing the assignment); SKILL.md §33 |
| claim | Three passages assert the author's honesty or frame content relative to the assignment rather than stating it. |
| evidence | "**What was abstracted away** (honestly):" — the parenthesis claims a virtue the list itself demonstrates. "## 5. A TLC-verified **bonus** observation" — bonus relative to a scope only the commissioner knows; the observation (the per-JVM dedup cache masks the bug on a single JVM, which is why it survived single-instance testing) is one of the document's most useful results and is not a bonus to anyone reading the repository. "the **honest** production topology is the per-replica configuration that violates" — the sentence's real claim is that per-replica is the production topology, and it is weakened by the adjective. The same habit appears twice in appendix D (D5). |
| failure scenario | Minor: a reader discounts claims that advertise their own candour. |
| severity | nit |
| confidence | confirmed |

### E7 — `nit`

| Field | Content |
|---|---|
| location | Lines 90 and 97 |
| principle | none (internal consistency) |
| claim | Two references into appendix B cite it by description where that document asks to be cited by number. |
| evidence | "serialized by the shared cache ([Appendix B](appendix-b-code-review.md), **the dedup-cache finding**)" and "the scheme-normalization concerns ([Appendix B](appendix-b-code-review.md))" — appendix B line 33 states "Finding numbers are stable addresses; any future revision appends rather than renumbers", and the same file's other two references do use numbers ("blocking finding 3"). The intended targets are findings 5 and 13. |
| failure scenario | A reader must scan 29 findings to locate the one being cited. |
| severity | nit |
| confidence | confirmed |

---

## Pull request mkuchenbecker/openhouse#35 (title and body)

Genre: PR description. Binding rules per the skill's genre table: conclusion on top;
stands on its own; the caveat test; tables where items share fields. The layered
skeleton is exempt. The repository's PR-template checkboxes are template scaffolding,
not prose defects, and are not flagged.

### P1 — `suggestion`

| Field | Content |
|---|---|
| location | Body, "## Summary" (first paragraph) |
| principle | STRUCTURE.md rule 1 (conclusion on top; a conclusion is a claim someone could dispute or act on) |
| claim | The summary is a 90-word inventory of what the branch contains, not a claim a reviewer can act on. |
| evidence | "Adds `docs/commit-protocol/`: a code-referenced explanation of the OpenHouse table commit protocol (...), a synthesized three-expert code review of the commit path, a TLA+ model with TLC results reproducing the #612 snapshot-loss bug and verifying the fix, a REST-catalog-native migration design, and a working, tested prototype of that REST-native commit path." Five appositive clauses under one verb, "Adds". The disputable claims this PR actually makes — nine blocking defects remain beside the commit point; the structural fix is to move metadata authorship to the server; here is a working prototype that proves the commit loop — exist and are well stated in README.md's four numbered conclusions, but none of them reaches the PR description. |
| failure scenario | A reviewer triaging a 3,641-line, 24-file PR reads the first sentence and learns the file inventory, which the Files tab already shows. What they need to decide — is this a docs PR I can skim or a claim about production defects I must engage with — is not stated. |
| severity | suggestion |
| confidence | confirmed |

### P2 — `suggestion`

| Field | Content |
|---|---|
| location | PR title |
| principle | STRUCTURE.md rule 1 (the title is the conclusion); rule 4 (stands on its own) |
| claim | The title's `docs:` prefix understates a PR that adds five production classes to the tables service. |
| evidence | Title: "docs: OpenHouse commit protocol deep dive, review, and REST-native migration design". The body checks "- [x] New Features" and "- [x] Tests", and lists `IcebergRestCommitController`, `IcebergRestCommitService`, `RestUpdateValidator`, `IcebergRestExceptionHandler`, `IcebergRestSerde` under "Prototype (additive; legacy commit path untouched)". The commit log on the branch separates these correctly (`feat: add Iceberg REST-native commit endpoint prototype`, `test: cover the REST-native commit prototype...`), so the title is narrower than the branch's own ledger. |
| failure scenario | A reviewer filtering by conventional-commit prefix deprioritizes a `docs:` PR and never reviews a new unauthenticated-by-default HTTP route into the commit path. |
| severity | suggestion |
| confidence | confirmed |

### P3 — `suggestion`

| Field | Content |
|---|---|
| location | Body, "# Additional Information", final line |
| principle | STRUCTURE.md rule 4 (the document stands on its own) |
| claim | The PR points at a companion pull request it does not name or link. |
| evidence | "Related: a separate draft PR carries TLA+-driven fixes for the CAS-exempt rename path identified by the model and corroborated by the code review." No number, no URL. README.md line 21 makes the same unlinked reference ("the fix ships separately as its own draft pull request"), and appendix B grades that rename path a blocking finding, so the missing link is the one a reviewer most needs. |
| failure scenario | A reviewer who reads appendix B finding 3 and wants to know whether the fix is in flight has to search the repository's PR list to find out. |
| severity | suggestion |
| confidence | confirmed |

### P4 — `nit`

| Field | Content |
|---|---|
| location | Body, "## Summary" |
| principle | STRUCTURE.md rule 4 (undefined insider terms) |
| claim | The summary's parenthetical uses a coinage defined only inside the PR's own documents. |
| evidence | "(the metadata-path-as-version CAS trio, the HTS optimistic-lock commit point, failure windows, the subtractive snapshot merge)". "The CAS trio" is defined in protocol.md §2, which the reader has not opened yet; a PR description is read in a notification list where it must stand alone. |
| failure scenario | A reviewer skims a phrase they cannot parse and moves on. |
| severity | nit |
| confidence | confirmed |

### P5 — `nit`

| Field | Content |
|---|---|
| location | Body, "## Testing Done" |
| principle | none (internal consistency) |
| claim | The test count and the design matrix are reported in a way that appears to contradict itself. |
| evidence | "the full 8-case design matrix in `IcebergRestCommitControllerTest` (15 e2e on H2 + local FS, ...)". Eight cases, fifteen tests, no statement that several matrix cases expand into more than one test. The arithmetic elsewhere is right (15 + 6 + 14 = 35), so the confusion is presentational. Related: appendix D §6 makes the same matrix's exit criterion "matrix green ... plus one manual end-to-end against a dev MySQL HTS", while this PR lists "MySQL HTS certification" as a named follow-up — the PR is straightforward about it, but a reader comparing the two documents sees a stated exit criterion reported as unmet without comment. |
| failure scenario | A reviewer checking that the shipped tests match the design matrix cannot reconcile 8 against 15 without opening the test file. |
| severity | nit |
| confidence | confirmed |

### P6 — `nit`

| Field | Content |
|---|---|
| location | Body, last two lines |
| principle | STRUCTURE.md rule 4 (referencing the conversation that produced the document) |
| claim | The description ends with a generation footer and a session URL that no reader of the PR can open. |
| evidence | "🤖 Generated with [Claude Code](https://claude.com/claude-code)" followed by "https://claude.ai/code/session_011q6RgUTg3frF7q7n8EvR39". Flagged at `nit` and `probable` because this is plausibly a repository-wide tooling convention rather than a choice made in this PR; if it is, it is house style and not a defect. It is recorded here because it is the same class of residue as B2 and D5, and because a private session link is dead weight on a public open-source PR. |
| failure scenario | A contributor reading the PR follows a link that resolves to nothing they can access. |
| severity | nit |
| confidence | probable |

---

# Part 3 — Consistency check across the document set

Checked against the four context documents (README.md, protocol.md, appendix A,
appendix C), which were not reviewed. Contradictions found are filed above as D4
(README says the prototype ships in this change set; appendix D bills it as unstarted
Phase 0 work and specifies its classes as "to add") and E2 (README says an extended
model confirmed the rename counterexample; appendix E recommends building that model).
Both point the same way: README was updated after D and E and neither was reconciled.

Everything else checked out. Verified consistent: the 29 findings / 9 blocking count
across README, appendix B's summary, and appendix B's own numbering (findings 1-9
blocking, 10-29 follow-up); the 15-25 engineering-week estimate between README and
appendix D §5, subject to D4's point that it still includes delivered work; the
subtractive-merge and CAS-trio mechanics between protocol.md §2/§5, appendix A,
appendix D §0/§2, and appendix E §1; the 503-versus-500 unknown-state analysis between
appendix B finding 12 and appendix D §2 row 8; the rename defect between protocol.md
§4, appendix B finding 3, appendix D §2 row 11, and appendix E §3/§7; and
`incident-12185` versus `#612` usage, which appendix A defines once and the rest of the
set uses consistently.

Links: every relative link in the three reviewed documents resolves to an existing
file, and every `tla/` artifact named in appendix E's table exists. All of appendix D's
internal section references (§1.2, §1.4, §2 rows, §3, §4 P2, §4.5, §5 R6, §6) resolve.
The two reference failures are appendix E's "see §6" (E4) and appendix D's nine
citations into nonexistent reports (D3).

Numeric claims re-derived from the artifacts: appendix E's TLC figures match the
committed logs exactly (223/103 pre-fix, 221/99 depth 11 post-fix, 105/45 shared-cache,
11,536/4,197 depth 20 post-fix large), and "TLC finishes in under a second" matches
"Finished in 00s" in all five logs. The one discrepancy is E5's: the pre-fix large run
is 389/218 with 119 states left on queue, not a completed graph.
