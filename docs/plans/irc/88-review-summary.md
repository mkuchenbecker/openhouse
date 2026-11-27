# Summary of the retroactive review of the 71 merges

Bottom line: the Iceberg REST and v3 work is mostly sound as an adapter layer over native code, but it cannot go
upstream as merged. Two new routes read data with no authorization check, LinkedIn's release workflow is broken, no test
compares against LinkedIn main, and 26 behavior changes are visible on the reference line that was meant to be main's
build unchanged. Fixes start from `86-review-findings-and-fix-forward.md` (FF-01 first); the changes and what to do with
each are in `85-behavior-change-ledger.md`; the re-implementation plan is `87-clean-reimplementation-guide.md`; the nine
reports are `records/review-*.md`.

## Counts

- Defects: 68 (5 BLOCKER, 24 SHOULD FIX, 39 MINOR); 7 are inherited from LinkedIn main. By area: table write routes
  1/4/5, read and register 0/0/7, views and capability routes 1/2/1, scan planning 0/3/4, native path and House Tables
  1/1/2 (the blocker inherited), client and Spark 0/1/5, format v3 0/3/4, jobs 0/2/3, build and release 1/4/4, CI and
  evidence 1/4/4 (BLOCKER/SHOULD FIX/MINOR).
- Ledger: 43 behavior changes. Part A (reference line) 26, part B (default line only) 7, part C (CI, release, build,
  published fixtures) 10. RESTORE 19, GATE 4, KEEP-FIX 12, KEEP-DIRECTED 1, OWNER 7. SMALL 37, FOUNDATION 6.
- Fix-forward: 45 items; 4 decided by the orchestrator and ready; 9 wait on a decision; 1 waits on the scout.

## The ten findings that matter most

1. An Iceberg REST table commit with no updates returns the table's full metadata to any authenticated principal
   (RF-001); every commit also answers requirement checks before authorization (RF-002). Fix: FF-01, ready.
2. Iceberg REST view load and list return view definitions with no authorization decision, where the native GET is
   pointer-only (RF-018). Fix: FF-03, waits on the scout for which privilege.
3. LinkedIn's release workflow, unchanged, now pushes a tag and then fails before publishing anything (RF-051,
   BC-034). Fix: release from the reference line, tag after publish (FF-26).
4. No suite compares against LinkedIn main; baselines were edited in place and 32 test branches accept default-line
   differences unreviewed (RF-060). Fix: a recorded baseline of `bb8929e1` in CI (FF-20 to FF-23).
5. The reference line is not main's build: `log4j2.properties` in the jobs jar, `datalayout` on Spark 3.5, unpublished
   capability classes in the Spark runtime, the Iceberg 1.2 / Spark 3.1 artifacts deleted (BC-008, BC-023 to BC-025).
6. The House Tables row-length check refuses, by default and with no off value, writes the bootstrap store accepts,
   including every commit to an existing long-path table (BC-001). Fix: off by default (FF-09), ready.
7. The default build stops reading every table that requires `column-defaults` (422), with no migration, and ships Java
   17 client jars; the owner was never told either consequence (BC-029, BC-028; OWNER).
8. Scan planning exposes per-file column bounds under the metadata privilege with the service's credentials, emits no
   audit event, and cannot be switched off or bounded in time (RF-022 to RF-024; FF-04 to FF-06).
9. Spark policy statements are now refused on catalogs that are not Iceberg's own (BC-007; FF-10, ready), one of 26
   reference-line changes, 24 of them SMALL with no owner justification.
10. Twenty claims of unchanged behavior in merge messages and notes are false, among them "No Spark SQL difference was
    found" (118), "answer exactly as before" (129), "under the switch only" (105) and "byte for byte" (114); the v3
    question put to the owner in `58-waiting-on-mike.md` rests on the wrong baseline (BC-031).

## Confirmed sound (do not re-litigate)

- The native commit refactor of pull request 89 is behavior-preserving line by line: order of checks, error types and
  wording, saves, no-op short-circuit, return flags; native rename, drop and create untouched (CW).
- The IRC compare-and-set is real: built on a server-loaded base, checked by `versionCheck` and the House Tables
  compare-and-set (CW). The multi-table route lets no second table in (CW).
- Register table: path rules, read-after-authorization order, storage pinning, traversal guard (RR). Load runs the
  native checks in native order (RR). The endpoint list equals the served routes by construction (RR, VC).
- Grants and unlock over IRC call the native handlers with the same arguments, privilege and audit (VC); the policy
  patch's no-lost-update claim holds for every field kind by reading (VC).
- The scan planner's v2 results: no case of fewer files or a dropped delete file; deletes attached on the server; no
  `scan-planning-mode` ever sent (SP).
- Pull request 100's resolver is equivalent for Iceberg's own Spark catalogs, checked against Iceberg 1.5.2.24 (CS);
  the in-process seam is invisible unless `catalog-impl` names it (CS); the rename-target rule matches the legacy
  messages (CS); 12 (CS) and 16 (CF) declared smoke difference reasons were checked and hold.
