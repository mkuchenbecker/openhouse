# Capabilities module and resolver (C2-1, C2-2): result

Bottom line: OpenHouse features are now capabilities found by one resolver in the Spark 3.5 extensions, instead of
by a lookup that needs an OpenHouse catalog class. On the legacy and in-process paths nothing changed, and the counts
below show it. On a plain `RESTCatalog` the policy statements need the server to advertise `policies`, and grants and
unlock still say "does not support", because no client for those routes exists yet (C2-3). Written by a delegated
agent from branch `irc/c2-capabilities-resolver` (pull request 100, merged as 19605614). "Ran" means run. "Read"
means taken from code only.

## What was built

- Module `integrations/java/openhouse-catalog-capabilities` (project `:integrations:java:openhouse-catalog-capabilities`).
  It is plain Java with no Spring, generated client, Spark or Scala dependency. Iceberg's API is compileOnly. It is
  not published (D24).
  - `CapabilityNames`: `policies`, `grants`, `unlock`, `column-defaults`, `openhouse.capabilities`, the header
    `X-OpenHouse-Client-Capabilities`, the server and client sets, and `parse`.
  - `OpenHouseTableProperties`: `openhouse.tableId` and `updated.openhouse.policy`.
  - `GrantsCapability`, `UnlockCapability`, and the value types `UnlockReason`, `UnlockTableTarget` (an interface
    with a validating `of`), `UnlockValidationException`, `AclPolicy`, `Principal` and `Privileges`.
- In the Spark 3.5 runtime: `JavaClientCapabilities` adapts the Java client's interfaces. `OpenHouseCapabilities`
  and `CatalogCapabilities` are the resolver, written in Java so Spark 4.2 can reuse them. The four production call
  sites of `IcebergCatalogMapper` use the resolver. The six policy execs call `requirePolicies` before they commit.

## What the resolver answers, by path (ran: `OpenHouseCapabilitiesTest`, 24 runtime tests)

