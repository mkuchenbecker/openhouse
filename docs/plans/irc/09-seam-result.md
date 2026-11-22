# Result of the plugin seam (work item C1)

Bottom line: the seam works on the Spark 3.5 line. With one setting, the plugin runs as Iceberg's
`RESTCatalog` over an in-process client that sits on top of today's OpenHouse client. All 21 original
smoke scenarios and all 18 contract tests pass on the new path with no expectation changed. Today's path
is untouched: three production files were added and none was modified. Observed by a delegated agent on
2026-11-22; it is in draft pull request 84 on the openhouse fork. The design it follows
(`07-seam-design.md`) has not been reviewed by Mike.

## How it was proven

- Both suites were first run on the new path and failed, before any production code existed.
- A first version that sent every request through Iceberg's stock handlers failed 4 smoke scenarios and
  3 contract tests. Those differences were then fixed inside the new classes.
- 10 smoke scenarios and 2 contract tests were added for differences the original suites did not catch.
  Each was asserted on today's path first. All pass on both paths.

## Differences that remain between the two paths

The full delta-harness gives identical counts on both paths with the real embedded house-table service (2792 cases, 2778 passed, 3 failed, 11 skipped) and one extra failure on the REST-shaped path with the stub (2574 cases, 2559 passed, 4 failed, 11 skipped, against 2560 passed and 3 failed on the legacy path). The legacy full-run counts were taken from the earlier baseline run and were not repeated for this note. The counts and both differences are detailed in `11-harness-via-seam.md`.

| What differs | Today's path | REST-shaped path | Shown by a test? | Fixable where |
| --- | --- | --- | --- | --- |
| When the RTAS / REPLACE gate runs, and what it leaves behind | Stage-replace is sent before any data is written, so a gated table fails there. | No stage-replace; data files are written first, then the server's gate rejects the commit and the data file stays in the table directory. | No test; seen on disk by hand. | Only by calling the backing stage-replace before delegating, or by a REST replace signal. Decision needed. |
| How a replace commit is recognized | An explicit flag is set where the replace is known. | Inferred from the request shape Iceberg 1.5.2 builds; deterministic for this client, not for others. | Adapter unit tests (5 cases); s05, s22, s23. | An explicit replace signal on the wire. Decision needed. |
| A requirement-free commit from a handle with out-of-date metadata | Sent with the base version; the server answers 409, the client refreshes and retries. | The handler applies the change to fresh metadata, so the base check never fires and the commit succeeds first time. The concurrent case was not reproduced. | No test on the Spark path; the raw case is covered by rawCommitWithStaleBaseFails. | Carrying the base version needs a non-spec requirement or a server change. Decision needed. |
| Server and storage calls per operation | `refresh()` skips the metadata read when the location is unchanged; a commit sends one request. | Every refresh re-reads metadata, and every commit loads the table first. | None. | Cost only; caching in the adapter could fix it later. Decision whether it matters. |
| CTAS when another writer creates the same table between stage-create and commit | Same ops from stage-create to commit; the server answers 409. | The create commit uses fresh ops and fails with "table already exists". Inferred from code, not reproduced. | None. | Keep staged ops per identifier in the new classes. Decision needed (stateful handler). |
| Metrics reporter instances | One reporter per catalog. | Two reporters: the session catalog's and the seam's; REST reporting is off, so nothing is posted. | None. | Only with private access to the session catalog's reporter; or accept. |
| Tables not wrapped by the stale-base check | Every table operation checks base against current. | Tables from transactions and metadata tables keep the handler path, which sends a stale commit. | None. | Wrap more entry points in the new classes. Low value. |
| Java API surface of the catalog object | Public `OpenHouseCatalog` API and protected `updateAuthToken`. | Extends `RESTCatalog`; code that casts to `OpenHouseCatalog` or subclasses it breaks. No such code in this repo. | None. | Decision: document it, or expose a backing accessor. |
| Catalog properties that mean something to `RESTSessionCatalog` | Ignored by today's catalog. | Read by the session catalog (token, credential, oauth2-server-uri, prefix, header.*); an OAuth refresh goes to a route that does not exist. | None; inferred from code. | Strip or reject them in `initialize` in the new classes. |
| View replace / alter | Client-side view commit. | Built client-side and committed through a handler that retries up to 4 times on conflict. | None; s18 covers create, select and drop only. Not verified. | Own view-commit handler in the new classes. |
| Multi-table `RESTCatalog.commitTransaction` | No such API. | No route; fails with `BadRequestException`. | None. | Not applicable (new API). Decision whether to route it. |
| Iceberg 1.2 / Spark 3.1 line | Today's path. | No seam (out of scope). | Contract tests on spark-3.1 run the legacy path only. | Separate copy needed. Decision. |
| Concurrent RTAS and INSERT, in-memory house-table stub | No loss in 50 of 50 stub races. 0 of 50 lost with the real service on the REST-shaped path. | RTAS silently lost in 2 of 50 stub races (orc only), leaving 4 rows where 2 or 3 are valid. | Yes: `surface.conc.rtasVsAppend @ orc`. Diagnosed by reading: the stub has no version check, and the adapter's reload-then-commit changes the timing so the two commits overlap. | The stub, which has no version check. The real service gave no loss. |
| Snapshot history after an INSERT commits just before an RTAS (final rows the same) | INSERT's snapshot removed in 50 of 50 runs (stub). | INSERT's snapshot kept in the table history in 30 of 50 stub races and 41 of 50 real-service races. | No case asserts it. Cause by reading: the adapter applies the replace to a freshly loaded head (`OpenHouseRestAdapter.java:406-410`), while the legacy path commits metadata built from the transaction's starting point, and the server removes snapshots the client payload does not list. | Not decided. |

## Where the design note was wrong

- CTAS does not work at all through `CatalogHandlers`: the server rejects the empty-schema stage-create (400, s04); the note called it a difference only.
- An unflagged RTAS commit is applied as an ordinary update when the schema is compatible (s22, s23) and rejected as schema evolution otherwise (s05), so CREATE OR REPLACE does not match.
- A replace can be told from the request shape for Iceberg 1.5.2, deterministically, but this is not robust across client versions.
- `setProperties` and `removeProperties` on a namespace give "Describing database is not supported", because the handler describes the namespace first (s24).
- HEAD is needed to keep today's answers for `tableExists` (locked tables) and `namespaceExists`; the note said it was not needed.
- `ResolvingFileIO` breaks merge-on-read writes on the LinkedIn fork (s10), which is more than a class difference.
- The REST metrics reporter is added next to the configured one, so it is a second reporter; the seam turns REST reporting off.
- The client-builder function receives only properties, not the Spark catalog name, so the backing catalog is built in `initialize(name, props)`.
- The handler's four retries are correct for `CatalogHandlers`, but the seam does not use that handler for tables.
- Verified, not left open: the Hadoop conf reaches the backing catalog, Spark 3.5 CREATE NAMESPACE works on both paths (s19), a null-location view passes validation (s18), and `table.name()` is the same.

## Not verified

- Any real HTTP transport: the new path is in-process only.
- HDFS storage, concurrent CTAS, OAuth properties, view replace.
