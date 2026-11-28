# V1 result: the commit core on the tables service

Bottom line: every native write to an existing table now goes through one core that runs the service-layer rules
in their native order and persists through the unchanged repository. A new entry point takes a complete Iceberg
candidate. Nothing changed for native requests. Create, staged create and lock are not in the core. Written by a
delegated agent from branch `irc/v1-candidate-commit` (openhouse pull request 89, based on pull request 88 at
a2edc223). The rule inventory is in `24-commit-rule-inventory.md`.

## What goes through the core

- Every write through `PUT /tables/{t}` (property, schema, policy, sort-order and no-op updates) and through
  `PUT .../iceberg/v2/snapshots` (append, delete, overwrite, refs and branches, expiry and replace commits).
- Both routes decode the request into a `TableCommit` and call `TableCommitCore`. The three guards are unchanged:
  the base-version check, the retry cache and the house-table compare-and-set.
- New entry point: `TableCommitService.commit(TableIdentifier, String baseVersion, TableDtoWithMetadata base,
  TableMetadata candidate, CommitContext)`. It converts the candidate into the core's input and runs the same core.
  It works for property, schema, sort-order, policy and snapshot commits. 18 tests cover it, and each rejection is
  compared with the native answer on the same table.

## How the candidate is carried, and why

The core carries the candidate in the shape of a native write, a `TableDto`, and does not restate its rules over two
`TableMetadata` objects. The entry point converts a candidate into that shape. Reasons: the rules' extension points
(`PreservedKeyChecker`, `TablePolicyManager`, `PoliciesSpecMapper`, `PartitionSpecMapper`,
`ReadBridgeStripProtection`, `AuthorizationUtils`) take a `TableDto` and deployed overrides receive it; several
messages embed request text; and Iceberg's `TableMetadata.Builder` rejects some invalid candidates itself, so building
one up front would change which error a bad native request gets.

## What the core does, in order

Lock-flip check (table PUT only); legacy lock refusal, worded by kind; write-path privileges and the system-only
lock check; the table PUT's no-op short-circuit; then the candidate is computed on first use, so a native table PUT
still maps its request only after those checks; then the capability gate, system-only lock preservation and the
read-bridge strip (`ColumnDefaultException` becomes 422); then `repository.save(candidate)` with the route's wording
of 400, 409 and, for table PUT only, 503. Everything after the save is unchanged code in the repository, `doCommit`
and the house table store.

## What does not go through the core

- Create, staged create and the CTAS first commit: a different rule set (database privilege, initial-version check,
  table count limit, UUID derivation, location allocation, cluster-forced properties). Location and UUID are outputs
  of a create, not inputs. V3 needs its own entry, or this one with a null base.
- Staged replace: natively it runs only the write and system-only checks, so the core would add checks, which is a
  behavior change. A snapshots PUT replace commit does go through the core.
- Lock and unlock: their own rules, persisted through the same save helper and so the same repository path.
- Rename, drop, restore and purge: no candidate is written.

## Ten rules that cannot be stated over a metadata pair

Detail is in `24-commit-rule-inventory.md`, section 3.

1. The native no-op short-circuit compares request text with the stored table (`CommitContext.unchanged`, native only).
2. Reserved properties and the replication skip read the request's reserved keys as sent. An IRC candidate has that
   shape.
3. Policies are a second channel: the raw key is reserved and the structured value replaces it. This needs
   `CommitContext.requestedPolicies`. The entry point keeps stored policies when none is given.
4. Table type is a request field stored as a reserved property. The entry point keeps the stored type.
5. Snapshot payload text is parsed inside `doCommit`, after the version, max-ref-age and retry-cache checks.
   Parsing earlier would reorder errors. The entry point serializes the candidate's snapshots and refs into the
   same payload.
6. Schema text is read by the strip and by the schema-evolution message.
7. Construction order: Iceberg's builder rejects some candidates before OpenHouse's rules can.
8. Operation kind decides the lock wording, the 409 and 503 wording and the table-PUT-only rules.
9. Replace and staged intents are flags, not metadata.
10. Extension points are typed on `TableDto`.

