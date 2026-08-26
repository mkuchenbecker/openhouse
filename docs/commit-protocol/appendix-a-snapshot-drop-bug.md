# Appendix A: The Snapshot-Loss Incident and Fix #612

Repo: `/home/user/openhouse` (fork `mkuchenbecker/openhouse` with upstream `linkedin/openhouse` history; local history is grafted/shallow at ~50 commits).

## TL;DR

**Fix commit: `940781958e20c40a5764cda147df9b7613ed2133` — `fix(catalog): abort doCommit on stale-base divergence (#612)`, authored 2026-05-29 by Mike Kuchenbecker.** It adds a catalog-level compare-and-swap (CAS) in `OpenHouseInternalTableOperations.doCommit` that aborts the commit with `CommitFailedException` when the writer's declared base (`COMMIT_KEY` property) does not match the catalog's actual current base. Without it, a stale writer commit that had been silently rebased by Iceberg's `BaseTransaction.applyUpdates` would carry a stale full-snapshot-list payload, and the server's *subtractive* snapshot merge would compute the concurrently-added snapshot as "to remove" and silently expire it — the dropped-snapshot lost update (internal incident-12185, 2026-05-25).

---

## 1. The vulnerable code path (root cause)

### Architecture context

OpenHouse's Tables service commits snapshots via properties smuggled through an Iceberg transaction:

