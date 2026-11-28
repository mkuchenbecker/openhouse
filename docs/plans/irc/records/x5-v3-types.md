# X5: the column types of format v3, supported or refused type by type

Bottom line: variant is off by default behind cluster.iceberg.format-v3.variant.enabled; final head fa9dbf4b2251713f88ffb59ae180dc5201948cc6, CI green. Earlier text below describes the property-on behaviour.
Parquet/Avro for Spark 4.2 over IRC (Spark 3.5 cannot read it; refused on ORC, over ORC data files and with a retention
policy); `timestamp_ns`, `timestamptz_ns`, `geometry`, `geography` are refused with a reason. v2 and switch-off
answers are unchanged. Draft pull request 129, head 430bf023fdda2d04811b4fa5e88efcba018e29aa. CI green on that head (both jobs).

## Decisions and reasons
- unknown SUPPORT: Spark 3.5 and 4.2 spell `void` (CTAS of NULL makes one), read nulls; no format stores it; jobs (stats,
  compaction, retention) ran on such a table.
- variant SUPPORT, Parquet/Avro: Spark 4.2 writes/reads it; no Spark ORC writer (ORC = cluster default) -> refused on ORC;
  Spark ORC reader NPEs on old ORC files once a variant column exists -> adding one is refused while a branch/tag head has
  ORC data files (manifest scan; PendingCommit carries FileIO); retention job (Spark 3.5) cannot load the table -> retention
  policy refused with a variant column.
- timestamp_ns/tz_ns REFUSE: no Spark integration has the type.
- geometry/geography REFUSE: Spark 3.5 cannot read; Spark 4 only behind spark.sql.geospatial.enabled and Parquet only;
  bounds break Spark 3.5 metadata tables.
- v2 kept exactly: native variant still 400 "schema : Unsupported type: variant" (switch off: from the walk; switch on:
  emulated in the repository before anything else); other types Iceberg's 500.

## Not changed, brought to the orchestrator
- v2 answers of 500 "Invalid schema for v2" for v3 types (a 400 would be a v2 change).
- 500 "Type: X is not supported" for a default on a variant/unknown column, any version (validateSortOrder's second parse).

## Log
- 2026-11-24: worktree created; inventory under way.

## Inventory notes [read]
- Iceberg 1.12 v3-only type ids (Schema.MIN_FORMAT_VERSIONS): TIMESTAMP_NANO (timestamp_ns, timestamptz_ns), VARIANT, UNKNOWN, GEOMETRY, GEOGRAPHY.
- Spark 3.5 TypeToSparkType: UNKNOWN -> NullType; no case for variant (visitor default throws "Unsupported type: variant"), geometry, geography, timestamp_ns ("Cannot convert unsupported type to Spark").
- Spark 4.2 TypeToSparkType: variant -> VariantType, geometry/geography (fork, behind spark.sql.geospatial.enabled), unknown -> NullType; no timestamp_ns.
- OpenHouse cluster default write format is ORC (cluster.iceberg.write.format.default:orc). Spark 4.2 SparkOrcWriter has no variant writer.
- OpenHouse visitors that throw on variant: OpenHouseTablesApiValidator.validateNoDuplicateColumns, BaseIcebergSchemaValidator.

## Commits and runs [ran]
- 54921ec6 pins (green on base code); f2fbae3e service decisions; 339ebecf ORC-files check + Spark 4.2 tests; 70e89256 Spark 3.5
  tests; 0ae653d1 stats unit test; 82a8cdcc merge #125; b66e7794 retention refusal + jobs test; 430bf023 merge #128.
- b66e7794, 1.12: services 1706/0f/1s, internalcatalog 327/0/0, Spark 3.5 catalogTest 298/0/1, Spark 4.2 test 73, ext 24,
  smoke 66, apps 347/0/5, kit 41/96/23 = expected, spotless pass. Reference: services 1690/0/48, internalcatalog 327/0/0,
  Spark 3.5 catalogTest 298/0/24, apps 347/0/28, spotless pass.
- 430bf023: Spark 3.5 catalogTest 333/0/1 (1.12) and 333/0/56 (ref); Spark 4.2 test 73, ext 24, smoke 65; kit 41/96/23;
  service FormatV3* classes 43/0/1; spotless pass.
- Not verified: jobs compaction of a variant table with data; Spark 4.2 extension statements on these types; Avro on Spark 3.5
  (test JVM cannot write Avro at all).
- CI on 430bf023 [ran]: Build and Run Tests success; Reference build on LinkedIn's Iceberg 1.5.2 success.

## Round 2 (jobs on a variant table with data)
- 17689d24 [ran 1.12]: FormatV3VariantJobsTest 8/0/0. Compaction (table-only, strict) commits nothing; expiry, orphans (single, batched), staged do their work correctly; stats collected; layout and sort stats fail in their work; every post-job validation fails "Unsupported type: variant". No data changed or lost. Retention refused both directions (FormatV3TypesTest). Next: code answers, full suites, merge, CI.
- 7e37f76b Spark 3.5 exact error + SHOW TABLES pinned. 71aec37f merge of irc-integration adc28470 (#130). 3b0d54f2 job tests reuse FormatV3Tables.
- 3b0d54f2, 1.12 [ran]: services 1706/0/1, internalcatalog 327/0/0, apps 389/0/5, Spark 3.5 catalogTest 333/0/1, Spark 4.2 test 85, ext 24, smoke 65 /0/0, kit 41/96/23 = expected, spotless pass. Reference line: first attempt killed (daemon OOM, shared memory); rerunning task by task.
- Reference line on 3b0d54f2 [ran]: internalcatalog 327/0/0; apps 389/0/53 (X5 job classes compile with the stub and skip). services:tables:test killed twice by OOM (shared memory), not completed; Spark 3.5 catalogTest still running at report time. CI on 3b0d54f2 green, both jobs (incl. the reference build).
- Reference line on 3b0d54f2, completed after the report [ran]: Spark 3.5 catalogTest 333/0/56, spotlessCheck pass. services:tables:test still not completed locally (OOM kill); the 1706/0/1 in the count is the stale 1.12 result.

## Round 3 (variant behind its own property)
- 929089c4 tests (FormatV3VariantOffTest; variant-taking tests run with cluster.iceberg.format-v3.variant.enabled=true: FormatV3TypesTest context, Spark 4.2 third service process, Spark 3.5 FormatV3Server, apps FormatV3TablesServer). e6fc2fd8 property (default false) + refusal of new variant columns when off + docs (docs/development/iceberg-fork.md). Targeted runs green [ran]: service FormatV3* 54/0/1, Spark 4.2 FormatV3 7+6, Spark 3.5 FormatV3 7+4. Final full runs in progress (r5.log).
- On e6fc2fd8 [ran]: services 1708/0/1 (1.12), 1692/0/50 (ref, completed); Spark 4.2 test 85/0/0 (second attempt; first killed); apps X5 classes 12/0/0; kit 41/96/23 = expected; spotless pass. CI on e6fc2fd8 green both jobs.
- fa9dbf4b merge of irc-integration 1dd485d3 (#131); conflict in IrcReadTestServiceProcess resolved by keeping both added methods; hook dropped merge state, finished with write-tree/commit-tree. Rerunning all on fa9dbf4b (r6.log).
- fa9dbf4b [ran]: services 1714/0/1 (1.12), 1692/0/50 (ref, completed); Spark 4.2 test 115/0/0 (2nd attempt after daemon kill + 10 min wait); apps X5 classes 12/0/0; kit 41/96/23 = expected; spotless pass. CI on fa9dbf4b green both jobs. Base unchanged since. Final.
