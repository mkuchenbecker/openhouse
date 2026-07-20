# delta-harness — OpenHouse audit findings

Two cross-cutting audits from the DDL planning recon (see `DDL-TEST-PLAN.md`, "Cross-cutting audits").
These are **OpenHouse product findings**, distinct from the harness's tagged test-skips in `BUGS.md`.
Each concrete instance becomes a test assertion where the harness can reach it.

---

## Audit A — Missing guards (an incompatible op breaks the table but isn't blocked)

The model of a *good* guard is `validateReplaceTable`: it rejects RTAS while WAP/replication is on —
`UnsupportedClientOperationException(RTAS_DISABLED, …)`. Wherever an op would corrupt the table, a
block of that shape should exist. Gaps, most-severe first:

| # | Op that breaks the table | Why it breaks (cite) | Severity | Recommendation |
|---|---|---|---|---|
| ~~G1~~ | ~~Source expiration/OFD vs. replica copy → dangling refs~~ **WITHDRAWN — invalid.** | Replication is a **snapshot walk**: the mover walks the primary's snapshot ancestry and copies the files each walked snapshot references; **expiration is itself a snapshot in that chain that replicates**. The walk is internally consistent and ordered by construction, so there is no independent deletion racing the copy and no dangling ref. (The commit path indeed does no `exists()` validation, but the snapshot-walk ordering makes that a non-issue, not a gap.) | — | None — mechanism is sound. |
| **G2** | **RTAS / REPLACE on a LOCKED table** (concrete bug) | The replace/stage-replace branches (`TablesServiceImpl.putTable:113-115`, `IcebergSnapshotsServiceImpl.putIcebergSnapshots:68-72`) only check replace privilege and **never reach** the `isTableLocked → LOCKED_TABLE_OPERATION` throw (`:125-130`/`:73-78`); `validateReplaceTable` checks RTAS/WAP/replication but **not lock**. A locked table can be fully replaced. **Demonstrated live** by `interact.rtas.onLockedTable`: the lock rejected an UPDATE, then `CREATE OR REPLACE` replaced the locked table (3 rows → 2). | breaks-lock-contract / data-loss | **File it.** One-line extension: add the `isTableLocked` check to both replace branches. Cleanest RTAS-guard analogue. |
| **G3** | Partition/clustering spec change on the replica commit path | `checkPartitionSpecEvolution` is inside the `!skipEligibilityCheck` block (`OpenHouseInternalRepositoryImpl:373-382`); replica commits skip it (`:294-308`) → replica spec can diverge from the physical layout of copied files. | breaks-replica (read path) | Validate spec compatibility even on the replica path. |
| **G4** | Toggle `write.wap.enabled` / `replace.enabled` freely (e.g. disable WAP with staged snapshots present) | These are plain `write.*` props, **not** `openhouse.`-prefixed, so `checkIfPreservedTblPropsModified` doesn't cover them; only RTAS is WAP-aware. Disabling WAP while staged snapshots exist strands them (subtractive merge `doCommit:337-344`). | breaks-time-travel | WAP-state guard analogous to RTAS: reject disabling WAP while unpublished staged snapshots exist. |
| **G8** | Main-affecting DDL while "on a branch" (`spark.wap.branch` set) — `ADD COLUMN` / `SET TBLPROPERTIES` / `WRITE ORDERED BY` | Schema/spec/props/sortOrder are **table-global** at every layer; the client's metadata-vs-snapshot commit split carries no branch dimension, and the server applies them via `setCurrentSchema`/`replaceSortOrder` (table-global). **No branch guard anywhere.** So the DDL silently mutates MAIN, not the branch. **Demonstrated live** by `branch.ddlLeak.addColumn` (ADD COLUMN on branch `leakbr` changed main's schema). | breaks-branch-isolation (silent) | Reject table-global DDL while operating on a branch (analogous to the RTAS-while-WAP block), or make it branch-scoped. |
| **G9** | **RTAS changes the partition spec / drops columns**, bypassing the update-path guards | `checkPartitionSpecEvolution` runs only inside `updateEligibilityCheck` (`OpenHouseInternalRepositoryImpl:373-383`, update branch of `save`); the replace/stage-replace branch (`:154-173`) never calls it, and `validateReplaceTable` (`:325-366`) checks RTAS-enabled/WAP/replication but **not** spec or schema compatibility — `replaceTransaction` re-creates the table definition wholesale. So the exact evolutions ALTER rejects (partition-spec change; DROP COLUMN — rejection confirmed live) are reachable via `CREATE OR REPLACE`. Same root shape as G2: the replace path dodges update-path guards. **Demonstrated live, both halves:** `interact.rtas.partitionSpecChange` (unpartitioned → `PARTITIONED BY (datepartition)` via RTAS, where ALTER is rejected) and `interact.rtas.dropsColumn` (RTAS projection dropped 4 of 6 columns, where ALTER DROP COLUMN is rejected). | breaks-guard-consistency | Run the update-path eligibility checks (spec evolution, schema-drop validation) on the replace path too — or explicitly document replace as the sanctioned escape hatch. |
| **G10** | **RTAS silently WIPES the `policies` plane** — retention/sharing/history policies are gone after a replace | Set `RETENTION = 30d` via SET POLICY, then `CREATE OR REPLACE`: afterwards `policies` is **absent** from the table properties while `openhouse.tableUUID` is preserved. The replace path re-runs create-time property computation, and the preserved-key checker *filters* `policies` out of creation props (`allowKeyInCreation`) instead of carrying the existing value forward — so the replace commits with no `policies` at all. A table's retention/sharing contract can be silently destroyed by any RTAS. **Demonstrated live** by `interact.rtas.props.reservedPlane`, and extended by `hazard.rtas.wipesColumnTags`: **PII column tags are wiped too** (columnTags is a field of the same policies blob — Policies.java:43), while column COMMENTS survive (star projection carries schema metadata) — the loss boundary is exactly the policies plane. User props, by contrast, survive and statement-declared props win (also verified live: `interact.rtas.props.{userSurvival,statementWins}`). | breaks-governance (silent retention/sharing loss) | Carry the existing `policies` blob across the replace (like tableUUID), or reject RTAS on tables with policies set until it does. Highest-severity member of the G2/G9 replace-path cluster. |
| **G11** | **Branch × expiration × merge: routine snapshot expiration destroys merge connectivity between live refs** | Mechanism (bytecode-verified in the fork's `iceberg-core`): `RemoveSnapshots` retention is **per-ref and head-anchored** — each branch keeps its most-recent `min-snapshots-to-keep`/age-window from its own head; **nothing protects the ancestry BETWEEN live refs** (branch point + intermediate commits are neither head → expired). `SnapshotUtil`'s ancestry walk **silently truncates at an expired hole and returns false**. Consequences, **all demonstrated live** (`interact.branch.expireMerge.*`): (a) `fast_forward` merge spuriously rejected `"main is not an ancestor of mb"` **even though main never advanced** — the pre-expiration control (`branch.fastForward.merge`) proves the identical merge works; (b) **cherry-pick of the branch head SUCCEEDS and silently LOSES the expired intermediate commit** (main 3→4 rows; B1's row gone — a partial merge presenting as success, the worst variant); (c) no rebase exists → the branch is **permanently unmergeable**; copy-out is the only recovery (data files survive: the job runs `cleanExpiredFiles(false)`); (d) **staged WAP snapshots are unreferenced → age-based expiration silently deletes them pre-publish**; the loss is loud only at publish (`Cannot apply unknown WAP ID`). OpenHouse's scheduled expiration job (**default 3-day TTL** even unconfigured, `SnapshotsExpirationSparkApp:44-46`) makes all of this automatic. Held today: refs can never dangle (builder validation) and stale writers cannot resurrect expired snapshots (CAS guard active for ref-bearing commits) — the defect is spurious rejection + silent partial merge + stranding, not corruption. | breaks-branch-workflow / **silent-data-loss on cherry-pick merge** | Expiration should retain the common ancestor + connectivity path between any two live refs (or refuse/warn when it would sever them); staged-WAP snapshots need a publish-window retention rule; the fast_forward error should distinguish "expired-hole" from a real non-ancestor. |
| **G12** | **A LOCK starves maintenance for its entire lifetime** (while NOT stopping RTAS — G2) | Maintenance commits (expire_snapshots, compaction) are ordinary snapshot-bearing commits and hit the `LOCKED_TABLE_OPERATION` gate (`IcebergSnapshotsServiceImpl:68-84`); the jobs scheduler has **no lock filter** (no lockState read in apps/spark jobs client/scheduler), so scheduled expiration/compaction selects the locked table and fails at commit, every cycle. Snapshots and files accumulate unboundedly for the lock's duration. **Demonstrated live** by `hazard.lock.starvesMaintenance` (expire rejected "locked", snapshot count frozen; unlock -> maintenance proceeds). Combined with G2 the guard is simultaneously too strong (blocks upkeep) and too weak (misses replace). | operational (unbounded metadata/storage growth) | Either exempt maintenance principals from the lock, or teach the scheduler to skip locked tables loudly (metric + log), and fix G2 so the lock actually means "frozen". |
| **G13** | **CDC changelog is unsupported over a MoR table whose update/merge wrote position-delete files** | `create_changelog_view` on a merge-on-read table after an UPDATE or MERGE throws `UnsupportedOperationException: "Delete files are currently not supported in changelog scans"` (Iceberg 1.5 changelog-scan limitation, surfaced through OpenHouse). MoR **delete-only** changelog and **all CoW** writers work; MoR update/merge do NOT — CDC is silently unavailable for exactly the write shapes a MoR table exists to optimize. A downstream CDC consumer breaks the first time an update/merge lands on a MoR table. **Demonstrated live** by `readerWriter.changelog.{update,merge}.mor`. | breaks-CDC-on-MoR | Document the MoR+CDC incompatibility prominently, or upgrade the changelog scan to apply delete files; at minimum surface a typed, actionable error naming the incompatibility rather than a bare UnsupportedOperationException. |
| **G14** | **`rewrite_data_files` leaves a DANGLING position delete** on a MoR table (delete not folded out) | Compacting a MoR table that carries a live position delete (`CALL rewrite_data_files(..., 'rewrite-all'=>'true')`) applies the delete to the rewritten data — the row set is correct and the deleted key never reappears — but it does **not** remove the now-dangling position-delete file: `.delete_files` (current snapshot) still shows 1 after the rewrite. The delete-file reference points at data already removed and lingers until `rewrite_position_delete_files` or `expire_snapshots` cleans it. Format-consistent (parquet/orc/avro). Not corruption — reads stay correct — but every reader keeps paying to consult a delete file that should have been folded, and metadata accretes. **Demonstrated live** by `maint.mor.rewriteDataFilesDanglingDelete @ mor-verify/{parquet,orc,avro}`. | maintenance-inefficiency (dangling delete metadata) | Enable/adopt `remove-dangling-deletes` on the rewrite path (later Iceberg), or document that `rewrite_data_files` must be paired with `rewrite_position_delete_files` to actually shed deletes. **ATTRIBUTION (fork audit):** this is **stock Apache Iceberg 1.5 behavior**, NOT a LinkedIn/OpenHouse fork change — `remove-dangling-deletes` folding landed in a later Apache release and is absent here; the fork's compaction commits (#189 budgeted rewrite ordering, #233 bin-pack sizing) do not touch delete resolution. So the pin flips on a future **Iceberg bump**, not a fork fix. |
| **G5** | Branch/tag/ref ops removing `main` or a snapshot a retained ref targets | Refs stored verbatim; subtractive merge (`doCommit:346-354`) removes refs absent from payload. Iceberg build-time catches some, but `main`-preservation/ordering unmodeled. | breaks-time-travel | Validate `main` survives and every retained ref resolves post-merge. Low priority (partly covered by Iceberg). |
| **G6** | Update/replica-commit carrying a different `format-version` | Force-set only at create (`:554-556`); not on update, not `openhouse.`-prefixed, skipped on replica. Iceberg blocks downgrades downstream. | annoyance | Low — document reliance on Iceberg's downgrade block, or pin explicitly. |
| **G7** | Replica commit rewriting reserved props / `policies` / `lockState` | `skipEligibilityCheck` is **all-or-nothing** — replica path skips preserved-prop + tableType validation wholesale (`:373-382`). A buggy mover could write a lock, flip retention, etc. | breaks-replica / policy-integrity | Narrow `skipEligibilityCheck` to an **allowlist** of what a replication commit may mutate, not a blanket bypass. |

**Root-cause clusters:** G3 + G7 both stem from `skipEligibilityCheck` (`OpenHouseInternalRepositoryImpl:284-308`)
being all-or-nothing; **G2 + G9 + G10 all stem from the replace path skipping update-path guards**
(lock, spec evolution, schema-drop, policies preservation) — all three now demonstrated live by the
`interact.rtas.*` tests. Cleanest single fixes: **G2** (lock-on-replace) and **G10** (carry policies
across replace) — both concrete, both data/governance-loss class. (G1 withdrawn — the
snapshot-walk replication mechanism is sound; no dangling-ref race exists.)

**Observation (needs recon before calling it a gap): `register_table` is NOT blocked.** The Spark
procedure `register_table` with a real metadata file successfully imported a table into the managed
catalog (readable, 3 rows — `surface.pin.importProcs`). If that path skips create-time defaulting /
policy stamping / HTS bookkeeping, it is a G-class bypass of the managed-create contract; if it flows
through the normal catalog register hook, it's fine. Unverified which — queued.

**Behavior note (not a guard gap): rolled-past snapshots are silently expirable.** After
`rollback_to_snapshot`, the snapshots rolled *past* are unreferenced; the history-policy-driven
expiration job (`TableSnapshotsExpirationTask:44-58` → ref-aware Iceberg `expireSnapshots`,
`apps/spark/.../Operations.java:268-287`) deletes them on its next run — the rollback becomes
permanent with no signal. Pinning with a tag prevents it. (Probed context in INTERACTION-AUDIT.md §4.)

---

## Audit C — Reachability / product gaps (surfaced building the REST shim)
- **Customer-facing undrop is not wired.** The public Tables `DELETE /v1/databases/{db}/tables/{t}`
  hard-codes `purge=true` (`OpenHouseInternalRepositoryImpl.deleteById` → `catalog.dropTable(id, true)`),
  so a customer DROP can never populate the soft-deleted store — in **any** environment (Docker
  included). Soft-delete is reachable only via the internal HouseTables admin endpoint
  (`DELETE /hts/tables?isSoftDelete=true`). The Tables API exposes `GET /softDeletedTables`, `PUT
  /restore`, `DELETE /purge`, but nothing customer-facing feeds them. So `drop → undrop` is HTS-admin-only.
- **The embedded harness uses a soft-delete STUB.** In `SpringH2TestApplication` the active
  `HouseTableRepository` is a `@Primary` in-memory `HouseTablesH2Repository` (a HashMap
  reimplementation), not the real `HouseTableRepositoryImpl`. So any undrop test against the embedded
  server tests the shim's own logic, not production — hence `control.undrop` is tagged SKIP. Real
  fidelity needs the embedded HTS (`SpringH2HtsApplication`, which runs the genuine soft-delete JPA
  code) wired in with the stub de-`@Primary`-ed — a substantial harness restructure (REST-FIDELITY-EVAL.md).

## Audit B — Error-message readability (a stacktrace is "dumb")

Grade for a non-expert SQL user: **GOOD** = names the table + the fix · **MEH** = correct but jargony
/ dumps a raw object · **BAD** = stacktrace / `[INTERNAL_ERROR]` / NPE / HTTP 500 / cryptic.

### Systemic (fix these once, upgrade everything)
- **S1 — the client drags the whole error body (incl. stacktrace) into the message.** Every
  `ErrorResponseBody` carries a ≤6000-char `stacktrace` field (`OpenHouseExceptionHandler:429-479`),
  and on commit the java client wraps a 400 as `BadRequestException("400 , " + fullBodyJson)`
  (`OpenHouseTableOperations:436-440`; also `WebClientResponseWithMessageException:38-43`). So even a
  GOOD server sentence reaches a noob buried in `400 , {json + java frames}`. **Surfacing only
  `ErrorResponseBody.message` upgrades nearly every 4xx path at once — the highest-leverage fix.**
- **S2 — the catch-all 500 is `exception.toString()`.** `handleGenericException:402-415` (and the
  `IllegalStateException`/`InvalidTableMetadataException` → 500 handlers) surface a bare Java class
  name (e.g. `java.lang.NullPointerException`) at HTTP 500. Any un-typed failure is BAD by definition.

### Worst offenders (file first)
1. **DELETE on a nested struct field → `[INTERNAL_ERROR] … NullPointerException`** (optimizer NPE;
   already in `BUGS.md`). OpenHouse should pre-reject nested-field row-level DELETE with a typed message.
2. **Malformed replication interval → uncaught `NumberFormatException` → 500** (`IntervalToCronConverter:37`);
   `"3X"` is silently accepted as daily. Should be a typed 400 listing valid intervals. *(Corrects the
   plan's Phase-23 assumption of a clean "parse N" — it's actually a raw 500.)*
3. **Server 5xx on commit → `CommitStateUnknownException(rawBody)`** — scary wording + raw body.
4. **DROP/RENAME COLUMN dump full Iceberg `Schema.toString()` twice** inside
   `InvalidSchemaEvolutionException`'s template (`BaseIcebergSchemaValidator:125-160`) — the one-line
   fact ("dropping columns is not supported; recreate without column X") is buried in schema blobs.
5. **S1/S2 above.**

### Confirmed by execution (Phase 13)
- **DROP COLUMN message is worse than expected:** the message is `Column[foo_col_int] not found in
  newSchema` buried inside a **double** Iceberg schema dump — it **never says "you cannot drop
  columns."** A novice has no idea drops are unsupported. (Type: Iceberg `BadRequestException`,
  wrapped as `"400 , {body}"` per S1.)
- **RENAME COLUMN is a SILENT NO-OP** (not a message issue but a silent-failure defect; see
  `BUGS.md`) — neither errors nor renames. The worst readability outcome is *no signal at all*.
- **`SET TBLPROPERTIES('policies'='x')` throws a client-side Gson error**, not the clean reserved-key
  guard: `com.…relocated.…gson.JsonParseException: OpenHouse: Cannot convert policies string to
  policies object`. The `policies` value is parsed on the client before the `ALTER_RESERVED_TBLPROPS`
  server guard runs, so the user gets a parser stacktrace instead of "policies is reserved". (Other
  `openhouse.*` reserved keys DO hit the clean guard — verified `openhouse.tableUUID` → 400 "restriction".)
- **CREATE/DROP NAMESPACE surface the WRONG message:** both throw `UnsupportedOperationException:
  "Describing database is not supported"` — Spark calls `loadNamespaceMetadata` before create/drop, so
  a user issuing `CREATE NAMESPACE` / `DROP NAMESPACE` is told *describing* is unsupported. Should say
  "OpenHouse creates databases implicitly; CREATE/DROP NAMESPACE is unsupported."
- **Confirmed forced-override:** `CREATE … TBLPROPERTIES('format-version'='1')` reads back
  `format-version=2` — the user's value is silently overridden by the cluster default (not an error,
  not honored). `write.metadata.previous-versions-max` by contrast IS honored. The asymmetry is a
  "you didn't get what you set" surprise.

### MEH (correct but could name the fix / drop a raw dump)
reserved-tblprops (dumps a Gson diff), `ALTER_TABLE_TYPE` (no remedy named), GRANT-on-unshared (no
"enable SHARING" hint), REPLICA-UUID / "snapshot is invalid" (internal-sounding), namespace DDL
(generic engine message), partial-column INSERT (`CANNOT_FIND_DATA` jargon), `snapshot` procedure's
`"Cannot not find source table"` (double negative), and G11's `"main is not an ancestor of mb"`
(FALSE statement when the real cause is expired ancestry — actively misleading, see G11).

### GOOD (the model the project already knows how to write)
RTAS-disabled ("enable with ALTER TABLE … SET TBLPROPERTIES('replace.enabled'='true')"),
RTAS-while-WAP/replication, locked-table ops, history/retention/clustering validator bounds. These
name the table **and** the remedy — the bar the BAD tier should be raised to.

### Harness action
The negative tests already assert a message substring; **extend each to also assert the message is not
a raw stacktrace / `[INTERNAL_ERROR]` / 500** (a readability regression guard), and file every
BAD/MEH message here as a finding with the suggested wording above.
