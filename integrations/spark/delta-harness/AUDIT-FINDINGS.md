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
| **G1** | Source-side snapshot expiration / orphan-file-deletion vs. the verbatim replica snapshot copy | Snapshots copied verbatim, no path rewrite; commit path does **zero existence validation** (`SnapshotsUtil.parseSnapshots` only JSON-parses; `OpenHouseInternalTableOperations.doCommit:314-354` blindly `addSnapshot`/`setRef`; no `SnapshotInspector`). Deleting files the replica still references → **dangling refs**. | corrupts-data / breaks-replica | Server-side reject a snapshot PUT whose manifests aren't resolvable on the target FileIO **+** a mover ordering contract (copy files before metadata; don't expire until replicas advance). |
| **G2** | **RTAS / REPLACE on a LOCKED table** (concrete bug) | The replace/stage-replace branches (`TablesServiceImpl.putTable:113-115`, `IcebergSnapshotsServiceImpl.putIcebergSnapshots:68-72`) only check replace privilege and **never reach** the `isTableLocked → LOCKED_TABLE_OPERATION` throw (`:125-130`/`:73-78`); `validateReplaceTable` checks RTAS/WAP/replication but **not lock**. A locked table can be fully replaced. | breaks-lock-contract / data-loss | **File it.** One-line extension: add the `isTableLocked` check to both replace branches. Cleanest RTAS-guard analogue. |
| **G3** | Partition/clustering spec change on the replica commit path | `checkPartitionSpecEvolution` is inside the `!skipEligibilityCheck` block (`OpenHouseInternalRepositoryImpl:373-382`); replica commits skip it (`:294-308`) → replica spec can diverge from the physical layout of copied files. | breaks-replica (read path) | Validate spec compatibility even on the replica path. |
| **G4** | Toggle `write.wap.enabled` / `replace.enabled` freely (e.g. disable WAP with staged snapshots present) | These are plain `write.*` props, **not** `openhouse.`-prefixed, so `checkIfPreservedTblPropsModified` doesn't cover them; only RTAS is WAP-aware. Disabling WAP while staged snapshots exist strands them (subtractive merge `doCommit:337-344`). | breaks-time-travel | WAP-state guard analogous to RTAS: reject disabling WAP while unpublished staged snapshots exist. |
| **G5** | Branch/tag/ref ops removing `main` or a snapshot a retained ref targets | Refs stored verbatim; subtractive merge (`doCommit:346-354`) removes refs absent from payload. Iceberg build-time catches some, but `main`-preservation/ordering unmodeled. | breaks-time-travel | Validate `main` survives and every retained ref resolves post-merge. Low priority (partly covered by Iceberg). |
| **G6** | Update/replica-commit carrying a different `format-version` | Force-set only at create (`:554-556`); not on update, not `openhouse.`-prefixed, skipped on replica. Iceberg blocks downgrades downstream. | annoyance | Low — document reliance on Iceberg's downgrade block, or pin explicitly. |
| **G7** | Replica commit rewriting reserved props / `policies` / `lockState` | `skipEligibilityCheck` is **all-or-nothing** — replica path skips preserved-prop + tableType validation wholesale (`:373-382`). A buggy mover could write a lock, flip retention, etc. | breaks-replica / policy-integrity | Narrow `skipEligibilityCheck` to an **allowlist** of what a replication commit may mutate, not a blanket bypass. |

**Root-cause cluster:** G3 + G7 both stem from `skipEligibilityCheck` (`OpenHouseInternalRepositoryImpl:284-308`)
being all-or-nothing. Cleanest single fixes: **G2** (lock-on-replace) and **G4** (WAP toggle) — same
shape as the RTAS guard. Highest severity: **G1** (dangling refs).

---

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

### MEH (correct but could name the fix / drop a raw dump)
reserved-tblprops (dumps a Gson diff), `ALTER_TABLE_TYPE` (no remedy named), GRANT-on-unshared (no
"enable SHARING" hint), REPLICA-UUID / "snapshot is invalid" (internal-sounding), namespace DDL
(generic engine message), partial-column INSERT (`CANNOT_FIND_DATA` jargon).

### GOOD (the model the project already knows how to write)
RTAS-disabled ("enable with ALTER TABLE … SET TBLPROPERTIES('replace.enabled'='true')"),
RTAS-while-WAP/replication, locked-table ops, history/retention/clustering validator bounds. These
name the table **and** the remedy — the bar the BAD tier should be raised to.

### Harness action
The negative tests already assert a message substring; **extend each to also assert the message is not
a raw stacktrace / `[INTERNAL_ERROR]` / 500** (a readability regression guard), and file every
BAD/MEH message here as a finding with the suggested wording above.
