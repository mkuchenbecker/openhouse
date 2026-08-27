# Writing review: OpenHouse commit-protocol documentation

Reviewer: `writing-review`. Targets: `/home/user/openhouse/docs/commit-protocol/protocol.md`, `appendix-a-snapshot-drop-bug.md`, `appendix-c-iceberg-commit-protocol.md`. Criteria: the writing reference's structure rules (STRUCTURE.md, local copy) and sentence-pattern catalog (SKILL.md, local copy); both were reachable, so no degraded mode applies.

Genre call: none of the three documents is a design document. protocol.md is an explainer, Appendix A is an incident case study, Appendix C is a technical reference; all three are documentation files, so all structure rules bind and DESIGN-DOCS.md does not apply. Line numbers cited in the documents were not verified for technical truth (out of scope); cross-references between the documents were verified.

Cross-references verified good: protocol.md's links to `appendix-a-snapshot-drop-bug.md`, `appendix-c-iceberg-commit-protocol.md`, and `sequence-diagram.puml` resolve; protocol.md §5's citation of "Appendix C, §2" and §7's citation of "Appendix C, §4.2" both name sections that exist and say what protocol.md claims they say; Appendix A's account of the #612 fix (mechanism, check ordering, URI normalization, scope exclusions) is consistent with protocol.md §2 and §5.

Overall: protocol.md is a strong document — conclusion on top at every scale, real tables with framing sentences, caveats that carry information. Its one blocker is inherited from the set: it links three appendices that do not exist. Appendix A's content is solid but the document repeatedly addresses the author's machine and investigation session rather than a repository reader. Appendix C contradicts protocol.md on the central architectural fact of the set (who writes metadata.json in OpenHouse) and buries its own conclusions in a trailing appendix.

Counts: 4 blockers, 5 suggestions, 7 nits.

---

## Findings, most severe first

### F1. protocol.md links three companion documents that do not exist

| Field | Content |
|---|---|
| location | `protocol.md` lines 16–21 (intro), 199 (§3.3 item 4), 239 (§4 table row S6), 277 (§5), 314–316 and 318 (§7) |
| principle | The document stands on its own (structure rule 4) |
| claim | Four references to `appendix-b-code-review.md`, three to `appendix-d-rest-native-migration.md`, and one to `appendix-e-tla.md` are dead links; only appendices A and C exist in `docs/commit-protocol/` |
| evidence | Directory listing contains only `protocol.md`, `appendix-a-snapshot-drop-bug.md`, `appendix-c-iceberg-commit-protocol.md`, `sequence-diagram.puml`, `tla/` (no `.md`). The text makes load-bearing promises against the missing files: "consequences in [Appendix B]", "enumerated with severity assessments in [Appendix B]", "[Appendix D] builds on exactly that property", "a TLA+ model of this protocol ([Appendix E])" |
| failure scenario | A reader following S6 ("the one true non-atomic mutation of committed state") or the §7 limits to their promised severity assessments hits a 404; the document's risk claims cannot be checked without the author. The `tla/` directory exists but is unreachable from the prose except via the dead Appendix E link |
| severity | blocker |
| confidence | confirmed |
| reviewer | writing-review |

### F2. Appendix C contradicts protocol.md on who writes metadata.json in OpenHouse

| Field | Content |
|---|---|
| location | `appendix-c-iceberg-commit-protocol.md`, closing section "Appendix: Implications for an OpenHouse-style protocol" (heading and item 1) |
| principle | none (internal consistency) |
| claim | Appendix C characterizes the OpenHouse model as the client writing metadata.json, while protocol.md states the client never writes it |
| evidence | Appendix C: heading `("server stores version, client writes metadata.json")` and item 1 "In OpenHouse's model the client writes metadata.json and the server CAS-es a version". protocol.md §3.1: "The client never writes `metadata.json`. It writes data files, manifests, and manifest lists..."; §3.3 step 7 has the Tables service write it. The contradiction is resolvable only if Appendix C's "client" means the Tables service acting as a client of HTS, but neither document defines it that way, and protocol.md uses "client" throughout to mean the engine side |
| failure scenario | A reader of Appendix C alone concludes OpenHouse engines hold storage-write credentials for `metadata/` and that the server does not rebuild metadata — the exact axes the migration argument turns on. A reader of both documents cannot reconcile them without the author |
| severity | blocker |
| confidence | confirmed |
| reviewer | writing-review |

### F3. Appendix A addresses the author's checkout and session, not the repository