Five commits, each body saying why: golden test, HTTP pins, core, entry point tests (red when committed), entry
point. No existing test was changed.

## Test counts (ran, JDK 17)

| Suite | Base a2edc223 | Branch 7f7c5638 |
| --- | --- | --- |
| `:services:tables:test` | 1296, 0 failed | 1329, 0 failed (+33: golden 11, update rules 4, entry point 18) |
| `:iceberg:openhouse:internalcatalog:test` | 319, 0 failed | 319, 0 failed (up to date; module untouched) |
| Spark 3.5 `catalogTest`, `statementTest`, `test` | 219 (1 skipped), 137, 124 | same, 0 failed |

The first full branch run of the tables tests lost its daemon to the shared memory limit, so it was split by package
(432 in `e2e.*`, 897 elsewhere). The Spark base counts were read from the V2 record, not re-run.

## Harness (legacy path, in-memory house table stub; ran)

The `delete parquet` slice gave 238 cases, 237 passed, 1 skipped, 0 failed, as recorded. The full stub-mode run gave
2574 cases, 2560 passed, 3 failed, 11 skipped in 1214 seconds, identical to the baseline. The three failures are the
baseline's lock cases (`control.lock.enforcement`, `interact.rtas.onLockedTable`, `hazard.lock.starvesMaintenance`).

## Stored-metadata golden comparison (ran)

The test reads every metadata file each scenario leaves, oldest first, and compares it with files recorded on the
base. Scenarios: create, property PUT twice, schema PUT, policy PUT, sort-order PUT, no-op PUT, two appends, an
append then a delete, a refs-only branch, a replace commit, and lock then unlock. Versions per scenario are the
same before and after. Two runs on unchanged code gave identical files.

Normalized, and nothing else: all timestamps (`last-updated-ms`, `timestamp-ms`, `openhouse.lastModifiedTime`,
`openhouse.creationTime`); every UUID in any string; snapshot ids drawn by the test client, renamed by order of first
appearance; and, inside `policies`, the random replication cron schedule and the lock's creation time.

## What was not identical

Nothing for native requests. Two comparisons in the new tests are not byte for byte, by nature of the input: a
conflict is compared through the error handler's wording, and a built candidate's schema id is the one Iceberg's
builder assigns. One finding is pinned as it is: a snapshots PUT on a locked table answers 400, not 423.

## What V3 can rely on

- `TableCommitService.commit(...)` with the base from `TablesService.loadTable(...)` and the base version taken from
  the base table's location. It gives the native update rules and guards.
- `CommitKind.SNAPSHOTS_UPDATE` gives the snapshots-route wording. `TABLE_UPDATE` adds the lock-flip check and the 503
  mapping of unknown commit state.
- Snapshot and ref changes go as the full snapshot list plus refs, as natively, so the stale-snapshot and
  column-default checks apply. Edits of reserved `openhouse.*` keys and `policies` get the native 400. Max-ref-age,
  the capability gate and partition and schema evolution apply as natively.

## What V3 cannot rely on

- Create and staged create are not built.
- A candidate that changes location, UUID, format version or statistics files, removes every snapshot, or needs a
  partition spec the native API cannot express gets a 400 naming the field (D19). A partition change that keeps field
  names is accepted and not stored, as natively.
- Update requirements must be checked by V3 against the base. The core checks only the base version.
- Errors are the native ones (409, and 503 on `TABLE_UPDATE`), so V3 must map them to IRC semantics.
- The capability gate, the column-default bypass and the action declaration read request-scoped beans, so V3 calls
  must run inside the HTTP request.
- Policies change only through `CommitContext.requestedPolicies`. No IRC update maps to it yet.
## Ran versus read

Ran: the baseline and branch suites above, the golden recording twice, the new HTTP pins on unchanged code, the
harness runs, and `spotlessCheck` on `:services:tables`. Read, not run: the Spark baseline counts and the
full-harness baseline. Not run: the real house table service harness mode, internalcatalog as a fresh execution on
the branch, and any Iceberg 1.12 opt-in build.
