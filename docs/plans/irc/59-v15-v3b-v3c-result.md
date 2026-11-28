# V15, V3b and V3c result: three service follow-ups

Bottom line: done and merged. A write whose House Tables row would not fit is now refused with 400 before anything is
written, on both APIs; the IRC config route advertises the FileIO that matches the cluster's default storage; and a
failed IRC request is audited like a failed native one. One native behaviour changes for clusters whose store was
created from `schema.sql`, described below. It is pull request 121 in mkuchenbecker/openhouse, merged into
`irc-integration` on 2026-11-23 as merge commit `72947228dc77359a6c1b76fef667ba108ee7769d` (pull request head
`3de23544`). Nothing is merged outside the forks. The source record is `v15-v3b-v3c-service-followups.md` in the
session's reports directory (outside the repository). The decision is D41 in `51-decisions-continued-4.md`, as
amended.

## What is now true

- V15: a table create, commit, snapshots PUT, rename or lock, or a view write, on the native API or the IRC routes,
  whose metadata location would exceed the limit is refused with 400 before anything is written. The next commit on
  the same base lands. The message names the configured limit. Format v3 tables are held to the same limit. The check
  runs last of the pre-commit checks and before the retry cache. The limit is exact: 255 fits and 256 is refused.
- The limit is a cluster property, `cluster.housetables.metadata-location-max-length`, default 255, the width in the
  production DDL under `services/housetables/ddl/`. A value that is not positive means the default. `schema.sql` and
  the DDL are unchanged, and `DEPLOY.md` says when to set 512.
- A rename to a name over 255 characters is refused with 400. The IRC staged create is checked; the native staged
  create and staged replace are not, because they write no row.
- V3b: the IRC config route's `io-impl` follows `cluster.storages.default-type`: hdfs and local give `HadoopFileIO`,
  s3 gives `S3FileIO`, adls gives `ADLSFileIO`, and an unknown type gives none. Only the class name is sent; endpoint
  and credentials stay the client's configuration.
- V3c: a failed or refused IRC request produces the same `ServiceAuditEvent` as a failed native one, and requests
  under `/iceberg/` name the tables service, successes included (before, a success named `UNRECOGNIZED`).

## The behaviour change

On a House Tables store created from `schema.sql`, whose `metadata_location` column is 512 wide, a write whose
metadata location is 256 to 512 characters long worked before and is refused by default now. A deployment on such a
store sets `cluster.housetables.metadata-location-max-length` to 512. The property must never exceed the column's real
width. A store built from the production DDL (255) is unaffected.

## What it used to do

The service wrote the metadata file first and the row second, so an over-long location failed at the row write. The
caller was told the commit state was unknown (native 503 or 500, IRC 500 `CommitStateUnknownException`, about seven
seconds on the 1.5.2 line), the metadata file stayed, and a failed commit to an existing table made every later commit
on that base answer 409 for five minutes. Views answered 500. All of this was run on both Iceberg lines against the
H2 stand-in for the store.

## Evidence

Run locally on head `3de23544` (with `irc-integration` at `e2e02bfa` merged in), JDK 17. Counts are tests, failed,
skipped.

| Suite | Default line (Iceberg 1.12) | `-PicebergLinkedIn` (1.5.2) |
| --- | --- | --- |
| `:services:tables:test` | 1665, 0, 1 | 1665, 0, 32 |
| `:iceberg:openhouse:internalcatalog:test` | 327, 0, 0 | 327, 0, 0 |
| `:services:common:test` | 37, 0, 0 | 37, 0, 0 |
| `tables-test-fixtures-iceberg-1.5_2.12:test` | 41, 0, 0 | 41, 0, 0 |
| Spark 3.5 `catalogTest` | 279, 0, 1 | 279, 0, 7 |
| `restCompatKitTest` | 160: 41 passed, 96 failed, 23 skipped | not in that build |
| Spark 4.2 `test` and `smokeTest` | 48, 0, 0 and 65, 0, 0 | not in that build |
| `spotlessCheck` | passed | passed |

- The kit's failures are exactly the 119 listed in `expected-outcomes.txt`, the same as on the base.
- The three format v3 limit tests skip on the 1.5.2 line, which accounts for part of its skips.
- Pull request CI passed both jobs at `3de23544` (run 45288650359).
- Each new test was first committed pinning the old behaviour, then changed with the fix.

## Known gaps

- The retry cache is still reached by other late failures on an existing table: a snapshot validation 400, a storage
  error or a House Tables failure still makes every commit on that base answer 409 for five minutes. Only the
  row-width failure no longer gets there.
- A rename to a name of 129 to 255 characters still succeeds, and on a `schema.sql` store (`table_id VARCHAR(128)`) it
  fails after the metadata file is written. Pinned by a test, not fixed. Work item V19.
- A table that the storage selector places on a storage type other than the cluster default is advertised the
  default storage's FileIO class. Work item V20.
- The other bounded columns of the row cannot be exceeded by a request, except `table_id` as above.
- Not run: the real House Tables service on MySQL (there the answer is probably 409, read from code, not run), a real
  S3 or ADLS store, an Iceberg client reading through the advertised class, Spark 3.5 `statementTest` and `test`, and
  the delta-harness.
- A missing or bad token (401) is audited on neither API; read from code, not run.
