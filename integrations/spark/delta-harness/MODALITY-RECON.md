# Modality-hazard recon — code-verified answers for the predicted breaks

Companion to FEATURE-ANALYSIS-PLAN.md. Each hazard was predicted by the state-flow model (a
destroyer's D(O) intersecting a feature's consumed state S(F)) and then verified in source
([file:line]) or disassembled fork bytecode ([BYTECODE]). None of these has been run live yet —
each ends with the test that would demonstrate it (execution phase, gated on the plan).

## H1 — Streaming checkpoint × snapshot expiration — CONFIRMED HAZARD (G11's streaming twin)
The Spark streaming source persists a raw `StreamingOffset(snapshotId, position, ...)` in the
checkpoint — no table UUID, no lineage guard [BYTECODE StreamingOffset]. On restart,
`SparkMicroBatchStream.planFiles/latestOffset` re-resolve the id and
`validateCurrentSnapshotExists` throws `IllegalStateException("Cannot load current offset at
snapshot %d, the snapshot was expired or removed")`; intermediate holes throw via
`SnapshotUtil.snapshotAfter` [BYTECODE]. The `streaming-skip-delete/overwrite-snapshots` options
gate *operation type* only — they are NOT an escape for expired snapshots. Net: a stream paused
longer than the expiration window (OpenHouse job default: 3-day TTL) is **bricked on restart**
until the checkpoint is manually reset (losing exactly-once continuity).
**Test:** stream → checkpoint → stop → expire past the offset → restart → assert the typed
IllegalStateException. *(Honest-loud error, but undocumented window and no recovery = tier BROKEN
by our contract scale... strictly: consistently-partial-without-documentation.)*

## H2 — CDC/changelog over expired lineage — CONFIRMED HAZARD (with a silent variant)
Changelog scans validate only the ENDPOINTS: expired start/end id → hard
`IllegalArgumentException("Cannot find the starting/end snapshot")` [BYTECODE BaseIncrementalScan].
But (a) **timestamp-based bounds silently SHIFT** to the oldest surviving ancestor
(`SnapshotUtil.oldestAncestorAfter`), and (b) an expired **intermediate** snapshot truncates the
`ancestorsBetween` walk — both produce a changelog that **silently under-reports changes**. A CDC
consumer reconciling downstream state from such a view misses rows with no signal.
**Test:** s1..s5, expire s2; changelog(start=s1,end=s5) → characterize (error or under-report);
changelog(start-timestamp before s2) → assert whether the emitted change-set silently shrank.

## H3 — Column tags (and field docs) × RTAS — CONFIRMED HAZARD (G10's siblings)
`columnTags` is a field of the SAME `policies` blob as retention
(services/tables Policies.java:43; client merge in OpenHouseTableOperations.java:277-324). RTAS
wipes the policies plane (G10, demonstrated) → **RTAS wipes PII column tags** by the identical
mechanism. Separately, field `doc` strings ride the schema, and `replaceTransaction` derives a NEW
schema from the SELECT — docs/tags on the old schema are not inherited. Governance metadata
(PII marking!) silently destroyed by a data-shaped operation.
**Test:** SET TAG=(PII) + field doc → RTAS → assert tags/doc present (expected fail → extend G10).

## H4 — Lock × maintenance — CONFIRMED OPERATIONAL HAZARD (lock starves maintenance)
Maintenance commits (expire, compaction) are ordinary snapshot-bearing commits → they hit the
`LOCKED_TABLE_OPERATION` gate (IcebergSnapshotsServiceImpl.java:68-84). The scheduler has **no lock
filter** (no lockState read anywhere in apps/spark jobs client/scheduler — grep clean). So a locked
table is selected by the jobs, which then fail at commit, every cycle, for the lock's lifetime:
snapshots/files accumulate unboundedly. A protective feature turns into maintenance starvation.
(Also recall G2: RTAS *bypasses* this same gate — the lock blocks upkeep but not replacement.)
**Test:** lock T → run expiration path → assert LOCKED_TABLE_OPERATION + snapshot count unchanged.

## H5 — Retention (partition TTL) × branches — DEFENDED (with a residual edge)
Retention's DELETE targets main only (Operations.java:322-326); expire protects branch-reachable
snapshots; orphan deletion computes reachability from ALL in-metadata snapshots and skips
metadata/backup files (Operations.java:106-164). So branch data survives retention+expire+OFD under
normal operation. **Residual edge:** OFD reachability is only as good as surviving metadata — a
branch-intermediate snapshot expired by G11's mechanism makes its *uniquely-referenced* files
orphan-eligible (relevant when branch history includes deletes/overwrites, where old files are not
carried forward by the head).
**Test:** branch B pins TTL-expired data → retention → expire → OFD → branch still fully readable.

## H6 — Rename table × consumers — MOSTLY CONTINUOUS (one honest edge)
Rename is an UpdateProperties transaction on the SAME metadata (re-stamps openhouse.tableId/
databaseId/tableURI only — OpenHouseInternalCatalog.java:212-244) + HTS re-key. Branch refs, UUID,
snapshot log, schema history all survive. Streaming checkpoints bind by table IDENTIFIER: a stream
resumed against the new name continues correctly (offsets are snapshot ids); against the old name it
fails NoSuchTable (honest). No UUID guard exists to catch a wrong-table resume if the old name is
later REUSED by a new table — that stale-checkpoint-vs-recreated-name case is the residual hazard.
**Test:** rename with branch + checkpoint; resume old/new name; recreate old name and resume → ?

## H7 — wap.enabled=false × existing branches — DEFENDED (branches are not WAP-gated)
`write.wap.enabled` is consulted server-side ONLY by the RTAS guard
(OpenHouseInternalRepositoryImpl.java:342,350-364); no CREATE BRANCH / branch_ write / fast_forward
path reads it. Disabling WAP strands only **staged `wap.id` snapshots** (G4), not named branches.
**Test:** branch write + read still work after the toggle (plus the G4 staged-strand assert).

## H8 — ADD COLUMN × explicit-column INSERT — CONFIRMED COMPOSITION HAZARD
The partial-column INSERT rejection (CANNOT_FIND_DATA — knownBugs) is ENGINE-layer (Spark v2
TableOutputResolver + Iceberg 1.5 SparkTable not advertising column defaults; no OpenHouse code
forces `required`). Composition: `ADD COLUMN d` silently converts every previously-complete
`INSERT INTO t (a,b,c)` into a partial insert → **every existing explicit-column writer breaks
immediately** with CANNOT_FIND_DATA until rewritten. Schema evolution is NOT writer-backward-
compatible in this stack — contrary to ANSI SQL (omitted columns default to NULL) and to the
"evolution is safe" contract Iceberg markets. This weaponizes the standing bug: ADD COLUMN is a
fleet-wide writer-breaking event.
**Test:** writer INSERT (a,b,c) green → ADD COLUMN d → identical statement now fails typed.

## Cross-cutting classes
- **H1/H2 = G11's class**: a consumer persists a raw snapshotId (checkpoint, changelog bound,
  branch ref ancestry) with no pin/guard, and scheduled expiration is the destroyer. Same fix shape:
  consumer-pinning (industry model C) or reachability retention (model A).
- **H3 = G10's class**: governance state lives in a plane wholesale-replace silently drops.
- **H4 inverts a guard**: the lock (protector) starves maintenance while NOT stopping RTAS (G2) —
  the guard is simultaneously too strong (upkeep) and too weak (replace).
- **H8 is a composition**: two individually-known behaviors multiply into a fleet-scale break.