- Format v3: the rule is wired into the one pre-commit hook; no silent downgrade path; row lineage and deletion vectors
  survive the native paths; the widening fix of 126 is correct (FV).
- Jobs: 71 of 77 main-source moves are pure renames; the shared catalog is thread-safe; the concurrency races are real
  and cannot pass trivially (JB). The `src/shared` move of 110 is 193 pure renames (BD).
- CI: the split's partition is sound by construction, permissions are narrower than main's, no pull-request job touches
  a secret, and the T4 commit-path tests are real pins (CF).

## What no reviewer checked (the residual risk)

- Nothing was built or run by any reviewer: no resolved dependency graph, no jar or image listing, no test run; every
  runtime claim is from reading (all nine).
- Real systems: House Tables on MySQL (strict or not), S3 and ADLS FileIO and the new AWS SDK stack, a real OPA
  policy, LinkedIn's Spark clusters' Log4j2 setup, LinkedIn's branch protection, whether deployments grant
  `GET_TABLE_METADATA` without storage read, whether view directories are protected in storage (RR, SP, VC, BD, CF).
- Pull requests 125 and 149 were not reviewed in this round (they had independent reviews before merge); 149 was read
  only for fit (VC).
- Byte-identity of the generated client after 123 and 139, and the specs' JSON at base and head (RR, CS, BD).
- The Spark maintenance procedures through a server-planning catalog (SP); a multi-table transaction through
  `TableCommitService` (FV); a no-op view commit (CW); every internal-catalog caller of `doCommit` for the create-history
  key (CW); `IcebergRestErrors` completeness (RR, VC).
- About 94 of 118 kit filings, the smoke scenario bodies against main's code paths, the delta-harness code, the Spark
  4.2 itest suites, `FormatV3RulesHoldTest` rule by rule, the v3 stored-metadata goldens, the Spark 4 attributions of
  s22 and s26 (CF, FV, CS).
- TLS and custom `rest.auth.type` managers (CS); the REST-versus-native difference after expiry on the server and the
  House Tables compare-and-set window (JB, CW); the Azure recipe, base image digests (BD).

## Per pull request

"Covered by" names the review (ids as in the ledger); "Claims hold" is that reviewer's verdict on the merge message's
"Leaves true": yes, partly, no, or cannot tell.

