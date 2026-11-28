# OpenHouse capabilities over IRC: work items and what is not verified

Continues `28-capabilities-over-irc-design.md`, which is adopted by D23. Written by a delegated agent from reading
code; nothing in it was run. The rows are copied into `04-work.md`, replacing the former rows C2 and V5.

Bottom line: ten work items, each test first, each leaving a valid state. Items V5-2 and V5-3 and the routes and
names they publish are one-way doors at release (D24); building them inside the forks is a two-way door.

## Work items

| ID | Leaves true | Needs | Proven by | Door |
|---|---|---|---|---|
| V5-0 | Red pins: an IRC commit with `updated.openhouse.policy` stores the stray key; a policy statement on `type=rest` "succeeds"; GRANT and UNLOCK say unsupported | none | An `IrcWriteRoutesTest` case, plus a Spark 3.5 `IcebergRestSparkSqlTest` case (the shared session already loads the extensions, `TestSparkSessionUtil:26`) | two-way |
| V5-gap | The RTAS-versus-replication check and other `validateUpdateTable` checks are pinned on IRC updates, then fixed if missing | none | A red MockMvc test | two-way |
| C2-1 | The `openhouse-catalog-capabilities` module (names, interfaces, `UnlockReason`, `Privileges`, `Principal`). The Java runtime's catalogs implement it through adapters. Old `javaclient.api` types are kept. | none | Unit tests. Spark 3.5 `statementTest` and `catalogTest` unchanged. | two-way (internal) |
| C2-2 | The resolver in the 3.5 extensions replaces `IcebergCatalogMapper` at all 5 call sites. Policy execs check `policies`. `HasIcebergCatalog` is used where present. | C2-1 | Resolver unit tests (legacy, shim and fake-`RESTCatalog` properties). Smoke tests s16, s17 and s27 green on legacy and rest-shim. | two-way |
| V5-1 | Server-side policy patch translation on commit, create and staged completion, with validators | V5-0 | Equivalence test: an IRC patch and a native PUT with client-merged policies give a byte-equal `policies` property. Every rule in the design's server changes is refused with the native status and message. Audit event. | wire: one-way at release |
| V5-2 | Config overrides `openhouse.capabilities=policies`. The adapter's `CONFIG` says the same. | V5-1 | Config test. V5-0's policy pins flip to green. | one-way at release |
| V5-3 | ACL and lock routes under `/iceberg`, advertising `grants,unlock` | V5-2 | MockMvc equivalence with the native routes: status, message, the authorization handler's calls, audit, and lock state after | one-way at release |
| C2-3 | `OpenHouseRestCapabilityClient` (`HTTPClient`, `AuthManager`, `ResourcePaths`), used by the resolver | C2-2, V5-3 | Spark 3.5 itest: GRANT, REVOKE, SHOW GRANTS and UNLOCK on `type=rest` | two-way |
| N1-c | `SparkSqlSmokeTest` gains a third path, `rest-http` (`SparkCatalog type=rest`). Policy, grant and unlock cases pass. Differences are listed. | C2-3 | s16, s17 and s27 plus a test per SET POLICY form, on all three paths | two-way |
| N3-x1 | A Spark 4.2 and Scala 2.13 extensions module: ANTLR 4.13.1, the new parser methods, the resolver, `SQLConf.get`. It depends only on C2-1. | C2-2 | Ported parser and statement unit tests | two-way |
| N3-x2 | A Spark 4.2 itest: every OpenHouse statement over IRC, with no OpenHouse catalog class on the classpath | N3-x1, V5-3 | A 4.2 itest beside the read itest, asserting stored state through the OpenHouse Java client | two-way |

## Not verified

- Nothing in the design was run. The stray-property outcome on today's IRC path is inferred from `CandidateViews:57`
  and `BasePreservedKeyChecker:22`. V5-0 pins it.
- Not checked:
  - whether `HasIcebergCatalog` exists on LinkedIn's Iceberg 1.5.2 Spark 3.5 `SparkCatalog` (the current
    reflection works there, so it is not needed);
  - whether `parsePlanWithParameters` is abstract in Spark 4.2;
  - whether `AnalysisException(..., cause = ...)` compiles on Spark 4.2, which `UnlockAnalysisException` uses;
  - whether `sessionState` is reachable from `org.apache.spark.sql.SparkSession` in 4.2;
  - whether unannotated request and response classes round-trip through Iceberg's relocated Jackson mapper;
  - the possible gap on `validateUpdateTable` for IRC updates (V5-gap);
  - whether `RESTCatalog` sends `session-id` from `app-id` as the OpenHouse client does.
- Assumed: the client's Gson parse of the policy patch and the server's `Policies` mapper accept the same JSON.
  V5-1's equivalence test must cover every exec's literal output (`Set*PolicyExec`).
