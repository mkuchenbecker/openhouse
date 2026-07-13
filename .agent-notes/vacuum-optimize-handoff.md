# VACUUM / OPTIMIZE — Agent Handoff Notes

Running context for anyone (human or agent) picking up the Spark SQL Iceberg-maintenance
"sugar commands" work. Captures decisions, rationale, found issues, and current state so the
thread survives container reclaim and context compaction.

_Last updated: 2026-07-13. Session: iceberg-vacuum-semantics-43tl45._

---

## 1. Goal

Add Databricks-style SQL sugar commands that run Iceberg table maintenance by delegating to
Iceberg's existing stored procedures via `CALL`, rather than reimplementing anything.

- **VACUUM** — snapshot expiration (+ optional orphan-file deletion). **Implemented, CI green.**
- **OPTIMIZE** — bin-pack compaction (+ optional manifest rewrite). **Planned, not yet built.**
  See `.agent-notes/optimize-plan.md`.

The user's framing throughout: _"I just want sugar, we have the system call already. The
boilerplate is the hard part."_ These commands are thin `LeafRunnableCommand`s that resolve the
target catalog and emit `CALL <cat>.system.<proc>(...)` through `sparkSession.sql(...)`, so
procedure resolution, argument binding, and catalog-side **auth** all happen on the existing
`CALL` path.

## 2. Where the code lives

| Item | Repo / branch | State |
|---|---|---|
| VACUUM on Spark master (5.0) | `mkuchenbecker/spark` @ `claude/iceberg-vacuum-semantics-43tl45` | PR #1, CI fully green, draft |
| VACUUM on Spark 3.5 (primary target) | `mkuchenbecker/spark` @ `claude/iceberg-vacuum-semantics-43tl45-spark3.5` | PR #2, base `branch-3.5`, green except one known flake, draft |
| OpenHouse integration test | `mkuchenbecker/openhouse` @ `claude/iceberg-vacuum-semantics-43tl45` | this branch |

The user's real deployment target is **Spark 3.5** (LinkedIn OpenHouse catalog uses a custom
`com.linkedin.iceberg` 1.5.2.x on Spark 3.5). Master/5.0 is carried along but 3.5 is what matters.
Backport order the user stated: 3.5 first; 4.0/4.1 later ("3.5 is the only one i care about for now").

## 3. VACUUM — final design (implemented)

Surface: `VACUUM <table> [REMOVE ORPHAN FILES] [RETAIN n HOURS]`

- Snapshot expiration (`system.expire_snapshots`) **always** runs.
- `REMOVE ORPHAN FILES` opts into `system.remove_orphan_files`, run **after** expiration (so OFD
  works against the settled live-file set).
- `RETAIN n HOURS` bounds both via the procedures' `older_than` arg. Procedure args must be
  foldable, so the window is resolved at execution time to a **literal millisecond-precision
  timestamp** (`now - n hours`, session TZ). When omitted, each procedure uses its own default.

Keyword evolution: `OFD` → `REMOVE ORPHANED FILES` → **`REMOVE ORPHAN FILES`** (matches
`remove_orphan_files`). All five words — `VACUUM REMOVE ORPHAN FILES RETAIN` — are **non-reserved**.

Key files (paths on the Spark branches):
- Grammar: `sql/api/src/main/antlr4/org/apache/spark/sql/catalyst/parser/SqlBaseLexer.g4`,
  `SqlBaseParser.g4` (`#vacuumTable` rule; keywords in BOTH non-reserved blocks).
- AST: `sql/core/.../execution/SparkSqlParser.scala` → `visitVacuumTable`.
- Command: `sql/core/.../execution/command/VacuumTableCommand.scala` (`callStatements` helper is
  the unit-testable core).
- Tests: `VacuumTableSuite` (CALL-string unit), profile-gated `VacuumIcebergSuite` (real Iceberg).

## 4. Decisions & rationale (chronological, deduped)

- **Delegate to `CALL`, don't reimplement.** Minimal boilerplate; inherits procedure binding + auth.
- **Expiration is the default; OFD is opt-in.** OFD is a full-filesystem scan (big blast radius),
  so it must be explicit. Mirrors Dremio's `VACUUM TABLE ... EXPIRE SNAPSHOTS | REMOVE ORPHAN FILES`.
- **Non-reserved keywords.** So existing identifiers named `vacuum`/`remove`/`orphan`/`files`/
  `retain` still parse. Costs keyword-golden churn (see Found Issues).
- **`RETAIN` → literal timestamp, not `current_timestamp()`.** The CALL argument binding rejects
  non-foldable expressions. This surfaced a real bug caught by the exec test.
- **Millisecond precision on `older_than`.** Second-granularity made `RETAIN 0 HOURS` flaky — the
  cutoff could land between just-created snapshots' commit timestamps, expiring only some. Fixed.
- **OPTIMIZE does NOT expire snapshots** (user decision). VACUUM owns expiration; keeping them
  disjoint avoids double-expiry confusion when both are run.
- **OPTIMIZE manifest-rewrite is keyword-gated, not default** (user decision). Rationale: running
  both `rewrite_data_files` and `rewrite_manifests` = **two separate commits**; the user's rule was
  "both by default ... unless that would be two commits, at which point we should have a keyword."
  It is two commits, so manifests goes behind `REWRITE MANIFESTS`.

## 5. Found issues / gotchas (the expensive lessons)

