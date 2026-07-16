# Part B — server-side reconciliation of OPTIMIZE vs. streaming commits

_Design of record. Session iceberg-optimize-semantics-43tl45, 2026-07-16._

## Goal

Eliminate the residual OPTIMIZE-vs-streaming commit conflict server-side. The Spark-side Part A
(snapshot hold-back + SNAPSHOT isolation + `use-starting-sequence-number`) keeps OPTIMIZE off the
actively-appended hot partition, so on-time streaming and OPTIMIZE already touch disjoint file sets.
The residual is **late-arriving data** landing in a partition OPTIMIZE is rewriting, plus the fact
that on ANY concurrent append the OPTIMIZE commit currently **fails outright** (see below). Part B
closes that on the one side we control: the OpenHouse catalog.

## Current state (verified — file:line)

Conflict handling is **pure compare-and-swap on the `metadata.json` pointer**, at three layers, with
**no content awareness and no rebase**:

1. Catalog base CAS — `OpenHouseInternalTableOperations.abortIfWriterBaseDivergedFromCatalog`
   (`iceberg/openhouse/internalcatalog/.../OpenHouseInternalTableOperations.java:604-635`): compares
   the writer's declared base pointer to the loaded base; mismatch → `CommitFailedException:629`.
   Its Javadoc (`:588-602`) explicitly frames reject-on-mismatch as closing a "silent-rebase" hole:
   a naive subtractive snapshot-set merge would drop snapshots a concurrent writer added.
2. Repository version check — `OpenHouseInternalRepositoryImpl.versionCheck:389-413`
   → `CommitFailedException:395`.
3. HTS optimistic lock — JPA `@Version` on `UserTableRow.version:28` + metadata-location CAS in
   `UserTableVersionMapper:21-47` → `EntityConcurrentModificationException` → HTTP 409.

Two facts that shape the design:
- **Client retries are forced OFF.** `OpenHouseInternalRepositoryImpl:197-203` overrides
  `commit.retry.num-retries=0`, and `OpenHouseInternalTableOperations.failIfRetryUpdate:642-664`
  hard-fails a re-submitted commit (5-min `COMMIT_KEY` cache). So OPTIMIZE gets exactly one shot; a
  single concurrent append fails it. **Why this suppression exists is the gating question (below).**
- **The server can see file-level detail but doesn't use it.** The payload is snapshot JSON +
  manifest-list pointer + summary (`SnapshotsUtil.parseSnapshots:33-47`); the server has `FileIO`
  (`:363-364`, `fileIOManager.getStorage:808`) and the partition spec (`metadata.spec()`,
  `rebuildPartitionSpec:500-518`), so it *could* read manifests to enumerate touched files/partitions
  — but no code does today. Reconciliation at `:314-354` diffs by snapshot-**id** only, against the
  writer's own base (not a concurrently-advanced base).

## Why disjoint rewrite + append is safe to combine

An OPTIMIZE commit is an Iceberg `RewriteFiles` (snapshot `operation = replace`): remove data files
`F_remove` (all in settled partitions), add compacted files `F_add`. A streaming `Append` only ADDs
files `F_appended` (in the hot partition) and never touches `F_remove`. The rewrite's only
precondition is "every file in `F_remove` is still live." Appends cannot violate it. So the two
commute: the correct merged result is `current_base` with `F_remove` replaced by `F_add`. This is
exactly the invariant Iceberg's `RewriteFiles.validateFromSnapshot(...)` conflict-detection enforces.
The only true conflict is an intervening commit that **removed** an `F_remove` file or added a
position/equality **delete** targeting rows in `F_remove` (a concurrent MERGE/DELETE/expire on the
same settled partition) — which Part A's hold-back already makes rare.

## Candidate approaches