- **Client → server payload staging** — `services/tables/.../repository/impl/OpenHouseInternalRepositoryImpl.java`, `save()` (update branch, current HEAD):
  - line 179: `table = catalog.loadTable(tableIdentifier);` then `table.newTransaction()`
  - line 181: `updateEligibilityCheck(table, tableDto)` → `versionCheck` (line 451) compares the client's declared `tableVersion` (path of the base `metadata.json`) against the *loaded* table's `tableLocation`. **This check runs against the writer's own loaded view, at request-validation time — not at commit time.**
  - line 187: `doUpdateSnapshotsIfNeeded(...)` stages `SNAPSHOTS_JSON_KEY` (the client's **entire** snapshot list, serialized) and `SNAPSHOTS_REFS_KEY` as *table properties* on the transaction.
  - line 196: `updateProperties.set(COMMIT_KEY, tableDto.getTableVersion()).commit();` — stages the writer's declared base metadata.json path as the `commitKey` property.
  - line 216: `transaction.commitTransaction();`

- **Server-side apply** — `iceberg/openhouse/internalcatalog/.../OpenHouseInternalTableOperations.java`, `doCommit(base, metadata)`. The snapshot state to persist is reconstructed by a **subtractive merge**: whatever is in the current metadata but *not* in the client's `SNAPSHOTS_JSON_KEY` payload gets **removed**.

### The subtractive merge (current HEAD, `OpenHouseInternalTableOperations.java:314-354`; pre-fix at `9407819^` lines ~305-345 — identical logic)

```java
if (serializedSnapshotsToPut != null) {
  List<Snapshot> snapshotsToPut = SnapshotsUtil.parseSnapshots(fileIO, serializedSnapshotsToPut);
  ...
  // 1. Identify which snapshots are new vs existing
  Set<Long> existingSnapshotIds = metadataToCommit.snapshots()...;
  Set<Long> newSnapshotIds = snapshotsToPut.stream().map(Snapshot::snapshotId)...;

  // 2. Add new snapshots
  snapshotsToPut.stream().filter(s -> !existingSnapshotIds.contains(s.snapshotId()))
      .forEach(builder::addSnapshot);

  // 3. Remove snapshots that are no longer present in the client payload
  List<Long> toRemove =
      existingSnapshotIds.stream().filter(id -> !newSnapshotIds.contains(id))
          .collect(Collectors.toList());
  if (!toRemove.isEmpty()) {
    builder.removeSnapshots(toRemove);          // <-- drop happens here
  }
  // 4. Sync refs: refs not in payload are removed; payload refs are set
  ...
}
```

The removal branch exists legitimately to support snapshot expiration via `putSnapshots` (the client is authoritative over the full snapshot list). It is only safe **if the payload was computed against the same base the server is now committing on top of**. The bug is that this precondition could silently fail.

### Before the fix (`git show 9407819^`, `doCommit` try-block, lines 259-262)

```java
try {
  // Now that we have metadataLocation we stamp it in metadata property.
  Map<String, String> properties = new HashMap<>(metadata.properties());
  failIfRetryUpdate(properties);          // <-- only defense; see gap below
  restoreOverriddenProperties(properties);
```

There was **no comparison between the writer's declared base and the actual `base` argument** of `doCommit`.

## 2. The interleaving that drops the snapshot

From the fix commit message (incident-12185) and the added test's javadoc — server-side race, no threads needed to reproduce at the `doCommit` boundary:

1. Writer W loads the table at base **T_X** (metadata.json location `.../00001-....metadata.json`). `versionCheck` passes (it compares against W's own loaded view). W's transaction stages `COMMIT_KEY = T_X` and `SNAPSHOTS_JSON = snapshots(T_X) ± W's changes` — a list that does **not** contain any snapshot committed after T_X.
2. A racing commit R lands, advancing the catalog **T_X → T_Y**, where T_Y contains a new snapshot S_r (in the incident: snapshot `3635817277608242413`).
3. When W's `transaction.commitTransaction()` runs, Iceberg's **`BaseTransaction.applyUpdates` silently refreshes** the in-flight base from T_X to T_Y and **re-applies the staged updates** on top of it. The staged `PropertiesUpdate` re-stamps `COMMIT_KEY = T_X` and the stale `SNAPSHOTS_JSON` onto metadata now derived from T_Y. No exception, no client-visible retry — a *silent rebase*.
4. `doCommit(base = T_Y, metadata = T_Y + stale properties)` runs:
   - `failIfRetryUpdate` sees `COMMIT_KEY = T_X` for the **first** time (its dedupe `CACHE` at line 93/648 only catches Iceberg's built-in *retry after a failed doCommit* re-sending the same key; the silent rebase happens *before* the first `doCommit` attempt) → passes.
   - The subtractive merge computes `toRemove = T_Y.snapshots() − stalePayload = {S_r}` and calls `builder.removeSnapshots({S_r})`.
   - The new metadata.json is written and `houseTableRepository.save()` persists it. **S_r — a durably committed, acknowledged snapshot — is gone.** No error anywhere.

Key point: this is a **server-side** lost update inside the internal catalog's commit path. The client-visible `versionCheck` (repository line 451) cannot catch it because it runs before the race window; `failIfRetryUpdate` cannot catch it because the first `doCommit` attempt is already poisoned.

## 3. The fix (`9407819`, present at current HEAD)

Two changes in `iceberg/openhouse/internalcatalog/src/main/java/com/linkedin/openhouse/internal/catalog/OpenHouseInternalTableOperations.java`:

**(a) Call site** — HEAD line 269 (fix diff hunk at old line 259):

```java
Map<String, String> properties = new HashMap<>(metadata.properties());

abortIfWriterBaseDivergedFromCatalog(base, metadata);   // NEW — must run before
failIfRetryUpdate(properties);                          // failIfRetryUpdate strips COMMIT_KEY
```

**(b) The CAS itself** — HEAD lines 604-635:

```java
private void abortIfWriterBaseDivergedFromCatalog(TableMetadata base, TableMetadata metadata) {
  if (base == null || base.metadataFileLocation() == null) {
    return;   // initial CREATE / mid-CREATE constructed metadata — nothing to defend
  }
  if (!metadata.properties().containsKey(CatalogConstants.SNAPSHOTS_JSON_KEY)) {
    return;   // not a snapshot-bearing writer commit (rename, property-only, internal writes)
  }
  String actualBase = base.metadataFileLocation();
  String writerClaimedBase = metadata.properties().get(CatalogConstants.COMMIT_KEY);
  if (writerClaimedBase == null) {
    return;   // wholesale replace/create paths (replaceTable, stage-create/replace) are
              // authoritative over the snapshot set — intentionally undefended
  }
  if (CatalogConstants.INITIAL_VERSION.equals(writerClaimedBase)
      || !new org.apache.hadoop.fs.Path(writerClaimedBase).toUri().getPath()
          .equals(new org.apache.hadoop.fs.Path(actualBase).toUri().getPath())) {
    throw new CommitFailedException(
        "Cannot commit: writer's declared base [%s] does not match the catalog's current "
            + "base [%s] for table %s. A concurrent commit landed between the writer's "
            + "loadTable and commit. Refresh and retry.",
        writerClaimedBase, actualBase, tableIdentifier);
  }
}
```

### Why it works

- It converts the silent lost update into a **retriable `CommitFailedException`** thrown *before* any metadata is written or persisted. Iceberg's commit loop (and/or the application) then refreshes and recomputes against T_Y, where a recomputed append/expire keeps the racing snapshot.
- It runs **before** `failIfRetryUpdate`, which removes `COMMIT_KEY` from the local properties copy (ordering is load-bearing; documented in the javadoc, lines 598-599).
- Paths are URI-normalized via Hadoop `Path.toUri().getPath()` on both sides, so scheme/authority differences (`hdfs://nn/path` vs `/path` — HTS persists scheme-less, see `doCommit` comment at line 261) don't cause false aborts. This mirrors `versionCheck`'s `getSchemeLessPath` semantics.
- A `COMMIT_KEY` of `INITIAL_VERSION` against an existing persisted base is also aborted (a create raced against an existing table).

### Deliberate scope exclusions (per commit message and code comments)

- Commits with **no `COMMIT_KEY`** (replaceTable, stage-create, stage-replace) are not defended — they are wholesale-authoritative over the snapshot set by design.
- Commits with **no `SNAPSHOTS_JSON_KEY`** (rename, property-only, internal metadata-field writes) are skipped — they carry no stale snapshot list, so the subtractive merge never runs.

### Residual gaps (my assessment)

1. The replace/stage-replace paths can still clobber concurrent snapshots "by design" — a genuinely concurrent RTAS vs. append is resolved replace-wins. Upstream later gated RTAS behind a `replace.enabled` table property (`3faac06`, #640), which narrows this exposure.
2. The CAS compares metadata.json **paths**. If a base location were ever reused (it isn't in practice — locations are versioned `NNNNN-uuid.metadata.json`), the check would pass a stale base.
3. `failIfRetryUpdate`'s dedupe `CACHE` (line 93) is a static in-JVM cache — multi-node retry dedupe still isn't airtight, but the new CAS makes that mostly moot for the snapshot-loss class.
4. Property-only commits that concurrently race snapshot commits are unguarded, but they don't touch the snapshot list, so they can't drop snapshots (they could still lose a property-level update).
5. The check protects the internal catalog only when writers route through the Tables service (which always stamps `COMMIT_KEY` at `OpenHouseInternalRepositoryImpl.java:196`).

## 4. Test added by the fix

`iceberg/openhouse/internalcatalog/src/test/java/com/linkedin/openhouse/internal/catalog/OpenHouseInternalTableOperationsTest.java` — **`testDoCommitMustAbortStaleBaseRebaseToPreventSnapshotLoss`** (HEAD line 258; added by the fix diff, +102 lines):

- Builds a post-refresh base **T_Y** containing three snapshots (two writer-known + one racing) and **round-trips it through `TableMetadataParser`** so `base.metadataFileLocation()` is non-null — matching a base loaded from disk after `applyUpdates`' silent refresh (and ensuring the CAS actually executes).
- Sets properties as `applyUpdates` would leave them: stale `SNAPSHOTS_JSON` (racing snapshot omitted), `SNAPSHOTS_REFS` pointing main at the stale head, and `COMMIT_KEY` = a different metadata.json path (T_X).
- Asserts `doCommit(postRefreshBase, metadata)` throws `CommitFailedException` containing "Cannot commit", and that `houseTableRepository.save` is **never** invoked (the racing snapshot is never persisted out of existence).
- The javadoc documents it as a deterministic reproduction of incident-12185 (2026-05-25 WAR, snapshot `3635817277608242413` rebased out), and states it fails on the unfixed catalog.

Per the commit message, a black-box Spark concurrent-insert functional test (`SparkConcurrentInsertFunctionalTest`, explored in PR #614) was dropped because it only reproduced against the H2 test fixture, not production MySQL+HTS.

## 5. Timeline

| Commit | Date | Subject / role |
|---|---|---|
| `c9ccbdd` (#509) | 2026-05-15 | Metadata caching in the internal catalog — last major pre-incident change to this file; staleness-adjacent but a perf change, not the cause fix |
| *(incident-12185)* | 2026-05-25 | Production WAR: snapshot `3635817277608242413` silently rebased out by a concurrent commit |
| `d4fc9fe` (#619) | 2026-05-29 | Emergency rollback: revert all commits after `v0.5.417` (incident response) |
| **`9407819` (#612)** | **2026-05-29** | **THE FIX: `fix(catalog): abort doCommit on stale-base divergence`** — landed on top of the rolled-back tree. Author: Mike Kuchenbecker `<mkuchenbecker+github@linkedin.com>`; co-authored via Claude Code |
| `702a043` (#625) | 2026-06-01 | Re-applied the rolled-back commits; fix retained |
| HEAD `2a9dac8` | 2026-08-23 | Fix present at current HEAD (`OpenHouseInternalTableOperations.java:269, 604-635`; test at `OpenHouseInternalTableOperationsTest.java:258`) |

Note: the fix commit is a *child* of the rollback commit `d4fc9fe`, i.e. it was applied to the reverted (v0.5.417) tree and survived the later re-apply — it was never itself reverted.

## 6. Candidate enumeration (runners-up)

- **`9407819` (#612)** — the match, with certainty: the commit message and test explicitly describe "silently dropped a racing snapshot" / "prevent snapshot loss".
- `c9ccbdd` (#509) "Cache iceberg metadata..." — introduces `tableMetadataCache` and refresh caching; staleness-related but a performance change; not a snapshot-loss fix.
- `d4fc9fe` (#619) / `702a043` (#625) — incident-response rollback/rollforward around the same event; context, not the fix.
- `isStaleSnapshotError` (`OpenHouseInternalTableOperations.java:670`) — maps concurrent sequence-number `ValidationException` to a retriable 409; same bug *class* (concurrent snapshot commits) but predates the graft boundary locally and addresses a loud failure, not the silent drop.
- No other commit in the visible history touches snapshot-drop behavior (`git log --all -i --grep` over snapshot/drop/lost/race/concurrent/retry/stale confirms).

## Key file references (absolute paths, current HEAD)

- Fix + merge logic: `/home/user/openhouse/iceberg/openhouse/internalcatalog/src/main/java/com/linkedin/openhouse/internal/catalog/OpenHouseInternalTableOperations.java` — CAS call site line 269, CAS lines 604-635, subtractive merge lines 314-354, `failIfRetryUpdate` lines 642-664
- Test: `/home/user/openhouse/iceberg/openhouse/internalcatalog/src/test/java/com/linkedin/openhouse/internal/catalog/OpenHouseInternalTableOperationsTest.java` line 258
- Client-side staging: `/home/user/openhouse/services/tables/src/main/java/com/linkedin/openhouse/tables/repository/impl/OpenHouseInternalRepositoryImpl.java` lines 179-216 (`COMMIT_KEY` at 196), `versionCheck` lines 451-475
- Constants: `/home/user/openhouse/iceberg/openhouse/internalcatalog/src/main/java/com/linkedin/openhouse/internal/catalog/CatalogConstants.java` line 29 (`COMMIT_KEY = "commitKey"`)