| Field | Content |
|---|---|
| location | `appendix-a-snapshot-drop-bug.md` line 3 (provenance note) and final section "Key file references (absolute paths, current HEAD)" (lines 173–178) |
| principle | The document stands on its own (rule 4); state the present, history goes to the ledger (rule 8) |
| claim | The document opens by describing the author's local git state and closes with `/home/user/...` absolute paths, none of which a public-repo reader can resolve |
| evidence | "Repo: `/home/user/openhouse` (fork `mkuchenbecker/openhouse` with upstream `linkedin/openhouse` history; local history is grafted/shallow at ~50 commits)"; "Fix + merge logic: `/home/user/openhouse/iceberg/openhouse/internalcatalog/src/main/java/...`". The graft/shallow note is session state about the author's clone, meaningless and unverifiable for any other reader; the paths should be repo-relative |
| failure scenario | A public reader cannot open any file the reference section points at; the opening line signals the document is a private investigation artifact, undermining trust in the rest |
| severity | blocker |
| confidence | confirmed |
| reviewer | writing-review |

### F4. Appendix C's scope note points at a local checkout and narrates its own verification

| Field | Content |
|---|---|
| location | `appendix-c-iceberg-commit-protocol.md` line 3 (italic scope note) |
| principle | The document stands on its own (rule 4); state the present, history goes to the ledger (rule 8) |
| claim | The line-number pin is anchored to a path only the author has, and the parenthetical narrates the author's verification process instead of stating the checkable fact |
| evidence | "All `path:line` references are into the apache/iceberg checkout at `/home/user/iceberg` (version `1.5.2.x` per `version.properties`; line numbers verified against this tree)." The useful, verifiable content is the version pin ("Apache Iceberg 1.5.2"); "verified against this tree" is process narration a reader cannot check |
| failure scenario | Every one of the document's several hundred `path:line` citations is nominally addressed to a directory the reader does not have; a reader wanting to check one must first guess that "the checkout" is plain apache/iceberg at the stated version |
| severity | blocker |
| confidence | confirmed |
| reviewer | writing-review |

### F5. Appendix C's conclusions sit in a trailing appendix; the document opens with none

| Field | Content |
|---|---|
| location | `appendix-c-iceberg-commit-protocol.md`, document opening and closing "Appendix: Implications..." section |
| principle | Conclusion on top (rule 1); layering — no new conclusions downstream (rule 7) |
| claim | The document's actionable claims (writer locus, conflict semantics, retry correctness) first appear in the final section labeled "Appendix", and nothing at the top states what the reader should take away |
| evidence | The document opens with the scope note and §1's SPI walk-through; the three numbered "Implications" — e.g. "A protocol whose retry unit is 'the metadata.json I already wrote' cannot do this — ... the classic naive-implementation bug the `MetadataUpdate.applyTo` design exists to prevent" — are the verdict of the whole comparison, and §4's heading even flags the relevance ("key for the OpenHouse proposal") that only pays off at the bottom. An appendix by the reference's own layering carries background, not new conclusions |
| failure scenario | The triage reader protocol.md sends here "for comparison" reads six sections of Iceberg internals with no stated point, and the readers who stop early — most — never reach the three claims the document exists to make |
| severity | suggestion |
| confidence | confirmed |
| reviewer | writing-review |

### F6. Appendix A §6 narrates the investigation instead of informing the reader

| Field | Content |
|---|---|
| location | `appendix-a-snapshot-drop-bug.md` §6 "Candidate enumeration (runners-up)" |
| principle | State the present; history goes to the ledger (rule 8); the document stands on its own (rule 4 — assignment echo) |
| claim | The section records how the author identified the fix commit — candidate ranking, certainty statement, the grep that confirmed it — which is session state from an assignment ("find the commit"), not case-study content |
| evidence | "**`9407819` (#612)** — the match, with certainty"; "No other commit in the visible history touches snapshot-drop behavior (`git log --all -i --grep` over snapshot/drop/lost/race/concurrent/retry/stale confirms)". The one fact a reader needs from this section — that `isStaleSnapshotError` predates the fix and addresses a loud failure in the same bug class — is buried among rejected candidates |
| failure scenario | A public reader has no question this section answers; the "runners-up" framing reveals the document as the output of a search task and teaches the reader to read the rest as a task deliverable rather than an incident record |
| severity | suggestion |
| confidence | confirmed |
| reviewer | writing-review |

### F7. Appendix A leans on identifiers only the author's organization can resolve

