# Policies over IRC (V5-1, V5-2): result

Bottom line: every OpenHouse policy statement now takes effect over the Iceberg REST Catalog, with no OpenHouse
catalog class in the engine. The server translates a policy patch carried on a standard commit, and the config
advertises `openhouse.capabilities=policies`. Written by a delegated agent from branch `irc/v5-policies-over-irc`
(pull request 99, merged as 1fa8cb34). "Ran" means run. "Read" means taken from code only.

## What works (ran)

The statements, all through Iceberg's `SparkCatalog` over `RESTCatalog` with the OpenHouse extensions:
- SET POLICY (RETENTION=...) in every form: hours, days, months, years, ON COLUMN, WHERE PATTERN, WITH TIMEZONE.
- SET POLICY (SHARING=...), SET POLICY (HISTORY MAX_AGE, VERSIONS or both), SET POLICY (REPLICATION=...).
- UNSET POLICY (REPLICATION).
- MODIFY COLUMN ... SET TAG = (...), including (NONE).

Proof:
- `IrcPolicyPatchTest` (66 tests) compares the native PUT the OpenHouse client sends with the IRC commit carrying the
  same patch, on a table with every policy and on a table with none.
  - When native succeeds, the stored `policies` are byte-equal and every metadata file matches under the golden
    normalization.
  - When native refuses, IRC returns the same status and message and stores nothing.
- `IcebergRestPolicySqlTest` runs each statement through Spark 3.5 on an identical table through the OpenHouse
  catalog. The stored policies, read through the OpenHouse client, are the same. Only the replication cron is
  ignored, because each write draws it at random.
- `IrcRestCatalogPolicyKeyTest` calls `updateProperties` on a real `RESTCatalog`: the policy is applied, and a
  refused patch is a `BadRequestException`. A client reads the advertised capability through `properties()`.

## Patch literals and parse equivalence (ran)

`PolicyPatchLiteralsTest` captured the 21 strings the Spark 3.5 execs write. They equal the ones
`IrcPolicyPatchTest` sends. Some are not strict JSON, and the server accepts them as the client does:
- Sharing is the token as typed, for example `{"sharingEnabled": TRUE}`.
- Tags are unquoted, for example `{"columnTags":{"name": {"tags": [PII, HC]}}}`.
- A replication destination and a retention pattern keep their quotes (the server upper-cases the destination, as
  natively).

The server parses with Gson, as the client does. An unknown enum value becomes null and unknown fields are ignored,
on both sides. `{"replication": {}}` needed the generated model's default of an empty `config` list, or the server
answered 400 "config cannot be null". A patch the client cannot parse (a truncated object, a double-quoted
destination, `[1]`, an empty patch) gets a 400 naming the key. Both sides declare Gson 2.8.9. The version that
resolves on an engine classpath was not checked (read).

## Native outcomes per patch (ran; native and IRC equal in every case)

- Accepted (200): every statement form, on the table that has every policy and on the table that has none.
- Refused (400) with the native message: a retention granularity the partitioning cannot carry, retention with no
  column or on a missing column, a time zone on a non-string column or an unknown zone, history out of bounds, a
  replication interval that is not a number or has no destination, replication together with RTAS, and a
  legacy-locked table. A principal without the privilege gets the native 403.
- 500 on both: a raw `{"lockState":{"locked":true}}` on a table with no policies, from a native NullPointerException.
- Not validated natively, and so not on IRC: sharing values, tags on missing columns, replication destinations.

## Every difference between an IRC policy change and the native one

1. Races. The legacy client merges onto its loaded copy and sends the whole `policies` with the table version as
   base, so a second writer from the same load gets 409 and retries. An IRC policy commit asserts only the table
   UUID, and the server merges onto the policies stored at commit time. Two commits from one load both apply, with no
   409. The final state equals the legacy client's result after its retry (`twoPolicyCommitsOnTheSameLoadedTableBothApply`).