1. **Keyword goldens are plural and easy to miss.** Adding non-reserved keywords breaks, in
   addition to `SQLKeywordSuite`:
   - `docs/sql-ref-ansi-compliance.md` keyword table.
   - `SQL_KEYWORDS()` golden: `sql/core/src/test/resources/sql-tests/results/keywords.sql.out` and
     `ansi/keywords.sql.out` (3.5) — on master it's `nonansi/keywords.sql.out` **plus**
     `keywords-enforced.sql.out`. **This bit us: CI failed on `ThriftServerQueryTestSuite` /
     `SQLQueryTestSuite` because these were missed initially.**
   - Thrift `getSQLKeywords` golden: `ThriftServerWithSparkContextSuite.scala` (CLI_ODBC_KEYWORDS).
   - master only: connect JDBC `getSQLKeywords` in `SparkConnectDatabaseMetaDataSuite.scala`.
   - Goldens are **alphabetically sorted**; insert at the sorted position. The lexer keyword list is
     loosely ordered; grammar non-reserved blocks need the keyword in BOTH.
2. **The Iceberg runtime jar is banned by the Java-8 bytecode enforcer.** Adding
   `org.apache.iceberg:iceberg-spark-runtime-3.5` (even test-scoped) to `sql/core/pom.xml` fails
   `dev/test-dependencies.sh` → `enforceBytecodeVersion` ("Found Banned Dependency") on branch-3.5,
   because the shaded fat jar has >Java-8 bytecode. **Fix (user-approved): gate it behind an opt-in
   Maven profile** `iceberg-integration-tests` in `sql/core/pom.xml` that declares the dep and adds
   `src/test/iceberg` as a test-source root; the real-Iceberg suite lives there. Default CI (Maven
   dep-check + SBT) never pulls the jar. **OPTIMIZE's e2e must reuse this same profile/dir — do not
   add a second dependency or profile.**
3. **`StatisticsSuite` flake** on PR #2's `hive - other tests` (1/2908, hive module untouched by
   this change). Not real. Could not API-rerun (403, no Actions-write on the integration token);
   rerun the single job from the GitHub Actions UI.
4. **Toolchain limits in the sandbox:** only JDK 21 is available; Spark 3.5 needs JDK 8/11/17, so
   the patched Spark can't be built here, and the custom LinkedIn iceberg couldn't be built (gradle
   wrapper download 403). OpenHouse's own Gradle 7.6.2 wrapper is likewise **403-blocked**. So the
   OpenHouse integration test (below) is written but **not executed** in-sandbox.

## 6. OpenHouse integration — verdict (the "does this work with the real catalog + auth" question)

**Yes, by construction, and evidenced by OpenHouse's own passing tests.**
- OpenHouse configures `spark.sql.catalog.openhouse = org.apache.iceberg.spark.SparkCatalog`
  (`catalog-impl = com.linkedin.openhouse...OpenHouseCatalog`) + `IcebergSparkSessionExtensions`.
  Stock `SparkCatalog` + extensions ⇒ the `system.*` procedures are registered and reachable via
  `CALL`. Confirmed in `SparkTestBase` / `OpenHouseSparkITest`.
- OpenHouse's own itests already run `CALL openhouse.system.expire_snapshots(...)`
  (`BranchTestSpark3_5`) against a real embedded server (`OpenHouseLocalServer`); the jobs app runs
  `call openhouse.system.rewrite_data_files(...)` (`apps/spark/.../Operations.java`). VACUUM is a
  strictly thinner client of that identical path.
- **Auth is catalog-level and owned by OpenHouse:** `spark.sql.catalog.openhouse.auth-token` (set
  from a token path by `services/jobs/.../JobsRegistry.java`). VACUUM emits `CALL` and never touches
  auth, so it inherits OpenHouse's auth. This directly answers the user's "if openhouse does the
  auth this is needed" — it does; the sugar is the right shape.

**Test written (this branch):**
`integrations/spark/spark-3.5/openhouse-spark-itest/src/test/java/com/linkedin/openhouse/spark/catalogtest/VacuumTestSpark3_5.java`
— extends `OpenHouseSparkITest` (real local server + auth), mirrors `BranchTestSpark3_5`. Compiles
against stock Spark (VACUUM is a SQL string); needs a **VACUUM-carrying Spark 3.5 at runtime**.
It is annotated **`@Disabled`** so OpenHouse CI (which builds stock spark-sql 3.5.2) stays green
instead of failing on the `VACUUM` `ParseException`. To run: build the 3.5 VACUUM branch →
`publishToMavenLocal` → point the itest module's `sparkVersion` at that build → **remove the
`@Disabled`** → `./gradlew :integrations:spark:spark-3.5:openhouse-spark-3.5-itest:test --tests '*VacuumTestSpark3_5'`.

## 7. Current state / next steps

- [x] VACUUM implemented on master (PR #1) and 3.5 (PR #2); both draft, CI green (PR #2 minus the
      known StatisticsSuite flake).
- [x] Keyword goldens fixed on both branches (all variants).
- [x] Iceberg dep + real e2e gated behind `iceberg-integration-tests` profile (3.5).
- [x] OpenHouse integration question answered; `VacuumTestSpark3_5` written & pushed (this branch).
- [ ] Run `VacuumTestSpark3_5` in an env with a VACUUM-carrying Spark 3.5 (OpenHouse CI / real infra).
- [ ] Implement OPTIMIZE per `.agent-notes/optimize-plan.md` (3.5 first).
- [ ] (Deferred) Optimize keyword spelling default = `REWRITE MANIFESTS`; output = silent like VACUUM.
- [ ] (Deferred by user) 4.0 / 4.1 backports of VACUUM.