| Field | Content |
|---|---|
| location | `appendix-a-snapshot-drop-bug.md` — TL;DR, §2 intro, §4 last bullet, §5 timeline |
| principle | The document stands on its own (rule 4 — undefined insider terms) |
| claim | "internal incident-12185" (four occurrences), "2026-05-25 WAR" (undefined acronym), and "explored in PR #614" are cited as evidence but are unresolvable for the public reader |
| evidence | "the dropped-snapshot lost update (internal incident-12185, 2026-05-25)"; "documents it as a deterministic reproduction of incident-12185 (2026-05-25 WAR, snapshot `3635817277608242413` rebased out)"; "From the fix commit message (incident-12185) and the added test's javadoc" |
| failure scenario | The reader is told the incident record is the source for the interleaving in §2 but can never consult it; "WAR" forces a guess (war room? weekly report?) mid-sentence. Either define/summarize the incident inline as the sole public record, or drop the identifier and let the commit and test carry the evidence |
| severity | suggestion |
| confidence | confirmed |
| reviewer | writing-review |

### F8. protocol.md §4's "special paths" bullets are an undeclared table

| Field | Content |
|---|---|
| location | `protocol.md` §4, list following the failure-window table ("Special paths that deviate from the main flow") |
| principle | Tables instead of context-heavy lists (rule 2); sentence catalog §16 (bold-label lists) |
| claim | Three bold-label bullets each carry the same fields — path name, deviation from the protocol, consequence, code reference — and belong in a table like the S1–S6 one directly above |
| evidence | "**Stage-create / stage-replace (write-audit-publish)**: metadata.json is written but HTS is never updated...", "**Rename**: routes to a direct JPQL UPDATE that neither checks nor bumps `@Version`...", "**Drop**: deletes the HTS row first...". §7 item 4 later re-enumerates the same paths in prose, so the schema (path → which guarantee it escapes) demonstrably exists |
| failure scenario | A reader auditing which operations sit outside the optimistic lock must extract the shared fields from prose here and reconcile them with §7's second enumeration, instead of scanning one column |
| severity | suggestion |
| confidence | confirmed |
| reviewer | writing-review |

### F9. Appendix C's "the OpenHouse proposal" has no referent

| Field | Content |
|---|---|
| location | `appendix-c-iceberg-commit-protocol.md` §4 heading "(key for the OpenHouse proposal)" and the closing appendix |
| principle | The document stands on its own (rule 4) |
| claim | "The OpenHouse proposal" is named twice as this document's raison d'être but is never identified; the presumable target (the Appendix D migration design) is neither linked nor existent (F1) |
| evidence | "## 4. The REST Catalog Protocol (key for the OpenHouse proposal)"; "Implications for an OpenHouse-style protocol" |
| failure scenario | The reader is told which section matters most and why, but cannot find the document that makes it matter |
| severity | suggestion |
| confidence | confirmed |
| reviewer | writing-review |

### F10. Appendix A: "current HEAD" is a moving target the document otherwise pins

| Field | Content |
|---|---|
| location | `appendix-a-snapshot-drop-bug.md` — §1 ("update branch, current HEAD"), §1 merge heading, §3, §5 timeline, "Key file references" heading |
| principle | State the present; history goes to the ledger (rule 8) |
| claim | Line references are anchored to "current HEAD" in six places, a phrase that goes stale on the next merge, while the actual pin (`2a9dac8`) appears only in the timeline's last row; protocol.md pins its commit in its second paragraph |
| evidence | "(current HEAD, `OpenHouseInternalTableOperations.java:314-354`...)"; "HEAD `2a9dac8` | 2026-08-23 | Fix present at current HEAD" |
| failure scenario | A reader six months later checks a "current HEAD" line number against a moved HEAD, finds different code, and distrusts the document |
| severity | nit |
| confidence | confirmed |
| reviewer | writing-review |

### F11. Appendix A: "Residual gaps (my assessment)" — unattributed first person

| Field | Content |
|---|---|
| location | `appendix-a-snapshot-drop-bug.md` §3, heading "Residual gaps (my assessment)" |
| principle | The document stands on its own (rule 4) |
| claim | The only first-person voice in the set appears in a repo document with no stated author, so "my" resolves to no one |
| evidence | The heading; the document's byline-free framing (the Mike Kuchenbecker named in the TL;DR is the commit author, which does not establish document authorship) |
| failure scenario | A reader cannot weigh the assessment's authority; the parenthetical also functions as a blame shield rather than a confidence statement — either the gaps are asserted by the document or they are not |
| severity | nit |
| confidence | confirmed |
| reviewer | writing-review |

### F12. Appendix A: bold saturation substitutes for structure