| Path, and how it is recognized | policies | grants | unlock |
| --- | --- | --- | --- |
| Legacy `OpenHouseCatalog` (implements the Java client's `SupportsGrantRevoke` and `SupportsUnlock`) | offered | adapter | adapter |
| In-process `OpenHouseRestCatalog` (same interfaces, checked before any property although it is a `RESTCatalog`) | offered | adapter | adapter |
| A catalog implementing `GrantsCapability` or `UnlockCapability` itself | offered | itself | itself |
| Iceberg `RESTCatalog`, by `properties().get("openhouse.capabilities")` (merged; the server override wins) | only when advertised, else `UnsupportedOperationException` | never, even if advertised (C2-3) | never |
| Another Iceberg catalog, such as Hadoop in the statement tests | offered (the exec's own `openhouse.tableId` check decides) | not offered | not offered |
| Not Iceberg | not offered | not offered | not offered |

The policy refusal text is new: "Catalog 'x' does not support OpenHouse policies: its server does not advertise
'policies' in openhouse.capabilities (advertised: [...])". Grants and unlock keep today's texts. Each case runs with the cache enabled and disabled, and with the
`SparkSessionCatalog` wrapping.

## Every statement on a plain `RESTCatalog`, before and after (ran: `IcebergRestOpenHouseStatementsTest`)

| Statement | Base cc76d485 (nothing advertised; D25 refusal) | This change on that server | After pull request 99 (server advertises `policies`) |
| --- | --- | --- | --- |
| SET and UNSET POLICY, MODIFY COLUMN SET TAG | Reached the server, `BadRequestException` naming the key, nothing stored | Fails in the client with `UnsupportedOperationException`, nothing sent | Applied as a policy change, no stray key |
| Same, with client-side `openhouse.capabilities=policies,...` | Server 400 | Reaches the server, 400 (D25) | Applied (the server override wins) |
| GRANT, REVOKE, SHOW GRANTS on a table or database | "does not support Grant Revoke Statements" | Same | Same |
| ALTER TABLE ... UNLOCK | Disabled by default; when enabled "does not support UNLOCK" | Same | Same |

## Legacy and in-process paths unchanged (ran)

| Suite | Before (cc76d485) | After (head) |
| --- | --- | --- |
| Spark 3.5 itest `catalogTest` | 247 tests, 1 skipped, 0 failed | 255, 1 skipped, 0 failed |
| Spark 3.5 itest `statementTest` | 137, 0 failed | 137, 0 failed, identical per case |
| Spark 3.1 itest `catalogTest` | 87, 1 skipped | 87, 1 skipped, identical per case |
| Spark 3.1 itest `statementTest` | 126 | 126, identical per case |
| Smoke s16, s17, s27 on legacy and rest-shim | pass | pass, default and `-PicebergFork` |
| New module tests | none | 25, 0 failed |
| New Spark 3.5 runtime unit tests | none | 24, 0 failed |

The 247 base cases match by name and status. The 8 added cases are the new pin (4) and two classes from pull request
99 (3 and 1). The shared statement tests ran unchanged on both lines. The final `catalogTest` came from the cache,
because its key matched the executed run before the last two commits.

## Opt-in Iceberg 1.12 build (ran)

- With `-PicebergFork` the module and runtime tests pass. The fake REST client needed `withAuthSession` on 1.12.
- The Spark 3.5 `catalogTest` gave 255 tests and 8 failures, all view cases ("Server does not support endpoint GET
  .../views/..."). The same 8 fail on cc76d485 and on 1fa8cb34 without this change, so this work did not cause them.
  Pull request 102 fixed them (`36-v5-3-grants-unlock-endpoints-result.md`).

## Default build classpaths (ran)

Every resolvable configuration of every project was dumped before (59 projects) and after (60). The diff is the new
project's own file plus 5 lines, all naming the new module, in the Spark 3.5 runtime and the Java 1.5 runtime.

## What the Spark 4.2 extensions need from here

- The resolver uses the public `HasIcebergCatalog.icebergCatalog()`, present on 1.5.2 (read via `javap`) and on
  1.12 (read). The only reflection left is `CachingCatalog.catalog`.
- On 3.5 the plans and parser keep the Java client's `ReasonEnum` and `UnlockTableTarget`, because shared tests pin
  them. The 4.2 port uses the module's types.
- Still open from the design: ANTLR 4.13.1, the new parser methods, `SQLConf.get` (done by N3-x1, in
  `35-n3x1-spark42-extensions-result.md`) and the REST capability client (C2-3).

## Choices where the brief left room

1. The module sits beside the `iceberg-1.x` directories with no line or Scala suffix, uses its own `java-library`
   plus checkstyle, and does not use `openhouse.java-minimal-conventions` (it adds mapstruct and servlet-api).
2. Today's catalogs meet the interfaces through adapters in the Spark 3.5 runtime, because the Java runtime's
   sources are shared with the 1.2 and Spark 3.1 line.
3. `UnlockTableTarget` is an interface, so the adapter can unwrap the very object a shared test asserts.
4. Privilege stays a `String` in `GrantsCapability`, because converting earlier would move a
   `NoSuchElementException`. `Privileges` is in the module for C2-3.
5. Policies on other Iceberg catalogs are offered, which keeps the Hadoop-catalog statement tests working.
6. The policy check sits after the exec's own table check, so a non-OpenHouse table keeps its message.
7. `offers(x)` means usable. A name advertised but with no client yet is in `advertised()` but not in `offered()`.
8. Spark 3.1 is left alone: it has its own extensions copy and still uses `IcebergCatalogMapper`. In 3.5 the mapper
   and the Scala `Principal` stay and delegate, because the shared 3.1 itest compiles against them.
9. `OpenHouseRestAdapter` uses `CapabilityNames`. The module is compileOnly in the Java runtime because the
   constants are inlined (`javap` shows no reference). Pull request 102 did the same for the service.

## Known gaps

- A client-side `openhouse.capabilities` counts when the server sends no override, because `properties()` is merged.
- The "fail loudly" case is covered only by unit tests, because the real server now advertises `policies`.

## Not run

The whole build, the services, the Java 1.2 and 1.5 itests in full (only `OpenHouseRestAdapter*` ran), the
delta-harness, the fork build beyond the listed tasks, and any Spark 4.2 build.