2. Locked tables. On a LEGACY-locked table the client cannot build its request, because Gson fails on the lock
   reason. IRC returns the native lock refusal, which is what the native server answers to the request the client
   would have sent.
3. Unparseable patches. The client fails locally. IRC returns a 400 that names the key.
4. 500 messages. The native body prefixes the exception class name. The IRC error carries the message alone.
5. Staged create. The staged create validates the patch as the create would. The staged metadata keeps the key as a
   property, because `RESTSessionCatalog.createChanges` sends staged properties back, and the completion applies
   the patch. The completion runs the native mapping, so an interval it refuses fails at completion, not at staging.
   There is no native equivalent, because the client stages client-side.
6. Exception classes are Iceberg's (`BadRequestException`, `ForbiddenException`), with the native message text.
7. A key the table already holds as a stored property (written raw through the native API) is applied and dropped by
   any IRC commit, as the client does. Removing the key applies nothing and removes it.

Same as native (ran): the stored state; the audit (UPDATE SUCCESS, and UPDATE FAILED for a validator refusal); the
write count (a patch that changes nothing writes no version); and the lock, privilege, SYSTEM_ONLY and capability
rules, which come from the core. The mapping refusal comes after the lock and privilege checks, as natively.

Combined commits (ran): a patch plus a property, or plus a schema change, is one write equal to the native PUT.

Audit side effect: IRC validation now runs inside the audited commit (`CommitContext.validation`), so every IRC
update-validation failure is audited FAILED, as natively. This closes part of V3's difference 7 in
`27-v3-irc-write-result.md`.

## How a client reads the capability

- The config override is `openhouse.capabilities=policies`. `RESTSessionCatalog.initialize` merges config defaults,
  then client properties, then overrides, so the server wins (read, 1.5.2 and 1.12).
- Ran on 1.5.2 (`IrcReadRoutesTest` and the Spark itest). On 1.12 it was compiled under `-PicebergFork` only.
- The in-process adapter carries the same override, but its `properties()` returns the backing catalog's
  properties, so the shim has to be recognized with `instanceof`, as the resolver does.
- What a 1.12 client needs to advertise served routes was built later as V10a
  (`36-v5-3-grants-unlock-endpoints-result.md`).

## Choices where the brief left room

- The client's merge could not be reused, because it is built on the generated client model. The five field rules
  are restated in `PolicyPatch`. Gson, the model's field names and default, `PoliciesSpecMapper.mapPolicies`,
  `validateUpdateTable`, `validateCreateTable` and the core are reused.
- The patch merges onto the policies stored on the base table. Requested policies carry the pre-mapping value, which
  is what the validators see natively. The mapping happens lazily in `TableCommitService`.
- The raw `policies` property on create stays refused. The test oracle `ClientPolicyMerge` copies the client's method.
- D25's interim-refusal tests now expect the policy applied (`IrcWritePolicyKeyTest`,
  `IrcRestCatalogPolicyKeyTest`, and the two config tests).
- The Spark itests were written after the service code and passed on their first run. The service tests were red first.

## Test counts (ran)

| Suite | Before | After |
| --- | --- | --- |
| `:services:tables:test` | 1430 | 1499, 0 failed, 0 skipped |
| Spark 3.5 `catalogTest` | 247 | 251, 0 failed, 1 skipped |
| Java 1.5 itest `test` | 136 | 136, 0 failed |
| `tables-test-fixtures_2.12:test` (Iceberg 1.2 fixture) | 37 | 37, 0 failed |

Also passing: `StoredMetadataGoldenTest` 13 of 13, `IcebergRestBeanSignaturesTest`, compilation under
`-PicebergFork`, and `spotlessCheck` on the whole project.

## Not run

- Spark 3.5 `statementTest` and `test`; a 1.12 `RESTCatalog` at runtime (compiled only).
- The real House Tables mode (MySQL); continuous integration on pull request 99.
- The Spark itests in a red state before the code existed.