### A. Server-side content-aware replay (the "eliminate deterministically" version)
On base divergence AND incoming `operation = replace`: read the incoming manifests → `F_add` /
`F_remove`; load current base `B1`; verify every `F_remove` is still live in `B1` and no intervening
delete-file targets it; if clean, replay as `Transaction.newRewrite().deleteFile(f).addFile(g)
.validateFromSnapshot(B1)...commit()` server-side and return 200 (transparent rebase); else reject as
today.
- **Pro:** deterministic; no client round-trips; works even with retries off.
- **Con:** large new correctness surface in the commit core — duplicates Iceberg client validation
  server-side, must handle MoR delete files / sequence numbers correctly, and directly overrides the
  maintainers' deliberate reject-on-rebase stance. Highest risk.

### B. Re-enable bounded, scoped client retry for rewrite ops (recommended first)
Lift the retry suppression **only** for `operation = replace` commits: allow
`commit.retry.num-retries > 0` and exempt rewrites from `failIfRetryUpdate`. Iceberg's client then
refreshes to `B1` and re-applies the `RewriteFiles` through its own `validateFromSnapshot` — the
battle-tested path. Part A already sets `use-starting-sequence-number=true` + SNAPSHOT isolation,
which is precisely what makes client-side rewrite retry safe against concurrent appends/deletes.
- **Pro:** minimal new server code; reuses Iceberg's proven conflict resolution; low correctness risk.
- **Con:** N client round-trips under contention (bounded); and it is only safe if the reason retries
  were suppressed does not also apply to rewrites (**gating question**).

### C. Hybrid
Keep server CAS; on rewrite-op conflict, return the current base pointer so the client rebases in one
fast hop; re-enable retry only for rewrites. Between A and B in effort.

## Recommendation

Pursue **B first**, fall back to **A** only if B is blocked. Rationale: B reuses Iceberg's proven
`RewriteFiles` rebase instead of re-implementing it in OpenHouse's commit core, and Part A already
configured the rewrite for exactly this. A is the deterministic ideal but is a large,
correctness-critical change that contradicts an explicit maintainer decision — worth it only if
client retry proves insufficient.

## GATING QUESTION (must answer before implementing B)

**Why are client commit retries suppressed** (`commit.retry.num-retries=0` at
`OpenHouseInternalRepositoryImpl:197-203`, and `failIfRetryUpdate`'s `COMMIT_KEY` cache at
`OpenHouseInternalTableOperations:642-664`)? Hypotheses to confirm via git history / PRs / design
docs: (a) preventing orphaned UUID-named `metadata.json` accumulation on retry; (b) avoiding double
HTS `@Version` bumps; (c) a past correctness incident with silent rebase. If the rationale is
rewrite-neutral (e.g. orphan cleanup), B is viable with orphan handling; if it is a correctness
concern that also covers rewrites, escalate to A.

## Implementation + test plan (for B, once the gating question is answered)

1. Thread the incoming snapshot `operation` into the repository/commit layer; gate the retry-config
   override + `failIfRetryUpdate` exemption on `operation = replace`.
2. Test with the existing delta-harness (PR #9/#11) which already drives real OpenHouse concurrent
   commits: add a case — append to the hot partition concurrently with a `rewrite_data_files` on a
   settled partition; assert both commit, the rewrite's files are replaced, the appended rows survive,
   and no retry storm/hard failure. Add the true-conflict case (delete on a rewritten file) and assert
   it still rejects.
3. Only if escalating to A: server-side manifest read + `newRewrite().validateFromSnapshot` replay,
   with MoR delete-file conflict tests.

## Note on ANALYZE depth at scale (item 9)

Target sizing: max ~100M files/table, typically <1M. The `ANALYZE ... COMPUTE CLUSTERING QUALITY`
depth sweep uses a windowed running sum with no `PARTITION BY` (single partition). At ~1M files (the
common case) this is trivial; at the 100M ceiling it funnels ~200M events through one task — slow but
executor-side and spillable (no driver OOM; the driver-collect risk was already removed). Optional
safeguard if the ceiling matters: a file-count threshold above which depth is sampled or bucketed
(approximate, fully parallel). Not required for the common case.
