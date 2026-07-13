# delta-harness — session checkpoint (state, findings, decisions)

Context-retention snapshot. Pairs with `DDL-TEST-PLAN.md` (plan + phase checkoffs), `BUGS.md`
(tagged product bugs), `AUDIT-FINDINGS.md` (missing-guard + message-readability audits),
`VERIFIED-RUN-openhouse.txt` (run record). Branch: `claude/spark-scala-test-env-k7drzg`.

## Where we are
- **DML baseline**: 660 cases, green (typed pipelines × layouts; see `TEST-PLAN.md` phases 1–11).
- **DDL data-plane track: complete & green.** Phases 12–24 executed phase-by-phase, each committed:
  - 12 ADD COLUMN family (comment/position/`int→bigint` widen all supported)
  - 13 schema negatives (DROP COLUMN, narrow, SET NOT NULL — typed exceptions)
  - 14 table properties (user round-trip, reserved-key rejection, `format-version=1→2` forced finding)
  - 15 feature-flag `write.distribution-mode` honored
  - 16 sort order (`WRITE ORDERED BY` → `distribution-mode=range`)
  - 17 rename table (same-db + conflict 409)
  - 18 CTAS / RTAS (incl. RTAS⊕replication rejection — the gap OpenHouse's own tests miss)
  - 19 namespace negatives (CREATE/DROP → typed; misleading "Describing database" message finding)
  - 20 policy DDL (sharing/history/replication round-trips; retention on a time-partitioned table;
    history-bound negatives with GOOD messages)
  - 21 clustering — subsumed by Phase 7 partition transforms (no distinct `CLUSTERED BY` SQL surface)
  - 22 column tags (`SET TAG=(PII)`, reads unaffected) + GRANT/REVOKE ACL (grant-on-unshared typed)
  - 23 replication/table-type contract (SQL-reachable parts; tableType change → reserved rejection)
  - 24 prep multipliers (FULL DML cross): `prep.ordered` 318/318, `prep.evolved` 174/174
  - 24b encryption — tagged SKIP (see findings)
  - 27 maintenance OPERATIONS via Iceberg `CALL` (expire_snapshots / rewrite_data_files /
    remove_orphan_files) — green; jobs merely orchestrate these, so no Jobs/REST needed
- **Last full run**: **1,215 cases, 0 failed, ~14.6 min** (fresh classpath). With encryption re-added
  as SKIP the skip count is ~22 (was 21).

## Tagged bugs (SKIP; `BUGS.md`)
1. `insert.explicitColumns` — partial-column INSERT rejected (`CANNOT_FIND_DATA`); no null-fill.
2. `nested.deleteByNestedField` — DELETE on a nested struct field → optimizer NPE `[INTERNAL_ERROR]`.
3. `ddl.renameColumn` — **RENAME COLUMN is a silent no-op** (neither errors nor renames; client drops
   the change before the server validates). Verified via `REFRESH TABLE` + `DESCRIBE`.
4. `ddl.encryption` — encryption has **no in-repo impl** (KMS plugin private/external; `encryption()`
   hook un-wired → plaintext). Test asserts intended behavior (file ≠ plaintext) and SKIPs until the
   plugin is present.

## Audit findings (`AUDIT-FINDINGS.md`) — product gaps, for follow-up (not harness bugs)
- **Missing guards** (an op breaks the table but isn't blocked; model = RTAS-while-{WAP,replication}):
  - **G2 (concrete bug)**: RTAS/REPLACE on a **locked** table is NOT blocked (replace branches skip
    the `isTableLocked` check). One-line fix. *Only provable live via the REST lock path.*
  - **G4**: `write.wap.enabled`/`replace.enabled` are free-toggle props → disabling WAP strands staged snapshots.
  - **G3/G7**: `skipEligibilityCheck` is an all-or-nothing bypass on the replica commit path.
  - (G1 withdrawn — replication is a snapshot walk, no dangling-ref race.)
- **Error-message readability** (SQL-noob grade; stacktrace = "dumb"):
  - **S1 (systemic)**: the java client wraps a 400 as `"400 , {full body incl. 6000-char stacktrace}"` —
    surfacing only `ErrorResponseBody.message` fixes nearly every 4xx at once.
  - **S2**: catch-all 500 = `exception.toString()` (bare NPE).
  - Worst offenders: nested-DELETE `[INTERNAL_ERROR] NPE`; DROP COLUMN says `Column[X] not found in
    newSchema` (never says "drop"); `SET('policies'='x')` → Gson stacktrace; CREATE/DROP NAMESPACE →
    "Describing database is not supported" (wrong verb); malformed replication interval → uncaught
    `NumberFormatException` 500.
  - GOOD tier (the bar): RTAS-disabled, locked-table, history/retention/clustering validator bounds.

## Recon verdicts (in-code, evidence-backed)
- **Encryption**: absent in OSS (no `EncryptionManager`/`KeyManagementClient`/crypto factory/interface/
  mock); KMS plugin private. `encryption()` un-wired → plaintext.
- **Replication**: data-mover is EXTERNAL (no `OperationTask` registers `REPLICATION`; `JobsScheduler`
  throws "Unsupported job type"). Snapshots copied verbatim, no path rewrite; it's a snapshot walk.
- **Maintenance**: SE/OFD/compaction are Iceberg `CALL` procedures (jobs just orchestrate) → testable directly.

## Harness stack facts (relevant to the REST decision)
- `OpenHouseEnv.start()` boots `com.linkedin.openhouse.tablestest.OpenHouseLocalServer` and exposes it
  over **HTTP** at `http://localhost:${server.getPort}`; the Spark catalog (`OpenHouseCatalog`) talks
  to it via that `uri` + an `auth-token` (`dummy.token`). So the harness ALREADY runs an OpenHouse
  REST server in-process — lock/undrop would be a few `java.net.http` calls to the same server.
- Run tooling: `run-openhouse.sh` caches the resolved classpath in `oh-cp.txt` (reuse unless
  `FORCE_CP=1`) → slice fixed-cost ~40s vs ~120s. Full suite ~0.6s/case + ~120s fixed.
- JDK 17 required (Lombok 1.18.20). System Gradle 8.x (`GRADLE_BIN`); wrapper 7.6.2 blocked by proxy.

## Open decisions (pending user)
1. **Lock + undrop** — server-enforced, in-repo, reachable only via REST. Options under evaluation:
   (a) small `java.net.http` shim against the embedded `OpenHouseLocalServer`, or (b) stand up the
   **full REST service via its Docker setup** and target that. Fidelity vs. resource-cost trade-off —
   see the evaluation in progress. KEY test: is the feature logic real in the embedded server, or
   stubbed? (If the embedded server runs the real Spring controllers/services/repositories, the shim
   is high-fidelity and fine.)
2. **Branch/WAP mega-axis** — subset, deferred; budget discussed later.

## Not-a-test principle (established this session)
Do not write a "test" where the behavior under test has no in-repo implementation to exercise
(encryption). Such cases become tagged-SKIP intended-behavior assertions or documented findings.
