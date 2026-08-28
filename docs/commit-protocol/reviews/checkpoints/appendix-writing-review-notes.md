# Working notes — writing review of commit-protocol appendices + PR #35

## Criteria loaded
- /home/user/code-review-skills/skills/writing-review/SKILL.md (procedure, findings contract)
- /home/user/code-review-skills/skills/writing-review/DESIGN-DOCS.md (design-doc genre)
- /home/user/mkuchenbecker/humanizer/STRUCTURE.md (rules 1-8)
- /home/user/mkuchenbecker/humanizer/SKILL.md (patterns 1-35 + guardrails)
Local copies used; no fetch. Full criteria available, no degraded pass.

## House-style calls (NOT flagged)
- Em dashes: used at high rate across every document in the set including the
  already-reviewed protocol.md / appendix-a / appendix-c. Corpus = writer's sample
  per SKILL.md §14 guardrail. Not flagged anywhere.
- Title-case document titles ("Appendix B: Code Review of the Commit Path"):
  consistent across the whole set. House style, not flagged (§17).
- Finding records with named fields (Evidence/Failure scenario/Action) in appendix B:
  items each carry paragraphs of argument, which STRUCTURE.md rule 2 routes to prose,
  not a table. Not flagged as an undeclared table.

## Verifications performed
- tla/ artifacts all exist; TLC log numbers checked against appendix E claims:
  prefix 223/103 OK, postfix 221/99 depth 11 OK, sharedcache 105/45 OK,
  postfix_large 11536/4197 depth 20 OK. prefix_large = 389 generated / 218 distinct
  with **119 states left on queue** (incomplete graph) -> E §1's "4,197 at 3 writers/
  6 commits" is the post-fix figure only.
- "Finished in 00s" in all logs -> "TLC finishes in under a second" holds.
- Prototype EXISTS on this branch: services/tables/.../resthandler/{IcebergRestCommitController,
  IcebergRestCommitService,IcebergRestExceptionHandler,IcebergRestSerde,RestUpdateValidator}.java
  + RestUpdateValidatorTest, e2e/h2/IcebergRestCommitControllerTest, internalcatalog/
  RestNativeCommitOperationsTest. Controller route == the route appendix D §6 proposes:
  POST /v1/rest/namespaces/{namespace}/tables/{tableId}/commit.
- grep: "report 01|report 02|report 03" and "smell #2|#3|#4|#6" appear only in appendix D;
  no such documents exist in the repo. Same for appendix B's "arch #N", "protocol FN",
  "testing FN", "the anchoring rule", "principle 4".
- Counted appendix B findings: 1-9 blocking (9), 10-29 follow-up (20) = 29. Matches
  its own summary and README.
- appendix D internal section refs (§1.2, §1.4, §2 rows, §3, §4 P2, §4.5, §5 R6, §6)
  all resolve. Relative links protocol.md / appendix-a / appendix-c all exist.
  appendix D never links appendix B despite depending on it.
- appendix E line 10 says "(artifact; see §6)"; the shared-cache discussion is §5
  (§6 is Limitations). Broken internal ref.

## Cross-document contradictions found
1. README claim 4: prototype "is included in the same change set as this document ...
   passing a 35-test matrix". appendix D §4 Phase 0 lists the prototype as an unstarted
   2-3 eng-week phase and §6 as "Classes to add" / "must pass" / "Exit criterion".
2. README claim 2: rename counterexample is "a prediction an extended model has since
   confirmed". appendix E §7 still lists modelling the rename race as "Worth doing"
   future work with a *likely* counterexample.
3. appendix D §5 total 15-25 eng-weeks includes P0 (2-3) which is already delivered;
   README repeats 15-25 as the forward estimate.
4. Minor: appendix D §6 exit criterion demands a manual MySQL HTS run; PR #35 lists
   MySQL certification as a named follow-up (not done). D presents it as Phase-0 exit.
5. Minor: D §6 names a class `IcebergRestJacksonConfig.java`; shipped class is
   `IcebergRestSerde.java`. D puts controller tests in .../resthandler/; the shipped
   controller test is in .../e2e/h2/.

## Design-doc genre (appendix D) — implied requirements harvested for the report
Must: kill client authority over absolute snapshot state (#612 class) structurally;
HTS row CAS stays the single atomic arbiter; stock Iceberg REST clients 1.2 and 1.5
work unmodified (§3 calls this "the acceptance criterion"); OpenHouse policy gates
preserved (lock, preserved openhouse.*/policies keys, authz, per-table toggle);
no on-disk/HTS data migration; legacy clients keep working through the window;
per-table rollback.
Should: re-enable server-side retry; spec-conformant error codes; idempotency for
ambiguous retries; credential vending (explicitly "a later, optional win").
Won't/out of scope (all implied, never declared as such): HTS schema/storage layout
unchanged; multi-level namespaces rejected 400; ACL/policy DDL not expressible as
TableUpdate; multi-table /v1/transactions/commit deferred; HTS rename CAS bug
"tracked separately"; OAuth2 credential flow.
Never-developed alternatives: harden the CAS trio in place (status quo + appendix B
fixes); server-side metadata authorship over the *existing* wire contract (typed
internal delta API, no REST adoption); adopt an off-the-shelf REST catalog
implementation (§1.3 dismisses RESTCatalogAdapter in a table cell); do nothing.

## Counts
appendix D: 3 blocker, 5 suggestion, 2 nit
appendix B: 1 blocker, 3 suggestion, 2 nit
appendix E: 0 blocker, 3 suggestion, 4 nit
PR #35:     0 blocker, 3 suggestion, 3 nit
