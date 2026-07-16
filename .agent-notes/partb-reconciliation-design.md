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

**Architectural anchor (verified):** only the **server** writes `metadata.json`
(`OpenHouseInternalTableOperations:363-364` is the sole `TableMetadataParser.write`; the client
`OpenHouseTableOperations` has none). The **client** writes the data files + manifests to storage and
PUTs the server the **snapshot JSON + declared base** (`commitSnapshots:361-377`,
`jsonSnapshots(...SnapshotParser::toJson)`); the server builds the `TableMetadata` and writes the one
`metadata.json`. Two consequences that decide the design: (1) the server is the **single serialization
point and sole metadata writer**, and already builds metadata from "incoming snapshots + a base" on
every commit — so reconciliation belongs there (Approach A is a change to a branch it already runs);
(2) a rewrite's physical outputs (compacted data files + manifests) are **already materialized in
storage by the client** before the server sees the commit, so a client-retry (Approach B) re-runs and
re-sends work that is already done — the wrong shape. The stale-base merge the guards defend against
happens **server-side**, inside the server's own `applyUpdates`, not in a client write race.

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

## GATING QUESTION — ANSWERED (2026-07-16)

**Why are client commit retries suppressed?** The answer is **correctness (category c), not
housekeeping.** Both guards are one design aimed at the **silent-rebase / subtractive-snapshot-merge**
bug: a stale append/overwrite commit whose `SNAPSHOTS_JSON` list gets applied onto a
concurrently-advanced base would **drop the snapshots the concurrent writer added** (data loss). The
in-code evidence is explicit:
- `abortIfWriterBaseDivergedFromCatalog` Javadoc (`OpenHouseInternalTableOperations:588-602`): guards
  the case where `BaseTransaction.applyUpdates` "re-stamps the writer's original `COMMIT_KEY` on top
  of a concurrently-advanced base" and "a subtractive merge would silently expire" concurrent
  snapshots.
- `failIfRetryUpdate` (`:642-664`) error text: the resubmitted version "is stale, please consider
  retry **from application**" — push retry up to a fresh job load, don't let the client silently
  re-apply. Its Javadoc scopes it to "Iceberg built-in retry in `PropertiesUpdate#commit()`" — the
  in-client loop that `commit.retry.num-retries=0` disables. No comment anywhere ties the suppression
  to orphaned `metadata.json` (that's only an incidental side effect of aborting before the write).

**Does a rewrite-scoped retry reintroduce the bug? No** — the hazard is specific to the un-validated
subtractive snapshot-list merge, which append/overwrite use. A rewrite is an Iceberg `RewriteFiles`
(`operation = replace`) re-applied through content-aware `RewriteFiles.validateFromSnapshot(...)`,
which detects the only true conflict (an intervening removal of / delete on an `F_remove` file) and
otherwise commutes with appends. For rewrites, retry *replaces* the silent-rebase risk with a
validation check rather than reintroducing it.

**CRITICAL CAVEAT that reshapes the recommendation:** OpenHouse's **forked iceberg-core builds the
retry base from the `COMMIT_KEY` property, not from a fresh on-disk refresh**
(`OpenHouseInternalRepositoryImpl:192-196` comment). So a naive re-enable of retries would resubmit
the **same stale** `COMMIT_KEY`, re-tripping both guards — it would not rebase. Approach B is
therefore **not a config flip**: it requires (i) changing the **iceberg fork's** retry to genuinely
refresh + re-derive `COMMIT_KEY` for rewrites, and (ii) exempting rewrites from both server guards.
That spans **two deployable artifacts** (the `com.linkedin.iceberg` fork + the tables service) and
couples Part B to a fork release picked up by every Spark client.

## Recommendation — Approach A (revised)

Given the fork-retry finding, **A (server-side replay) is now the recommended path**, reversing the
initial lean toward B. A lives entirely in OpenHouse's own commit core (`internalcatalog` +
`services/tables`, both already deployed as one unit) and is **transparent to clients** — the server
simply succeeds where it used to 409, with no new client behavior and no iceberg-fork rebuild/redeploy
across the fleet. B's only advantage (reusing the client's `RewriteFiles` validation) is undercut by
the fork's broken retry-refresh, which would itself have to be fixed in the fork.

The server already has what A needs: `FileIO`/`Storage` (`OpenHouseInternalTableOperations:363-364,
808`), the partition spec (`metadata.spec()`), and the incoming snapshot's manifests. A's validation
is a **bounded** slice of Iceberg's logic, not the whole surface: for `operation = replace` on a
diverged base, read the incoming manifests → `F_remove` / `F_add`; confirm every `F_remove` is still
live in the current base `B1` and no delete-file added since `B0` targets it; if clean, produce the
rebased snapshot on `B1` and commit; else abort as today. Only rewrites take this path — append/
overwrite keep the existing reject-on-divergence guard untouched.

## Implementation + test plan (Approach A)

1. In `OpenHouseInternalTableOperations.doCommit`, before `abortIfWriterBaseDivergedFromCatalog`
   aborts: if the incoming snapshot's `operation = replace` AND the base diverged, enter a
   rewrite-rebase path instead of aborting.
2. Read the incoming snapshot's manifests (via `FileIO`) → `F_add` (ADDED entries) and `F_remove`
   (DELETED entries). Read the current base `B1`'s current data-file set + any delete files added
   since `B0`.
3. Validate the `RewriteFiles` precondition on `B1`: every `F_remove` is still live in `B1`, and no
   delete-file added since `B0` targets rows in `F_remove`. If violated → abort as today (genuine
   conflict).
4. If clean → build the rebased snapshot on `B1` (current files − `F_remove` + `F_add`, carrying the
   rewrite's sequence-number semantics), write the new `metadata.json`, swap the pointer, return
   success. The client sees a normal 200 — transparent rebase.
5. Keep append/overwrite on the existing reject path (untouched); scope the whole new path to
   `operation = replace`.

**Tests** (existing delta-harness, PR #9/#11, already drives real OpenHouse concurrent commits):
- Disjoint: append to hot partition concurrently with `rewrite_data_files` on a settled partition →
  both commit, rewrite's files replaced, appended rows survive, no hard failure.
- True conflict: an intervening DELETE/MERGE (position/equality delete) on a file the rewrite is
  compacting → must still reject (no silent data loss).
- MoR: rewrite over a partition carrying a live position delete → correct precondition handling.
- Regression: append-vs-append silent-rebase guard still fires (A must not weaken it).

**Fallback (B)** only if A proves intractable: fix the `com.linkedin.iceberg` fork's retry to refresh
+ re-derive `COMMIT_KEY` for rewrites, exempt rewrites from `failIfRetryUpdate` + the base-CAS, and
re-enable `commit.retry.num-retries` for replace ops. Larger blast radius (fork release across all
Spark clients).

## Note on ANALYZE depth at scale (item 9)

Target sizing: max ~100M files/table, typically <1M. The `ANALYZE ... COMPUTE CLUSTERING QUALITY`
depth sweep uses a windowed running sum with no `PARTITION BY` (single partition). At ~1M files (the
common case) this is trivial; at the 100M ceiling it funnels ~200M events through one task — slow but
executor-side and spillable (no driver OOM; the driver-collect risk was already removed). Optional
safeguard if the ceiling matters: a file-count threshold above which depth is sampled or bucketed
(approximate, fully parallel). Not required for the common case.