| Field | Content |
|---|---|
| location | `appendix-a-snapshot-drop-bug.md`, throughout §1–§4 |
| principle | Sentence catalog §15 (too much bold text) |
| claim | Emphasis bolding appears several times per paragraph, so it no longer ranks anything |
| evidence | §2 alone bolds "`BaseTransaction.applyUpdates` silently refreshes", "re-applies", "first", "S_r — a durably committed, acknowledged snapshot — is gone.", "server-side", plus more; §1 bolds "entire", "removed", "subtractive merge", "This check runs against the writer's own loaded view..." (a full sentence) |
| failure scenario | The genuinely load-bearing emphasis (the one-sentence bug statement) is indistinguishable from routine highlighting; readers skimming bold — the reason to bold — get noise |
| severity | nit |
| confidence | confirmed |
| reviewer | writing-review |

### F13. protocol.md: "the fix-#612 CAS" appears before #612 means anything

| Field | Content |
|---|---|
| location | `protocol.md` §1, happy-path step 3 |
| principle | The document stands on its own (rule 4 — undefined insider terms at first use) |
| claim | "#612" is used as an epithet in the one-screen sketch before the reader has been told it is the fix for the Appendix A incident (first explained in §2, check 2) |
| evidence | "`OpenHouseInternalTableOperations.doCommit` re-checks the base (the fix-#612 CAS)" — no link at this occurrence; the intro's Appendix A link mentions the incident but not the number |
| failure scenario | A first-time reader in the sketch layer stumbles on an unexplained issue number; small, because §2 resolves it one screen later |
| severity | nit |
| confidence | confirmed |
| reviewer | writing-review |

### F14. Appendix C: heading "(for sequence diagrams)" names the production purpose, not the reader's

| Field | Content |
|---|---|
| location | `appendix-c-iceberg-commit-protocol.md` §6 heading "Step-Numbered Commit Sequences (for sequence diagrams)" |
| principle | The document stands on its own (rule 4); state the present (rule 8) |
| claim | The parenthetical explains why the material was prepared (as input for drawing diagrams elsewhere in the doc set) rather than what it gives the reader (numbered actor-by-actor commit walkthroughs) |
| evidence | The heading; no diagram follows in this document, and no other document in the set is named as the consumer |
| failure scenario | A reader expects diagrams and finds numbered prose; the phrase reads as a note to whoever renders the diagrams — production scaffolding left in the deliverable |
| severity | nit |
| confidence | probable |
| reviewer | writing-review |

### F15. Appendix C: Title-Case headings, inconsistent with the set

| Field | Content |
|---|---|
| location | `appendix-c-iceberg-commit-protocol.md`, all §-level headings |
| principle | Sentence catalog §17 (title case in headings) |
| claim | Headings capitalize every main word ("The Retry Loop: Re-Apply, Never Re-Write", "Behavior / Guarantee Comparison Data") while protocol.md and Appendix A use sentence case |
| evidence | Compare "## 2. The Retry Loop: Re-Apply, Never Re-Write" with protocol.md's "## 5. Snapshot reconciliation: the subtractive merge" |
| failure scenario | Cosmetic inconsistency across a document set presented as one work |
| severity | nit |
| confidence | confirmed |
| reviewer | writing-review |

### F16. Appendix C: stray punctuation spacing

| Field | Content |
|---|---|
| location | `appendix-c-iceberg-commit-protocol.md` §4.4, server-internal-conflict bullet |
| principle | none (internal consistency — polish) |
| claim | A space precedes a comma |
| evidence | "the *server* refreshes (`:358`) , re-validates" |
| failure scenario | None beyond polish |
| severity | nit |
| confidence | confirmed |
| reviewer | writing-review |

---

## What was checked and not flagged

- Em-dash density is high in all three documents, but the catalog's false-positive guardrail (em dashes are evidence only alongside sales-y rhythm, absent here) applies; treated as house style.
- protocol.md's scope sentence ("This document explains the protocol as it exists today, at repo commit `2a9dac8`") is a useful, pinned scope statement, kept per the guardrails.
- Appendix A's timeline table is a legitimate ledger for the case-study genre; the incident history belongs there.
- The 503 discrepancy (protocol.md maps 503 to unknown; Appendix C says Iceberg REST treats 503 as retryable) describes two different systems and each account is internally consistent — not an inconsistency.
- No hedge piles, chatbot residue, generic endings, forced triads, or heading-echo openers were found in any document; the caveats that exist (e.g. Appendix A's residual gaps) are conditionals that carry information.

## Per-document tallies

| Document | blocker | suggestion | nit |
|---|---|---|---|
| protocol.md | 1 (F1) | 1 (F8) | 1 (F13) |
| appendix-a-snapshot-drop-bug.md | 1 (F3) | 2 (F6, F7) | 3 (F10, F11, F12) |
| appendix-c-iceberg-commit-protocol.md | 2 (F2, F4) | 2 (F5, F9) | 3 (F14, F15, F16) |
