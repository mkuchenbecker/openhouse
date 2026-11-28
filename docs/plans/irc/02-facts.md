# Verified facts

Bottom line: both forks are behind LinkedIn, LinkedIn has no Iceberg line that carries v3 plus its own
patches, Apache Iceberg 1.12.0 already ships Spark 3.5 and 4.2, and Iceberg's `RESTCatalog` already accepts
a caller-supplied client. Every line below was checked on 2026-11-21/22 against the refs named. These are
observations, not decisions.

## Fork drift

- F1. `mkuchenbecker/openhouse` main is 117 commits behind `linkedin/openhouse` main (`bb8929e`) and 12
  ahead. The 12 are the optimizer integration commits, the delta-harness, and a build parallelism change.
- F2. `mkuchenbecker/iceberg` `openhouse-1.2.0` and `openhouse-1.5.2` equal LinkedIn's as of 2026-11-22.
  Before the sync they were 6 and 3 commits behind, with nothing fork-only.

- F2a. The openhouse fork's main is guarded by a repository rule that refuses forced updates and direct
  pushes. Main is untouched. Its old state is also parked on `archive/fork-main-before-1to1-sync`.
- F2b. Branch `irc-integration` on the openhouse fork equals LinkedIn main (`bb8929e1`) as of 2026-11-22.

## Iceberg lines

| Line | Base | Format ceiling | IRC paths | Spark | LinkedIn patches |
| --- | --- | --- | --- | --- | --- |
| LinkedIn `openhouse-1.2.0` | Apache 1.2.0 | 2 | 9 | to 3.3 | 45 commits |
| LinkedIn `openhouse-1.5.2` | Apache 1.5.2 | 2 | 14 | 3.3-3.5 | 29 commits |
| LinkedIn `openhouse-master` | Apache main, Aug 2025 | 4 | 18 | 3.4-4.0 | none found |
| Fork `1.11.x` | Apache 1.11.0 | 4 | 20 | 3.4-4.1 | 2 commits, 12 files |
| Fork `spark-4.2` | Apache 1.11.0 | 4 | 20 | to 4.2 (WIP port) | +3 commits, 641 files |
| Apache `1.12.0` (2026-09-25) | n/a | 4 | 23 | 3.5, 4.0, 4.1, 4.2 | none |

- F3. The 29 LinkedIn commits on `openhouse-1.5.2` split as: build/release 6, core+api 9, ORC 5,
  Spark 3.5 9. The fork's `1.11.x` replayed a subset of core only. No Spark patch was replayed.
- F4. Apache 1.11 and 1.12 require JDK 17 or 21 to build and emit Java 17 bytecode.
- F4a. The untouched `openhouse-1.12.0` branch was built on this machine on 2026-11-22 with JDK 21. The full unit
  suites of api, common, core (9075 tests), data, orc and parquet passed with zero failures. A six-class subset
  passed on each of Spark 3.5 and Spark 4.2 (Scala 2.13). The full Spark suites and the REST compatibility kit
  tests were not run.

## Engine side today (LinkedIn main)

- F5. The plugin is `OpenHouseCatalog`. It extends `BaseMetastoreCatalog` on the 1.2 line and
  `BaseMetastoreViewCatalog` on the 1.5 line, and implements `SupportsGrantRevoke` and `SupportsUnlock`.
  The Spark classes are 31-line subclasses that only declare capabilities.
- F6. It talks to the service through a client generated from the service's OpenAPI. On load it fetches
  the metadata location and reads the metadata file itself. On commit it sends schema, properties,
  policies and a serialized snapshot list.
- F7. Namespace create, drop and property changes are unsupported in the plugin. Purge on drop is ignored.
  View rename is unsupported.
- F8. Policy statements do not call a policy endpoint. They commit a table property
  (`updated.openhouse.policy`) and detect OpenHouse tables by the `openhouse.tableId` property.
- F9. Grant, revoke, show grants and unlock find the catalog through the `SupportsGrantRevoke` and
  `SupportsUnlock` interfaces. Nothing in the SQL extensions casts to `OpenHouseCatalog`. They do reach
  into Iceberg's `SparkCatalog` private fields by reflection.
- F10. Plugin calls with no IRC counterpart: table and database ACL get/set, unlock, and the snapshot
  commit body. The service also has soft-delete listing, purge and restore, which the plugin never calls.
- F11. Nothing in LinkedIn main references `RESTCatalog`, `RESTClient`, `CatalogHandlers` or `/v1/config`.

## Capabilities today (LinkedIn main)

- F12. A capability mechanism exists. The client sends `X-OpenHouse-Client-Capabilities`. A table demands
  one through a `required-capability.<name>` property. The server enforces on load and on commit and
  answers 422 when the client lacks it.
- F13. Exactly one capability is defined: `column-defaults`. Views are gated separately by a client
  property, not by this mechanism.

## Iceberg REST building blocks

- F14. `RESTCatalog` has a constructor taking a function that builds the `RESTClient`, on both 1.5.2 and
  1.11. `RESTClient` has five abstract methods (head, get, post, postForm, delete).
- F15. `CatalogHandlers`, which turns IRC requests into calls on any Iceberg `Catalog`, is in main sources
  on both lines. The dispatcher that routes paths to it (`RESTCatalogAdapter`) is in test sources only.
- F16. The server's advertised endpoint list (`ConfigResponse.endpoints`) exists on 1.11 and not on 1.5.2.
- F17. Apache 1.11+ ships a REST compatibility kit as test fixtures (`open-api/src/testFixtures`).

## Tests and CI today (LinkedIn main)

- F18. CI runs on JDK 17: `./gradlew clean build`, then the `oh-only` docker recipe and a Python
  integration script. Modules compile to Java 8 bytecode. Lombok is 1.18.20, Spring Boot 2.7.8.
- F19. Two Spark SQL harnesses exist. One mocks the service with a mock web server (21 classes). The other
  starts the real service embedded with an H2-backed store (`OpenHouseSparkITest`; 12 Spark 3.1, 8 Spark
  3.5 and 15 app test classes).
- F20. The tables service has 136 test files. The internal catalog has 26.
- F21. Publishing happens only from `linkedin/openhouse` main. The build resolves Iceberg from the
  `com.linkedin.iceberg` group on Maven Central.

## Not verified

- The exact Apache commit under LinkedIn's `openhouse-master`, and whether it truly has zero LinkedIn
  commits beyond the 400 inspected.
- What `credentials` and `sign` should return for an HDFS-backed deployment.
- How v3 replication works, since no replication job lives in this repo.
- How the house table store could commit several tables atomically.
