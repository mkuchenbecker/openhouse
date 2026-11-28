# tables-test-fixtures-iceberg-1.5

Test support for code that embeds an OpenHouse tables service: `SpringH2TestApplication`, `OpenHouseLocalServer`,
and in-memory stand-ins for the House Tables store (`HouseTablesH2Repository`) and the namespace store
(`HouseNamespacesInMemoryRepository`). It is published as `tables-test-fixtures-iceberg-1.5_2.12`.

## Notes for consumers

### The House Tables stand-in answers as the real store does

`HouseTablesH2Repository` is held to `HouseTableRepositoryContract`. The same contract runs against the real
`HouseTableRepositoryImpl` over a real House Tables service. Where the stand-in used to do nothing or overwrite,
it now answers as the real store does.

**What now throws, or answers differently:**

| Call | Before | Now |
|---|---|---|
| `findById` of a missing row or a view | `Optional.empty()` | `HouseTableNotFoundException` (404) |
| `deleteById`, `rename`, `restoreTable` of a missing row (or of a view, for delete and rename) | nothing | `HouseTableNotFoundException` |
| `save` or `saveView` over a row of the other entity type, at any letter case | overwrite | `HouseTableConcurrentUpdateException` (409) |
| `restoreTable` over a live row | overwrite | `HouseTableConcurrentUpdateException` |
| `rename` onto an occupied key, at any letter case | 409, or overwrite for another spelling | `HouseTableConcurrentUpdateException` |
| a value wider than its column (255) | an H2 `DataIntegrityViolationException` | `HouseTableConcurrentUpdateException` |
| a name that is not letters, digits and underscores, or an empty metadata location, on a write, a keyed read or a delete | accepted | `HouseTableCallerException` (400) |
| the same on a table or soft-deleted listing | accepted | `WebClientResponseException.BadRequest`, untranslated, as the real repository passes it |
| `rename` to another database, or to the same name | moved or overwrote | `HouseTableCallerException` |
| `delete(entity)`, `saveAll`, `count`, `findAll(Sort)`, `findAllById`, `deleteAllById`, `deleteAll(entities)` | served by Spring Data | `UnsupportedOperationException` |

**What a read returns now:**

- A read returns only the fields House Tables keeps: the key, the metadata location (also as the version), the
  storage type (`hdfs` unless given), the creation time, and the entity type. There is no UUID, cluster, URI or
  creator. A table's UUID is in its metadata (`openhouse.tableUUID`) and in the name of its directory.
- Keys ignore letter case on every operation and keep the spelling they were created with. A rename writes the
  source database as the caller spelled it.
- Listings are sorted and paged in the store, by the sort's property names only; the real repository drops the
  direction. With no sort, tables and views sort by table name and the database list by database name.
- `findAll()` lists one row per database, naming only the database.
- `save` stamps the table type and `saveView` the view type, whatever type the entity carries. A row without a type
  (from before the discriminator) can only be written to the database directly.

**Still served for tests only** (the real repository refuses them):

- `existsById`
- `deleteAll()`, which also empties the soft-deleted store
- `softDeletedTables()`

### One store per application context

- The soft-deleted store and the write lock belong to each application context. Two services in one JVM share
  neither.
  - Removed: the static `HouseTablesH2Repository.softDeletedTables` and `SAVE_LOCK`.
  - Use `houseTablesH2Repository.softDeletedTables()`, the instance's live map, to seed or inspect soft-deleted
    rows.
- The database is a `DataSource` bean, `HouseTablesH2Configuration`, which `SpringH2TestApplication` imports. It
  names an H2 database of its own per context, with `OPTIMIZE_REUSE_RESULTS=FALSE`.
  - `spring.datasource.*` properties no longer select the database, from any source. A `DataSource` bean of your
    own now conflicts with this one.
  - The resource `tables-test-fixtures-h2.properties` is removed.
- Reads never come from a caller's persistence context. A test no longer needs `EntityManager.clear()` to see
  another request's write.

### Removed members of `HouseTablesH2Repository`

| Removed | Use instead |
|---|---|
| `softDeletedTables` (static field) | `softDeletedTables()` |
| `SAVE_LOCK`, `SOFT_DELETE_RETENTION_MS` | nothing |
| `findStoredLocation`, `findByDatabaseIdIgnoreCaseAndTableIdIgnoreCase`, `findByDatabaseIdAndTableId`, `findByDatabaseId(..)`, `findByDatabaseIdIgnoreCase(..)` | `findEntityById`, `findAllByDatabaseId` |
| `saveAndFlush` | `save` or `saveView` |
| the static helpers (`stored`, `pageOf`, `hydrateEntityType`, `isTableOrLegacy`, `isView`) | nothing |

### Added

- `HouseTablesH2Store` and `HouseTablesH2StoreImpl`, the implementation Spring Data composes into
  `HouseTablesH2Repository`.
- `HouseTablesH2Configuration`.
- `HouseTableRepositoryContract`, an abstract JUnit 5 contract that any `HouseTableRepository` can be run against.

**Where the stand-in still differs from the real store:** the class comment of `HouseTablesH2StoreImpl` lists
every case.
