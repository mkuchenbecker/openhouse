# Not-recovered cases — precise reasons

The commit-path guard recovers the 13 cases whose gap is "service-layer update/replace validation
was bypassed". The remaining 5 of the original 18 are NOT update/replace-validation gaps and are out
of scope for this change; each is recorded here with its actual cause.

## `partition.dateDay.rejected` — CREATE-path message contract, not a commit guard

`CREATE TABLE ... PARTITIONED BY (days(dt))` where `dt` is a `DATE` column. This is a CREATE, which
already routes through the service via `toCreateUpdateTableRequestBody` → `PartitionSpecMapper`. The
server **already rejects it** (a `RequestValidationFailureException` → 400 → `RuntimeException`),
but with the message *"OpenHouse cannot model transform 'day' on clustering column 'dt'; supported
transforms are identity, truncate[n], bucket[n]"*. The harness asserts the substring
`"Unsupported column"`, so it fails on the message text, not on the (correct) rejection. Changing the
mapper's message is a CREATE-path concern with blast radius across the other `partition.*` transform
tests and is intentionally left alone.

## `ddl.ns.createRejected` — namespace semantics, not a table commit

`CREATE NAMESPACE openhouse.a_new_db` expects `UnsupportedOperationException("...not supported")`.
The controller models databases as implicit with **optimistic namespace existence** (`namespaceExists`
HEAD returns 204 for any single-level namespace — see `rest-endpoint/design-decisions.md` §2). So
Spark's pre-check sees the namespace as already existing and raises `NamespaceAlreadyExistsException`
instead. Making `CREATE NAMESPACE` reject would require inverting that optimistic-existence contract,
which the create-table-in-a-new-db happy path (`create.schema`) depends on. This is a namespace-layer
decision, unrelated to the commit-path validation this change targets.

## `ddl.renameTable.conflict` — client-specific exception type unreachable in REST-first

The test asserts `Check.intercept[WebClientResponseWithMessageException]` — the OpenHouse **custom
client's** exception type. In REST-first mode the driver is a stock `org.apache.iceberg.rest.RESTCatalog`,
which on an HTTP 409 raises Iceberg's `AlreadyExistsException`, never the OpenHouse client type. So
even if the server correctly returned 409 on rename-to-existing (the underlying catalog rename does
not currently pre-check the destination), the asserted type can never be produced by a stock client.
The gap here is a harness expectation written against the old client, not a server validation guard.

## `hazard.rename.consumers` — rename correctness, not a validation guard

Fails with `TABLE_OR_VIEW_NOT_FOUND ... t_..._rn` after `ALTER TABLE ... RENAME TO`: the renamed
table (with its branch/time-travel consumers) is not resolvable afterward. This is a correctness
problem in the rename path (`CatalogHandlers.renameTable` → `OpenHouseInternalCatalog.renameTable`)
and its interaction with the REST load path, not a bypassed update/replace guard. It needs its own
investigation and is out of scope.

## `surface.conc.appendAppend` — concurrency/CAS semantics, not a validation guard

Two concurrent 3-append writers; the test requires that any losing append throw a **typed** commit
conflict and that the final row count equal the number of appends that actually landed. It fails with
`row count must equal successful appends (3 seed + 6 landed)`, i.e. appends were lost without a typed
rejection. This is about the commit CAS / retry behavior on the REST path (`OpenHouseInternalTable-
Operations` `COMMIT_KEY` handling and the stock client's retry), not the service-layer update
validation recovered here.