| Pull request | Merge | Subject | Covered by | Claims hold | Note |
|---|---|---|---|---|---|
| 80 | `37b89f0b` | T1 smoke suite | CF | partly | not a stored baseline; later scenarios not of main |
| 81 | `d5c34e74` | T3 contract test | CF | yes | |
| 82 | `85614361` | T4 commit-path tests | CF | yes | pinned defects documented |
| 83 | `2334b050` | T5 stand-in | CF | partly | "restore unchecked as in the real store" false |
| 84 | `4f15a3a3` | C1 RESTCatalog path | CS | partly | no production file changed; `prefix` unsupported |
| 85 | `8bda0f6b` | T2 delta-harness | CF | yes | stale: no full run since the flip |
| 86 | `b5b8ed87` | T2b harness via seam | CS | yes | |
| 87 | `fdc7f426` | I6a-c opt-in build | BD | partly | superseded by 109 and later docs |
| 88 | `4517643b` | V2 read routes | RR | partly | namespace case handling inconsistent |
| 89 | `94877d49` | V1 commit core | CW | yes | read line by line |
| 90 | `b78743fe` | CI post-merge build | CF | yes | superseded |
| 91 | `b732cc7b` | W3, W4, I6g, I6h | BD, FV | partly | datalayout change later unconditional |
| 92 | `8b9bfe83` | N3a Spark 4.2 read | CS | yes | client side only |
| 93 | `f60e32dd` | CI finish running build | CF | yes | superseded by 144 |
| 94 | `14f23870` | V3 write routes | CW | partly | no-op commit skips the write privilege |
| 95 | `71b3e826` | V4 view routes | VC | partly | reads unauthorized in substance |
| 96 | `0010df15` | V3 follow-up | CW | yes | |
| 97 | `e353a36d` | C5, W12, I10 | BD, JB | partly | BD cannot tell; JB: the scheduler parity javadoc is false |
| 98 | `cc76d485` | fix-forward 1.2 boots | BD | yes | guards now redundant |
| 99 | `1fa8cb34` | V5-1, V5-2 policies | VC | yes | |
| 100 | `19605614` | C2-1, C2-2 resolver | CS | partly | non-Iceberg catalogs refused |
| 101 | `b07613f9` | N3-x1 Spark 4.2 extensions | CS | yes | byte-identity verified |
| 102 | `6015e111` | V5-3, V10a grants, unlock | VC | yes | with notes |
| 103 | `c174e388` | C2-3 capability client | CS | yes | declared gaps |
| 104 | `fb8c1240` | V13a compat kit | CF | partly | ratchet ignores cause; misfilings |
| 105 | `7ddc5bb4` | I6f full regression | BD | partly | "under the switch only" no longer true |
| 106 | `bfdcf266` | V13b spec deviations | CW | yes | orphan files remain |
| 107 | `152b08e7` | fix-forward tables suite | CS | yes | no product code |
| 108 | `3222cf78` | fix-forward short warehouse | CF | partly | "real column is 255" false |
| 109 | `6bc6fb05` | V9a, V9b | BD | yes | |
| 110 | `7e8941ab` | V9c shared sources | BD | yes | pure move |
| 111 | `6cc7b092` | C6, T9 | CF | yes | |
| 112 | `8876a7f2` | V6, V7 metrics, register | RR | partly | UUID half of the directory check dead |
| 113 | `b1dfe9b0` | V9d deployables | BD | partly | log4j2 now on both lines |
| 114 | `1fec3a0a` | C9 Docker checks | BD | partly | "byte for byte" builder no longer default |
| 115 | `5ea51d35` | T10 Spark 3.5 tests | CF | yes | |
| 116 | `0a3bf529` | V16, N5, T13 | RR | partly | two-read race unmentioned |
| 117 | `c1303849` | N3b Spark 4.2 smoke | CS | partly | "none is a defect" contradicts its RENAME gap |
| 118 | `440249d1` | V9e flip | BD, CS | partly | reference line not main's build; "No Spark SQL difference" false |
| 119 | `92c5e117` | N6 rename target | CS | yes | |
| 120 | `a217cce9` | V8 multi-table | CW | yes | inherits RF-001, RF-002 |
| 121 | `72947228` | V15, V3b, V3c | RR | partly | row-length scope understated |
| 122 | `71c4c70b` | C10 Spark image, dataloader | BD | yes | builder default changed |
| 123 | `75d6e185` | C14 generator CLI | BD | yes | same bytes not verified |
| 124 | `e2e02bfa` | X1, W6 format v3 | FV | partly | "before" answers are the branch's |
| 125 | `262a1416` | X6 maintenance on v3 | none (excluded) | cannot tell | reviewed independently before merge |
| 126 | `57db4015` | W7, X4 v3 reads | FV | yes | ORC vectorized not verified |
| 127 | `91e175c4` | V11a, V12a planning | SP | partly | audit claim false |
| 128 | `ca94d82e` | N1-c, N7 | CS | partly | equivalence read, not proven |
| 129 | `70b35d7a` | X5 v3 types | FV | partly | "exactly as before" false against main |
| 130 | `adc28470` | X10 concurrency | JB | yes | tests only |
| 131 | `1dd485d3` | V11b, V11c | SP | yes | "under 1 MB" not asserted |
| 132 | `9f9561e6` | V11e | SP | yes | |
| 133 | `7e57bd21` | C16 CI split | CF | partly | check names checked on the fork only |
| 134 | `d30cb9f6` | V14 create history | CW | yes | |
| 135 | `e2823b69` | N11 jobs on 4.2 | JB | partly | extra table load per run |
| 136 | `4b696a55` | C12 UTF-8 | BD | yes | |
| 137 | `461ae437` | X10 rest, X15 | JB | yes | WARN per null value unmentioned |
| 138 | `c2c52144` | C16 print failed tests | CF | yes | |
| 139 | `e6c52c21` | N8, V18, C15 | RR | yes | spec equality not generated; s31 re-pinned (CF) |
| 140 | `dfcd15a0` | T6, N10 grants | CF | partly | effect of a test-invented role model |
| 141 | `3079859d` | N16, X9 | JB | yes | private ids in comments |
| 142 | `af210f1b` | V19, V20, V22 | RR | partly | io-impl edge cases |
| 143 | `f07a92e1` | C19 heap | JB | yes | |
| 144 | `7c9fa958` | C20 post-merge split | CF | yes | release broken by the flip |
| 145 | `9dfbbca7` | X11, X12, X13 | JB | partly | fix unreachable on `jobs.yaml` |
| 146 | `22017134` | C3, C4 | CF | yes | readback never shown to fail |
| 147 | `2ba15929` | V17 namespace check | RR | partly | letter-case evidence from a case-sensitive stand-in |
| 148 | `886b660b` | X16 H2 reuse | CF | yes | `@PropertySource` precedence weak |
| 149 | `81b4cd4d` | V10 register-view | VC (fit only) | cannot tell | reviewed independently before merge; inherits RF-018 |
| 150 | `cafcc9da` | X18 H2 reuse | CF | yes | duplicates the reproduction test |

Totals: yes 39, partly 30, no 0, cannot tell 2 (125, 149). No reviewer graded a pull request "no" overall; the false
claims are listed one by one at the end of `85-behavior-change-ledger.md`.
