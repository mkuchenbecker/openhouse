# V5-1, V5-2: policy changes through the IRC routes

- **Branch:** `irc/v5-policies-over-irc`
- **Pull request:** draft https://github.com/mkuchenbecker/openhouse/pull/99, into `irc-integration`
- **Head:** `92a3f57a8004c02c76d5d3be16a97d8940eb0bc4`
- **Base:** `origin/irc-integration` at e353a36d (#97). cc76d485 (#98, the fix for the Iceberg 1.2 fixture) was merged in at f7ff1005.

## Bottom line (ran)

Every OpenHouse policy statement now takes effect over IRC, with no OpenHouse catalog class on the engine. Proof below.

**The statements:**
- SET POLICY (RETENTION=…), in every form: h/d/m/y, ON COLUMN, WHERE PATTERN, WITH TIMEZONE.
- SET POLICY (SHARING=…).
- SET POLICY (HISTORY MAX_AGE / VERSIONS / both).
- SET POLICY (REPLICATION=…).
- UNSET POLICY (REPLICATION).
- MODIFY COLUMN … SET TAG = (…), including (NONE).

**Proof:**
- **HTTP boundary.** `IrcPolicyPatchTest` (66 tests) compares the native PUT that the OpenHouse client sends with the IRC commit carrying the same patch. The tests cover every exec's exact patch text, on a table that has every policy and on a table that has none.
  - When the native request succeeds, the stored `policies` are byte-equal and every metadata file matches under the golden normalization.
  - When the native request is refused, IRC returns the same status and message and stores nothing.
- **Spark 3.5 SQL.** `IcebergRestPolicySqlTest` runs each statement through Spark 3.5 SQL with Iceberg's `SparkCatalog` over `RESTCatalog` and the OpenHouse extensions. It runs the same statement on an identical table through the OpenHouse catalog (the real client). The stored policies, read through the OpenHouse client, are the same. The replication cron is the only thing ignored, because each write draws it at random.
- **Real `RESTCatalog`.** `IrcRestCatalogPolicyKeyTest` calls `updateProperties` on a real `RESTCatalog`: the policy is applied, and a refused patch is a `BadRequestException`.
- **Capability (V5-2).** The capability is advertised and a client reads it through `RESTCatalog.properties()`.

## Exec patch JSON and whether the server's parse accepts what the client's parse accepts (ran)

These are captured by `PolicyPatchLiteralsTest`: each statement runs on a Hadoop-catalog table carrying `openhouse.tableId`, and the test reads the property back. All 21 strings equal the ones `IrcPolicyPatchTest` sends.

| Statement (after ALTER TABLE t) | Patch |
|---|---|
| SET POLICY (RETENTION=24h) / 30d / 2m / 1y | `{"retention":{"count":24,"granularity":"HOUR"}}` (DAY, MONTH, YEAR likewise) |
| RETENTION=30d ON COLUMN name | `{"retention":{"count":30,"granularity":"DAY","columnPattern":{"columnName":"name","pattern":""}}}` |
| … WHERE PATTERN='yyyy-MM-dd' | same with `"pattern":"'yyyy-MM-dd'"` (**the quotes are kept**) |
| RETENTION=30d WITH TIMEZONE 'America/Los_Angeles' ON COLUMN name WHERE PATTERN='yyyy-MM-dd' | `{"retention":{"count":30,"granularity":"DAY","timeZone":"America/Los_Angeles","columnPattern":{"columnName":"name","pattern":"yyyy-MM-dd"}}}` (unquoted) |
| SHARING=TRUE / true / FALSE | `{"sharingEnabled": TRUE}` (**the token as typed; not strict JSON**) |
| HISTORY MAX_AGE=3d / VERSIONS=5 / MAX_AGE=24h VERSIONS=5 | `{"history":{"maxAge":3,"granularity":"DAY"}}`, `{"history":{"versions":5}}`, `{"history":{"maxAge":24,"granularity":"HOUR","versions":5}}` |
| REPLICATION=({destination:'a', interval:12h}) | `{"replication":{"config":[{"destination":"'a'","interval":"12H"}]}}` (**the destination keeps its quotes**; the server upper-cases it to `'A'`, as natively) |
| …, {destination:'b'} | adds `{"destination":"'b'","interval":""}` |
| UNSET POLICY (REPLICATION) | `{"replication": {}}` |
| MODIFY COLUMN name SET TAG = (PII, HC) | `{"columnTags":{"name": {"tags": [PII, HC]}}}` (**unquoted; not strict JSON**) |
| … (HC) on count, (NONE), (pii), on missing column nope | `[HC]`, `[]`, `[pii]` (an unknown enum constant, read as null by both sides), `nope` |

**Parse equivalence.** The server parses with Gson, as the client does, so every string the client accepts is accepted: lenient mode reads the unquoted tags and `TRUE`.
- An unknown enum value becomes null on both sides.
- Unknown fields are ignored on both sides.
- `{"replication": {}}` needed one adjustment. In the generated client model, `Replication.config` defaults to an empty list, so the server applies the same default. Without it the server answered 400 "config cannot be null" (seen on the first run).
- What the client cannot parse gets a 400 naming the key. The client itself fails locally with `JsonParseException` and sends nothing. Tested inputs: a truncated object, a double-quoted destination (`"destination":""a""`), `[1]`. An empty patch also gets a 400; the client throws a NullPointerException there.
- Gson version: both sides declare 2.8.9 (`buildSrc`). I did not check the resolved versions on the engine classpath (read).

## Native outcomes per patch (ran; native and IRC equal in every case)

**Accepted (200):**
- On the partitioned table that has every policy: RETENTION=24h, all sharing forms, all history forms, both replication forms, the unset, all tag forms, and the raw patches `{}`, a string sharing value (stored as false), several fields, an unknown field, and a lockState (ignored by the merge).
- On the unpartitioned table without policies: column retention, with pattern, with time zone; sharing; history; replication; tags; the raw patches.

**Refused (400), with the native message:**
- d/m/y retention on the hour-partitioned table (granularity).
- Retention without a column on the unpartitioned table.
- Column retention on the partitioned table.
- A missing retention column.
- A time zone on a non-string column; an unknown zone.
- A retention without granularity (bean validation).
- History out of bounds (max age, versions).
- A replication interval that is not a number (`xH`, from the mapper: "For input string").
- A replication without a destination (bean validation).
- RTAS together with replication (feature compatibility).
- A legacy-locked table (the lock message).
- A principal without the privilege gets the native 403 (auth context).

**500 on both:** a raw `{"lockState":{"locked":true}}` on a table with no policies. The native lock-flip check throws a NullPointerException.

**Native checks that do not exist:** sharing values, tags on missing columns, and replication destinations are not validated natively. Both paths accept and store them the same way. For replication, an interval of any count of hours or days is scheduled.

## Every difference between an IRC policy change and the native one

1. **Races.** The legacy client merges onto its loaded copy and sends the whole policies object with that table's version as base. A second writer from the same load gets 409, then reloads and retries. An IRC policy commit asserts only the table UUID (that is what Iceberg's `UpdateRequirements` sends for property updates), and the server merges onto the policies stored at commit time. So two commits from one load both apply, with no 409. Pinned in `twoPolicyCommitsOnTheSameLoadedTableBothApply`. The final state equals the legacy client's result after its retry.
2. **Locked tables.** On a LEGACY-locked table the client cannot build its request: Gson fails on the lock reason, and only SYSTEM_ONLY has a fallback parse. IRC returns the native lock refusal (400, the native message), which is what the native server answers to the request the client would have sent. Pinned.
3. **Unparseable patches.** The client fails locally. IRC returns a 400 that names the key.
4. **500 messages.** The native 500 body prefixes the exception class name; the IRC error carries the message alone (the existing IRC mapping). Only the raw lockState case reaches it.
5. **Staged create.** The staged create validates the patch as the create would. The staged metadata keeps the key as a property, because `RESTSessionCatalog.createChanges` sends the staged properties back, and the completion then applies the patch. The staged response's `policies` stays as before (empty).
   - The completion runs the native mapping: replication schedule and default pattern. So an interval the mapping refuses fails at completion, not at staging.
   - There is no native equivalent: the client stages client-side.
