# Iceberg 1.12.0 baseline (unmodified, 212aa894e), JDK 21, Gradle 8.14.5

**Bottom line:** On the unmodified checkout, everything I ran compiled and passed with zero failures and zero errors. That covers the full unit suites of api, common, core, data, orc and parquet, and a 6-class subset on each of Spark 3.5 and Spark 4.2, both on Scala 2.13. The only problem was transient: Maven Central returned HTTP 429 through the proxy during dependency resolution, so the first compile attempts failed until the cache was warm. A retry fixed it with no TLS or proxy changes. `git status` is clean afterwards: no tracked file was modified, and nothing was committed, pushed or branched.

Common flags on every Gradle call: `--no-daemon --continue --no-parallel --max-workers=2 -Dorg.gradle.jvmargs=-Xmx4g -DsparkVersions=3.5,4.2 -DscalaVersion=2.13 -DflinkVersions= -DkafkaVersions=` (abbreviated `$F` below). No init script or GRADLE_USER_HOME override; the default `~/.gradle` is outside the checkout. The `-DscalaVersion=2.13` flag was needed because Spark 4.x only exists for Scala 2.13 (settings.gradle) and the default is 2.12; Spark 3.5 supports 2.12 and 2.13, and I built only 3.5_2.13. `-DsparkVersions` is what puts 3.5 and 4.2 in the build; it overrides the default of 4.2. `--max-workers=2 --no-parallel` was added after the 429s.

| Step | Command (`$F` = flags above) | Result | Wall |
|---|---|---|---|
| 1 compile | `./gradlew $F` + `compileJava compileTestJava` on :iceberg-api, -core, -common, -data, -orc, -parquet, -bundled-guava, -open-api (plus `:iceberg-open-api:compileTestFixturesJava`), and :iceberg-spark:iceberg-spark-{3.5,4.2}_2.13 and :iceberg-spark:iceberg-spark-extensions-{3.5,4.2}_2.13 | BUILD SUCCESSFUL on 3rd retry (the 2 earlier loop tries and 2 earlier attempts failed on 429, see Failures) | 127 s (successful run) |
| 2 api | `./gradlew $F :iceberg-api:test` | 1287 run, 0 failed, 1 skipped | 19 s |
| 2 common | `... :iceberg-common:test` | 5 run, 0 failed, 0 skipped | 11 s |
| 2 orc | `... :iceberg-orc:test` | 66 run, 0 failed, 0 skipped | 17 s |
| 2 parquet | `... :iceberg-parquet:test` | 756 run, 0 failed, 9 skipped | 36 s |
| 2 data | `... :iceberg-data:test` | 805 run, 0 failed, 85 skipped | 77 s |
| 2 core | `... :iceberg-core:test` | 9075 run, 0 failed, 550 skipped | 841 s |
| 3 spark 3.5 | `... :iceberg-spark:iceberg-spark-3.5_2.13:test --tests <4 classes>` | 156 run, 0 failed, 9 skipped | 119 s |
| 3 spark-ext 3.5 | `... :iceberg-spark:iceberg-spark-extensions-3.5_2.13:test --tests <2 classes>` | 187 run, 0 failed, 24 skipped | 238 s |
| 3 spark 4.2 | same as 3.5 with `4.2_2.13` | 156 run, 0 failed, 8 skipped | 78 s |
| 3 spark-ext 4.2 | same as 3.5 with `4.2_2.13` | 187 run, 0 failed, 24 skipped | 240 s |

Test counts come from the JUnit XML in each module's `build/test-results/test`, not from the console. Skips are the tests' own `assume`/`@Disabled` conditions; I did not add any.

## Step 3: classes chosen (same set in both Spark versions)
Spark module (`org.apache.iceberg.spark...`):
- `sql.TestCreateTable` (64 tests): catalog CREATE TABLE SQL, with parameters that include a REST catalog configuration.
- `sql.TestAlterTable` (60): ALTER TABLE SQL, also with a REST catalog parameter.
- `sql.TestPartitionedWrites` (28): INSERT write path.
- `TestRestDropPurgeTable` (4): the only REST-specific test in the Spark module; it mocks RESTCatalog and does not use RESTServerExtension.

Extensions module (`org.apache.iceberg.spark.extensions...`):
- `TestMergeOnReadDelete` (147): merge-on-read DELETE.
- `TestMergeOnReadWithLineage` (40): v3 format row lineage. It was the only v3-specific class I found in the extensions module, and the class has format-version-3 parameterization.

That is 6 classes per version rather than 5 because I needed separate classes for create, alter, insert, REST and v3. Because `TestAlterTable` and `TestCreateTable` already cover REST parameters, a leaner set would be 5.

## Step 4: REST compatibility kit
It lives in `/home/user/iceberg-1.12/open-api/src/testFixtures/java/org/apache/iceberg/rest/`: `RCKUtils`, `RESTCatalogServer`, `RESTServerCatalogAdapter` and `RESTServerExtension`. `:iceberg-open-api:compileTestFixturesJava` succeeded in step 1, and all 4 classes are present (plus a nested `RESTCatalogServer$CatalogContext`) in `open-api/build/classes/java/testFixtures/org/apache/iceberg/rest/`. I did not run the RCK tests in `open-api/src/test` (see Not run).

## Failures
No test failures. Only build-time failures, all the same transient cause:
- Attempts 1 and 2 (`$F` without and with `--no-parallel --max-workers=2`) and tries 1 and 2 of the retry loop failed in dependency resolution, for example: `Execution failed for task ':iceberg-bundled-guava:compileJava'. > Could not resolve com.google.guava:guava:33.4.0-jre ... Could not GET 'https://repo.maven.apache.org/maven2/org/antlr/ST4/4.3.1/ST4-4.3.1.pom'. Received status code 429 from server: Too Many Requests`. The second attempt showed the same for `parquet-hadoop-1.17.1.pom`.
- A plain `curl` of those same URLs through the proxy returned 200. The README describes 429 as the proxy rejecting too many open connections. Gradle caches what it downloads, so each retry progressed until the third succeeded.
- Later steps (2 and 3) needed no retries.

## Not run
- Flink, Kafka Connect, Spark 4.0/4.1, and the Spark 3.5 Scala 2.12 variants: not requested or not needed. `-DflinkVersions= -DkafkaVersions=` were empty.
- Full Spark 3.5 and 4.2 suites, including `spark-runtime` modules: the task said a bounded subset only.
- `:iceberg-bundled-guava:test` and `:iceberg-open-api:test`: only compiled, per the task. The open-api compile covers main, test and testFixtures; no RCK conformance tests were executed.
- Other modules (aws, gcp, azure, hive-metastore, nessie, arrow, mr and the rest): out of scope; they were not compiled except as dependencies of the listed ones.
- Checkstyle, spotless, errorprone-specific tasks and javadoc: not run separately; only the compile tasks above.

Logs: `/tmp/claude-0/-home-user/eca9c5c3-f1f6-52c6-b03c-ae0dfb4c364a/scratchpad/` (`step1-try*.log`, `step1-attempt*.log`, `step2-*.log`, `step3-*.log`).
