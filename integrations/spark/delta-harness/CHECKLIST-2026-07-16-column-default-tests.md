# CHECKLIST — 2026-07-16 — #251 column-default correctness tests

New file per task. Re-read first each turn. Parent: PROJECT-PLAN.md sub-goal B (fork) follow-ups.

> NOTE (2026-07-20): column defaults are TABLED per repo owner. This checklist is the dated working log;
> for the current factual account see ICEBERG-FORK-AUDIT.md #251 section. Value-judgment phrasing in the
> original text below has been neutralized; the observations stand.

## GOAL
Characterize the fork's #251 column-default backport (api/core only: initial/write-default APIs +
SchemaParser serialization; no read-application and no Spark write wiring in the open fork). Format policy:
ORC+Parquet (defaults are schema-metadata = format-agnostic, but run both per standing policy; Avro DML-smoke).

## RATIONALE
- #251 is api/core only: a defaulted column set via the API is serialized but not applied on read by the
  open fork's readers.
- SchemaParser serializes the default with no format-version parameter, so the key serializes regardless
  of the table's format version.

## STEPS
0. [x] Persist this checklist.
1. [ ] REACHABILITY recon: how can a default be SET from the harness?
       (a) Spark SQL `ALTER TABLE t ADD COLUMN c <ty> DEFAULT <v>` — does the fork's SparkTable accept
           it (SupportsColumnDefaultValue) or reject/ignore? (expected: reject/ignore — bug1 root.)
       (b) Iceberg API `table.updateSchema()...` — is there an operation to set initial/write-default,
           or only the NestedField builder (create-time)? Determines if read-apply is testable at all.
       (c) Can we inspect the persisted schema JSON (metadata) for initial-default/write-default?
2. [ ] Build the tests that ARE reachable, each a CHARACTERIZATION PIN of current behaviour:
       - `fork.colDefault.addColumnDefaultRejected` (or ignored) — customer path via Spark SQL.
       - `fork.colDefault.readsNullNotDefault` — IF a default can be set (API), pre-existing rows read
         NULL (pins the missing read-apply). If not settable, pin that it's unsettable.
       - `fork.colDefault.v2PersistsNoGate` — IF settable, a v2 table's schema JSON carries the default
         with no format-version parameter; assert persistence + document that a stock 1.5.2 reader ignores the key.
3. [ ] Smoke narrow → confirm behaviour matches the audit; adjust pins to ACTUAL behaviour.
4. [ ] Full both-mode verify, 0 failures (new pins green).
5. [ ] Record run; commit + push; update PROJECT-PLAN + ICEBERG-FORK-AUDIT.md (tests now cover #251).

## STATUS: step 1 (reachability recon).

---
## APPENDED 2026-07-20 — RECON DONE + FINDING + PINS BUILT (do not rewrite above; this supersedes STATUS)

### CRITICAL CORRECTION to the GOAL premise
The GOAL above assumed #251 is IN the artifact and we'd pin "durable SchemaParser serialization / v2
persists a v3 default with no gate." That is WRONG for the DEPLOYED artifact:
- **#251 is NOT in `com.linkedin.iceberg` 1.5.2.15** (the artifact the harness actually loads).
  Compiler-proven: `Types.NestedField.builder()/initialDefault()/writeDefault()` and `SchemaParser`
  default fields are ABSENT. #251 lives on the `openhouse-1.5.2` branch HEAD (d1603c807), which
  POST-dates the 1.5.2.15 release. So the branch-only "v2 persists v3 default, no format-version parameter" behaviour is
  **not present in what OpenHouse runs today** (the released 1.5.2.15). No serialization path exists to test on the release.

### What 1.5.2.15 ACTUALLY does with the customer path (runtime-measured, both parquet+orc)
`ALTER TABLE t ADD COLUMN c int DEFAULT 5`:
1. **Accepted** at Spark parse time (no error) — Spark 3.5 owns the DEFAULT grammar.
2. **Silently dropped** from the persisted Iceberg schema — `DESCRIBE` shows `c|int|null`, no default
   metadata. Nothing round-trips to a reader.
3. **Not backfilled on read** — pre-existing rows read NULL, not 5.
4. **Not applied on write** — an INSERT omitting c is REJECTED with
   `INCOMPATIBLE_DATA_FOR_TABLE.CANNOT_FIND_DATA` (same root as bug1 — no v2 column-default write wiring).
Net: the DEFAULT clause is accepted at parse time but not persisted, read-applied, or write-applied.

### PINS BUILT (green, both modes-independent block)
`fork.colDefault.addColumnInert @ parquet` / `@ orc` — hard-assert all 4 behaviours above.
When OpenHouse bumps to a #251-containing artifact (and/or wires SparkTable), every assert flips → the
tests fail loudly → someone re-audits against that build. That is the tripwire's whole point.

### STEP STATUS (final)
0.[x] persisted  1.[x] reachability recon (Spark accepts, Iceberg drops)  2.[x] pins built (2 cases)
3.[x] smoked narrow → behaviour reconciled to ACTUAL (accepted at parse, not persisted; not "rejected")  4.[~] full STUB
gate running; block is HTS-independent so STUB fully exercises it  5.[ ] record run + commit + push +
update PROJECT-PLAN + ICEBERG-FORK-AUDIT.

### OPEN DECISION for user (surfaced in chat, not closed here)
Whether to ALSO build #251 tests against a NEWER fork artifact that contains d1603c807 (would require a
build/version bump — out of the "test what's deployed" fast inner loop). Default if no opinion: NO — pin
current reality only; the tripwire catches the bump.

---
## TABLED 2026-07-20 (owner)
Column defaults tabled. Owner: not fundamentally broken; read-apply gap (if any) implemented elsewhere
(likely the private Spark reader, not exercised by this OSS-Spark harness). Retracted the read-apply
"bug" claim; readApplyProbe is DIAG-only now. Un-table only with the private Spark reader available.
