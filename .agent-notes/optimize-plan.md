# OPTIMIZE command — implementation plan, checklist & verification

Sibling of the shipped VACUUM command. Mirrors VACUUM's structure exactly (grammar → AST →
`LeafRunnableCommand` → `CALL` sugar → keyword goldens → profile-gated real-Iceberg e2e).
Read `.agent-notes/vacuum-optimize-handoff.md` first for shared context and the "found issues".

**Primary target: Spark 3.5** branch `claude/iceberg-vacuum-semantics-43tl45-spark3.5` (stack
OPTIMIZE on top of the VACUUM commits so goldens already contain VACUUM's keywords). A master/5.0
variant follows with the extra goldens noted in step 5.

---

## Finalized design (user-decided)

Surface: **`OPTIMIZE <table> [REWRITE MANIFESTS]`**

- `OPTIMIZE <table>` → `system.rewrite_data_files` only (bin-pack compaction, **one commit**).
- `OPTIMIZE <table> REWRITE MANIFESTS` → additionally `system.rewrite_manifests` (**second commit**),
  run after data-file rewrite so manifests reflect the compacted layout.
- **No snapshot expiration** — that stays VACUUM's job (user decision "1 - no").
- Manifest rewrite is **keyword-gated, not default** — because running both procedures is two
  separate commits (user rule: "both by default ... unless that would be two commits, at which
  point we should have a keyword").

Defaults for the two points the user left open (proceed unless overridden):
- Keyword spelling: **`REWRITE MANIFESTS`** (matches the `rewrite_manifests` procedure; alternative
  `COMPACT MANIFESTS` would reuse the existing `COMPACT` token and save one keyword-golden edit).
- Command output: **silent** (`Seq.empty[Row]`), like VACUUM (could surface rewrite file-counts later).

New keywords introduced: **`OPTIMIZE`, `REWRITE`, `MANIFESTS`** (all non-reserved).

---

## Implementation checklist

### 1. Grammar — `sql/api/src/main/antlr4/org/apache/spark/sql/catalyst/parser/`
- [ ] `SqlBaseLexer.g4`: add tokens `OPTIMIZE`, `REWRITE`, `MANIFESTS` inside the
      `//--SPARK-KEYWORD-LIST-START/END` region (near their alpha neighbours; lexer order is loose).
- [ ] `SqlBaseParser.g4`: add the statement alternative next to `#vacuumTable`:
      `| OPTIMIZE identifierReference (rewriteManifests=REWRITE MANIFESTS)?   #optimizeTable`
- [ ] `SqlBaseParser.g4`: add `OPTIMIZE`, `REWRITE`, `MANIFESTS` to **BOTH** the
      `//--ANSI-NON-RESERVED` block and the `//--DEFAULT-NON-RESERVED` `nonReserved` block.

### 2. AST builder — `sql/core/.../execution/SparkSqlParser.scala`
- [ ] Add `visitOptimizeTable(ctx: OptimizeTableContext)` mirroring `visitVacuumTable`:
      `val rewriteManifests = ctx.rewriteManifests != null`;
      `withIdentClause(ctx.identifierReference(), nameParts => OptimizeTableCommand(nameParts, rewriteManifests))`.

### 3. Command — `sql/core/.../execution/command/OptimizeTableCommand.scala` (new)
- [ ] `case class OptimizeTableCommand(nameParts: Seq[String], rewriteManifests: Boolean) extends LeafRunnableCommand`.
- [ ] `run`: same catalog resolution as VACUUM (`isCatalogRegistered` head-part split, else current).
- [ ] `object OptimizeTableCommand.callStatements(catalog, table, rewriteManifests): Seq[String]`
      (exposed for unit test):
      - always `CALL <cat>.system.rewrite_data_files(table => '<db.t>')`
      - if `rewriteManifests`, append `CALL <cat>.system.rewrite_manifests(table => '<db.t>')`
      - use `quoteIfNeeded` for catalog + each name part, as VACUUM does.
- [ ] No `older_than`/timestamp machinery (v1 has no time argument).

### 4. Keyword goldens (EXHAUSTIVE — this is where VACUUM's CI failed; insert at SORTED position)
branch-3.5:
- [ ] `docs/sql-ref-ansi-compliance.md` — rows for `MANIFESTS`, `OPTIMIZE`, `REWRITE`.
- [ ] `sql/core/src/test/resources/sql-tests/results/keywords.sql.out` — 3 lines, `<KW>\tfalse`.
- [ ] `sql/core/src/test/resources/sql-tests/results/ansi/keywords.sql.out` — same 3 lines.
- [ ] `sql/hive-thriftserver/.../ThriftServerWithSparkContextSuite.scala` — CLI_ODBC_KEYWORDS string.
- [ ] (no edit) `SQLKeywordSuite` is the cross-check that fails if any of the above is inconsistent.

master/5.0 additionally:
- [ ] `sql/core/src/test/resources/sql-tests/results/nonansi/keywords.sql.out`
- [ ] `sql/core/src/test/resources/sql-tests/results/keywords-enforced.sql.out`
- [ ] `.../spark/sql/connect/client/jdbc/SparkConnectDatabaseMetaDataSuite.scala` (getSQLKeywords)

Sorted-insertion neighbours: `MANIFESTS` after `MACRO`/before `MAP`; `OPTIMIZE` before `OPTION`;
`REWRITE` after `REVOKE`/before `RIGHT`. (Verify against the actual file — the neighbour set shifts
once VACUUM's keywords are present.)

### 5. Tests
- [ ] `SparkSqlParserSuite`: parse-to-plan for `OPTIMIZE a.b.c` and `OPTIMIZE tbl REWRITE MANIFESTS`.
- [ ] `OptimizeTableSuite` (new, extends `SparkFunSuite`): assert `callStatements` output for
      compaction-only, with-manifests (order: data then manifests), and catalog-quoting.
- [ ] `OptimizeIcebergSuite` (new, in `sql/core/src/test/iceberg/...`): **reuse the existing
      `iceberg-integration-tests` profile + `src/test/iceberg` source root** — do NOT add a new dep
      or profile. Same Hadoop-catalog setup as `VacuumIcebergSuite`, `enableAutoThreadAudit = false`.
- [ ] (optional) OpenHouse itest `OptimizeTestSpark3_5.java` mirroring `VacuumTestSpark3_5.java`.

---

## Explicit verification steps

Run from a Spark checkout with JDK 8/11/17 (branch-3.5 will NOT build on JDK 21).

1. **Keyword consistency** — proves lexer + both grammar blocks + doc table agree:
   `build/sbt "catalyst/testOnly *SQLKeywordSuite"` → PASS.
2. **Parse → plan**:
   `build/sbt "sql/testOnly *SparkSqlParserSuite -- -z OPTIMIZE"` → PASS (both forms).
3. **CALL-string unit**:
   `build/sbt "sql/testOnly *OptimizeTableSuite"` → PASS (asserts exact CALL strings incl. order).
4. **Golden query suites** (the ones VACUUM's miss broke) — must stay green with the new keywords:
   `build/sbt "sql/testOnly *SQLQueryTestSuite -- -z keywords"` and
   `build/sbt "hive-thriftserver/testOnly *ThriftServerQueryTestSuite -- -z keywords"` → PASS.
   Also `hive-thriftserver/testOnly *ThriftServerWithSparkContextSuite -- -z "Get SQL Keywords"`.
5. **Real-Iceberg e2e** (opt-in profile; confirms actual compaction effects):
   `build/mvn -Piceberg-integration-tests -pl sql/core test -DwildcardSuites=org.apache.spark.sql.execution.command.OptimizeIcebergSuite` → PASS.
   Assertions: after N small single-row inserts, `OPTIMIZE ice.db.t` reduces the referenced
   data-file count (`SELECT count(*) FROM ice.db.t.files`) while `SELECT *` is unchanged; and
   `OPTIMIZE ice.db.t REWRITE MANIFESTS` reduces (or ≤) the `.manifests` count with data intact.
6. **Full targeted regression before pushing**:
   `build/sbt "sql/testOnly *VacuumTableSuite *OptimizeTableSuite *SparkSqlParserSuite"` → PASS
   (ensures OPTIMIZE didn't disturb VACUUM).
7. **CI gate**: on push, confirm `Build modules: api, catalyst, hive-thriftserver`,
   `sql - other tests`, and `Linters, licenses, dependencies...` are green (the three jobs the
   keyword-golden + iceberg-dep issues previously failed). Treat a lone `StatisticsSuite` red as the
   known hive flake, not OPTIMIZE.

## Definition of done
- All 7 verification steps pass locally on a JDK-8/11/17 Spark 3.5 checkout.
- PR opened (draft) on 3.5 with green CI (modulo the known flake).
- `.agent-notes/vacuum-optimize-handoff.md` updated: OPTIMIZE checkbox ticked, any new found-issues
  appended.
- master/5.0 variant tracked as a follow-up (extra goldens in step 5).