6. **Exception types.** The exception classes are Iceberg's (`BadRequestException`, `ForbiddenException`), not `WebClientResponseWithMessageException`. The message text is the native text.
7. **A key the table holds.** When a table holds the key as a stored property (written raw through the native API), any IRC commit applies it and drops it, exactly as the client does (it reads the key from the table's properties). Removing the key applies nothing and removes it.

**Same as native (ran):**
- The stored state is the same, per the equivalence tests.
- Audit: UPDATE SUCCESS, and UPDATE FAILED for a validator refusal. I fixed the second part: IRC validation now runs inside the audited commit entry through `CommitContext.validation`. As a side effect, all IRC update-validation failures are now audited FAILED, as natively; this closes part of V3's difference 7.
- The number of writes is the same: a patch that changes nothing writes no version on both sides, and a patch combined with other properties or with a schema change is one write.
- The lock, privilege, SYSTEM_ONLY and capability rules come from the core.
- The order of checks: the mapping refusal comes after the lock and privilege checks, as in the native PUT, because the mapping is lazy in the candidate supplier.

**Combined commits (ran):**
- A patch plus a property: the native client sends one PUT with both, and IRC does one write with both, equal.
- A patch plus a schema change (add a column and tag it): one write, validated against the new schema, equal to the native PUT with the new schema. A retention on a column the commit adds is accepted on both. Without that column it is refused.

## Spark over a plain `RESTCatalog`: the client side (ran)

Every SET/UNSET POLICY and SET TAG statement reached the catalog and worked. The policy execs resolve the catalog with `Spark3Util.catalogAndIdentifier` and require only that it is a `SparkCatalog` and that the table has `openhouse.tableId`. They do not use `IcebergCatalogMapper`, so nothing on the client side blocked them. Not exercised here: GRANT, SHOW GRANTS and UNLOCK. They use the mapper and remain V5-3/C2.

A refused statement comes back as Iceberg `BadRequestException` carrying the native message.

## How a client reads the capability

- **Service config:** the override `openhouse.capabilities=policies`.
- **Merging.** `RESTSessionCatalog.initialize` merges config defaults, then the client's own properties, then the overrides (`ConfigResponse.merge`). This is the same on 1.5.2 (`li/openhouse-1.5.2`, line 202) and on 1.12 (`origin/openhouse-1.12.0`, line 218). It passes the result to `BaseSessionCatalog.initialize`, so `RESTCatalog.properties()` returns it (1.5.2:86, 1.12:109). Read for both lines.
- **Ran on 1.5.2:**
  - `IrcReadRoutesTest`: a client value for the key loses to the override.
  - `IrcRestCatalogPolicyKeyTest`: `RESTCatalog.properties()` over HTTP.
  - The Spark itest: the same check.
- **1.12:** compiled only (`-PicebergFork`), not run.
- **In-process adapter:** its `CONFIG_OVERRIDES` carries the same key. It sits next to #97's endpoint list on the v3 line.
  - `OpenHouseRestCatalog.properties()` returns the backing catalog's properties, not the merged map. The rest-shim therefore has to be recognised with `instanceof`, as the design's resolver already does.

## Endpoint list an Iceberg 1.12 client would need (read: `RESTSessionCatalog` on `origin/openhouse-1.12.0`; not added)

**What 1.12 assumes today.** With no `endpoints` in the config, 1.12 assumes `DEFAULT_ENDPOINTS`:
- namespace list, load, create, update and delete;
- table list, load, create, update, delete and rename;
- register table;
- report metrics;
- commit transaction.

It adds views only if `view-endpoints-supported=true`, which is false by default. As a result:
- `tableExists` and `namespaceExists` fall back to a load (HEAD is not used).
- View statements are refused locally.
- Create, update and delete namespace, register, and commit transaction are assumed served but answer 406.

**What to advertise.** Exactly the routes that are served:
- `GET /v1/{prefix}/namespaces`
- `GET /v1/{prefix}/namespaces/{namespace}`
- `HEAD /v1/{prefix}/namespaces/{namespace}`
- `GET /v1/{prefix}/namespaces/{namespace}/tables`
- `POST /v1/{prefix}/namespaces/{namespace}/tables`
- `GET /v1/{prefix}/namespaces/{namespace}/tables/{table}`
- `HEAD /v1/{prefix}/namespaces/{namespace}/tables/{table}`
- `POST /v1/{prefix}/namespaces/{namespace}/tables/{table}`
- `DELETE /v1/{prefix}/namespaces/{namespace}/tables/{table}`
- `POST /v1/{prefix}/tables/rename`
- `GET /v1/{prefix}/namespaces/{namespace}/views`
- `POST /v1/{prefix}/namespaces/{namespace}/views`
- `GET /v1/{prefix}/namespaces/{namespace}/views/{view}`
- `HEAD /v1/{prefix}/namespaces/{namespace}/views/{view}`
- `POST /v1/{prefix}/namespaces/{namespace}/views/{view}`
- `DELETE /v1/{prefix}/namespaces/{namespace}/views/{view}`
- `POST /v1/{prefix}/views/rename`

**Effect of sending the list.** Once any list is sent, the client refuses unlisted routes locally: namespace writes, register, metrics and transactions. Metrics reporting is already off through the config default. `Idempotency-Key` is used only if `idempotency-key-lifetime` is advertised, which it is not.

## Choices where the brief left room

- **What was reused and what was not.**
  - The client's merge could not be reused: it is built on the generated client model, which the server does not have. The five field rules are restated in `PolicyPatch`.
  - Reused unchanged:
    - Gson, configured as the client configures it.
    - The client model's field names, nullability and its one default.
    - A Jackson round trip, so that a tag set becomes a `HashSet` as in a native request.
    - `PoliciesSpecMapper.mapPolicies`.
    - `validateUpdateTable` / `validateCreateTable`.
    - The core.
  - The test oracle `ClientPolicyMerge` is a verbatim copy of the client's method, used only in tests; the 1.2 runtime cannot join the service's test classpath. The Spark itest checks against the real client.
- **Merge base.** The patch is read from the properties the updates leave, so a key stored on the table counts, as on the client. It is merged onto `base.getTableDto().getPolicies()`.
- **Requested policies and mapping.** The requested policies carry the round-tripped, pre-mapping value: that is what the validators see, as they see the native request. The mapping (default pattern, cron) happens in `TableCommitService`, lazily.
- **Validation inside the audit.** `CommitContext.validation` was added so that validation runs inside the audit.
- **Staged create** carries the key (see difference 5).
- **Capability constant.** It is local to `IcebergRestApiHandler` and `OpenHouseRestAdapter`. The shared constants class waits for C2-1.
- **Raw `policies` on create** stays refused. Its message now points to the patch.
- **Spark itests after the code.** The Spark itests were written after the service code and passed on their first run. They were not red first; the service tests were.

## Stopped on

Nothing.

## Interim-refusal tests changed (which assertions changed, and why)

These tests pinned D25's 400. V5-1 replaces that refusal, so they now assert the outcome the OpenHouse client produces.

**`IrcWritePolicyKeyTest`:**
- set: the 400 became 200, with the retention 3 HOUR applied and no key stored;
- with another property: both are applied;
- remove a stored key: the key is removed and the policies are unchanged;
- create and staged-create completion: the policy is applied;
- staged create: the key is carried back;
- The create tables gained hour partitioning, which the retention needs.
- The raw `policies`, table-type and native-path tests are unchanged.

**`IrcRestCatalogPolicyKeyTest`:**
- "refused" became "applied";
- added a test for a refused patch and one for the capability;
- the table is now hour-partitioned.

**Config tests:** `IrcReadRoutesTest` and `OpenHouseRestAdapterEndpointsTest` expect the override instead of empty overrides.

## Test counts (ran)

| Suite | Before (derived: after minus my additions) | After |
|---|---|---|
| `:services:tables:test` | 1430 (base including #98) | **1499, 0 failed, 0 skipped**. Added: `IrcPolicyPatchTest` +66, the auth test +1, `IrcRestCatalogPolicyKeyTest` +2. |
| Spark 3.5 `catalogTest` | 247 | **251, 0 failed, 1 skipped** |
| 1.5 java itest `test` | 136 (no tests added) | **136, 0 failed** |
| `tables-test-fixtures_2.12:test` (the 1.2 fixture) | 37 | **37, 0 failed** |

Other runs, all passing:
- `StoredMetadataGoldenTest`: 13/13, unchanged, inside the full suite.
- `IcebergRestBeanSignaturesTest`: passes after the merge.
- `publish-local.sh`, then `compileJava` and `compileTestJava` with `-PicebergFork`: pass.
- `spotlessCheck` on the whole project: pass.

## Commits

| Commit | Kind | What and why |
|---|---|---|
| 1a1074f2 | test | Equivalence and rule tests, plus the client-merge oracle (red). |
| f7b8159f | test | The interim refusals now expect the policy applied (red). |
| 62722c8f | feat | `CommitContext.validation`: validation inside the audit. Request policies are mapped where the native PUT maps them. |
| 273fc43b | feat | `PolicyPatch` and the `IcebergUpdates` overload. |
| 03f58fe6 | feat | The handler applies the patch on create, staged create, completion and commit. |
| c05b72ba | test | Capability in the config and in the adapter (red). |
| 30dcb365 | feat | The IRC config override. |
| 7ffa993f | feat | The adapter override. |
| f7ff1005 | merge | `origin/irc-integration` (#98). Conflict resolved: kept the V5 code and applied #98's conditional bean and `IcebergRestRequirements`. |
| 92a3f57a | test | Spark 3.5 literals and SQL over `RESTCatalog`. |

## Not run

- Spark 3.5 `statementTest` and `test`.
- A 1.12 `RESTCatalog` at runtime (compiled only).
- The real-HTS (MySQL) mode.
- CI on pull request 99.
- The Spark itests in a red state before the code existed.

I left no JVM of my own running deliberately. Gradle daemons use the 60 s idle timeout.
