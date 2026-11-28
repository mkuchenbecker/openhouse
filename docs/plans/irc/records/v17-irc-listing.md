# V17: House Tables listings on the /iceberg routes

Bottom line (done; pull request #147 draft, CI green): the namespace check of six /iceberg routes now reads one row (native paged
search, page of 1) instead of every table row of the database; an empty table list reads one listing
fewer. Two costs stay, each needing a House Tables API that does not exist: the database-list fallback on
a namespace miss, and the directory-in-use scan of a staged-create completion and a register.

Worktree /home/user/oh-v17, branch irc/v17-irc-listing from origin/irc-integration 22017134.
Commits: 790ba6db pinning test; f6f9aaed fix. Both pushed.

## Route table (measured by IrcHouseTableReadsTest, db of 3 tables, T = tables in db, D = databases)
| route | needs | before | after | native |
|---|---|---|---|---|
| HEAD /namespaces/{db} (exists) | db holds a table | 1 list, T rows | 1 page of 1 | views svc probe: page of 1 (read) |
| HEAD /namespaces/{db} (missing) | same, then db list | list (0) + findAll (D) | page (0) + findAll (D) | - |
| GET /namespaces/{db} | same | 1 list, T rows | 1 page of 1 | - |
| GET /namespaces?parent={db} | same | 1 list, T rows | 1 page of 1 | - |
| GET /namespaces | every db | findAll (D) | unchanged | GET /v1/databases: findAll (D), measured |
| GET /namespaces/{db}/tables | every table | 1 list, T rows | unchanged | unpaged search: 1 list T, measured |
| same, pageSize=1 | page size ignored, full list | 1 list, T rows | unchanged | v2 paged search size 1: 1 page, measured |
| GET /namespaces/{none}/tables | empty -> db list | list + list + findAll | list + findAll | - |
| HEAD /tables/{t} | one key | findById 1 | unchanged | keyed |
| GET /namespaces/{db}/views (views off) | ns check | 1 list, T rows | 1 page of 1 | - |
| POST /tables/rename to another db | dest ns check | 1 list, T rows (then 406) | 1 page of 1 | - |
| staged-create completion | no row uses dir | list T + soft-deleted pages | unchanged | native create: none |
| register (read, not pinned) | same method | list T + soft-deleted pages | unchanged | - |
Survey (run, not kept): config, load, create, commit, same-db rename, drop, table-exists all keyed only.

## Needs a new House Tables API (not added)
- Namespace miss: "does any row (table or view) have databaseId exactly X". HTS JDBC has
  findAllDistinctDatabaseIds(databaseId, pageable) but no endpoint/client exposes the filter (and it is
  case-insensitive, today's check is exact). The views probe exists but is case-insensitive and throws on a
  non-canonical row: an answer change, so not used.
- Directory in use: "rows, live and soft-deleted, of db X with tableUUID U or tableLocation under P".

## Decisions
- tablesService.searchTables(db,0,1,null,null,null): native paged search, no fields so no authorization,
  same HTS predicate as the unpaged one (read UserTableHtsJdbcRepository: same COMMON_FILTER + TABLE_ROW).
- listTables empty: skip the second table probe; a table created between the two reads now gets the db
  list answer (it is listed then too), racy either way.

## Runs (local)
- Default: Irc* + api.irc.* service tests 40 classes 367 tests 0 failed (after fix).
- Compat kit default: 118 non-passing = exactly expected-outcomes.txt; list unchanged.
- Reference (-PicebergLinkedIn): 5 touched/route classes, 118 passed.
- check-test-groups.py: partition holds. origin/irc-integration had nothing new (no merge needed).
- "Before" for route tests: base 22017134 CI (not rerun locally).

## Pull request
#147 draft, head f6f9aaedea92e7bd3dd0f6eb927d18cc4d7aa482. CI: 13 jobs success, iceberg-linkedin Docker job skipped (as on base). No Dataloader jobs ran on the pull request.
