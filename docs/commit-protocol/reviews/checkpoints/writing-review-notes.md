# Writing-review working notes (checkpoint)

## Setup
- Criteria loaded from local copies: humanizer/STRUCTURE.md (rules 1-8) and humanizer/SKILL.md (patterns 1-35 + guardrails). Not fetched from GitHub (local copies designated by invocation).
- DESIGN-DOCS.md read. Genre call: none of the three targets is a design document (protocol.md = explainer, appendix A = incident case study, appendix C = technical reference). DESIGN-DOCS structure does not bind; general structure rules + sentence catalog bind all three (Documentation files row of Genres table).

## Directory facts (verified)
- docs/commit-protocol/ contains ONLY: protocol.md, appendix-a-snapshot-drop-bug.md, appendix-c-iceberg-commit-protocol.md, sequence-diagram.puml, tla/ (model + cfg + logs; no .md).
- Missing link targets, all referenced from protocol.md: appendix-b-code-review.md (4x: lines 18, 199, 239, 316), appendix-d-rest-native-migration.md (3 lines: 20, 277, 318), appendix-e-tla.md (line 21).
- sequence-diagram.puml link resolves. appendix-a and appendix-c links resolve.
- Section cross-refs verified: protocol.md §5 → "Appendix C, §2" (exists: retry loop) OK; protocol.md §7.1 → "Appendix C, §4.2" (exists: requirement types) OK; protocol.md §2/§5 → Appendix A (mechanism matches) OK.

## Structure pass
### protocol.md
- Conclusion on top: YES, strong (para 1 is a disputable claim: atomic at exactly one point; structural weakness = client-authoritative snapshot list). Sections lead with their point. Layering good (one-screen sketch §1 → details §3-6 → properties §7).
- Broken links to B/D/E = cannot-stand-alone for the promises made about them (S6 consequences, severity assessments, migration design "builds on exactly that property").
- §4 "Special paths that deviate" = 3 bold-label bullets each carrying name+behavior+consequence → undeclared table (rule 2 / §16).
- "the fix-#612 CAS" first appears §1 step 3 cold; explained only via later Appendix A links. Minor.
- Em dashes dense but guardrail says em dashes alone are not evidence; technical register, no sales rhythm → not flagged.
- Scope sentence "This document explains..." kept per guardrail (useful scope statement + commit pin).

### appendix-a
- Conclusion on top: yes (TL;DR = fix commit + mechanism).
- BLOCKER leak cluster: line 3 provenance ("Repo: /home/user/openhouse (fork mkuchenbecker/openhouse... grafted/shallow at ~50 commits)") + "Key file references (absolute paths...)" section with /home/user/... paths. Doc addresses the author's machine/session, not the repository. Rules 4+8.
- Unresolvable internal identifiers: "internal incident-12185" (4x), "2026-05-25 WAR" (WAR undefined acronym), "explored in PR #614".
- §6 "Candidate enumeration (runners-up)" = investigation-process narration / assignment echo ("the match, with certainty"; "git log --all -i --grep ... confirms"). Rule 8; reader-facing value ~0.
- "Residual gaps (my assessment)" — unattributed first person.
- "current HEAD" phrasing throughout (stale-prone; commit 2a9dac8 named only in timeline).
- Bold overuse (§15) heavy throughout.
- Timeline table = legitimate ledger for a case study (genre) — not flagged. "test was dropped because H2-only" = relevant engineering fact, kept.

### appendix-c
- No conclusion on top: opens with checkout note then §1 contract; the document's verdict/takeaways (3 implications) sit in a TRAILING appendix = new conclusions downstream (rules 1+7).
- BLOCKER leak: "/home/user/iceberg" checkout + "line numbers verified against this tree" (process narration).
- BLOCKER internal inconsistency: closing appendix says OpenHouse model = "server stores version, client writes metadata.json" / "In OpenHouse's model the client writes metadata.json" — protocol.md §3.1 says "The client never writes metadata.json" (Tables service writes it, §3.3 step 7). Undefined referent "client" (engine vs Tables-service-as-HTS-client); reader cannot reconcile without author.
- "the OpenHouse proposal" (§4 heading + appendix) unresolvable — presumably missing Appendix D, never named.
- §6 heading "(for sequence diagrams)" — production-purpose leak.
- Title-Case headings (§17), heavy bold, stray "(`:358`) , re-" spacing.

## Sentence pass residue (folded or nits)
- Bold-label sub-bullets in appendix A §1 are code-walk items with scaffold — acceptable fragments.
- No hedge piles found anywhere (caveats present are conditionals that carry information — e.g. residual gaps list is genuine limits). No chatbot artifacts, no generic endings, no forced triads.
- 503 handling difference between protocol.md (OpenHouse: 503→unknown) and appendix C (Iceberg REST: 503=retryable) is a real difference between systems, both internally consistent — NOT flagged.

## Final tallies
protocol.md: 1 blocker, 1 suggestion, 1 nit
appendix-a: 1 blocker, 2 suggestions, 3 nits
appendix-c: 2 blockers, 2 suggestions, 3 nits
