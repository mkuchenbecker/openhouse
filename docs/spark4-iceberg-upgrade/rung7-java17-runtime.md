# Rung 7 — Server / metadata-writer runtime → Java 17 (keep Java-8 bytecode where consumed)

**Status: DONE — already satisfied by the 1.11 upgrade architecture; verified empirically here.**

## Goal
Run the OpenHouse server (and its metadata-writer) on a **Java 17 runtime**, while keeping
**Java-8 bytecode** for the code whose output is consumed by Java-8 readers (the metadata-writer
classes that produce Iceberg metadata read by older HDFS/Iceberg clients).

## Finding
No new work was required: the Spark-4.0 / Iceberg-1.11 upgrade already established the exact
build/runtime/bytecode split this rung asks for. This rung is therefore a **verification + audit**
of that split, with concrete proof captured below.

## The bytecode ↔ runtime split (how it works)
- **Bytecode target = Java 8.** `buildSrc/src/main/groovy/openhouse.java-minimal-conventions.gradle`
  sets `sourceCompatibility = targetCompatibility = JavaVersion.VERSION_1_8`. Every server module
  inherits it (`springboot-conventions` → `java-conventions` → `java-minimal-conventions`), so the
  emitted class files are Java-8 (major 52) and the modules **advertise** Java 8 to their consumers
  (`apiElements`/`runtimeElements` left untouched). A Java-8 jar is compatible with a Java-17
  consumer, so nothing downstream breaks, and Java-8 readers of the metadata-writer output keep
  working.
- **Resolution requests JVM 17.** The same plugin raises `TARGET_JVM_VERSION` to 17 **only** on
  resolution-only configs (`canBeResolved && !canBeConsumed`), so the compile/runtime classpaths
  accept Apache Iceberg 1.11 (whose Gradle Module Metadata declares `jvm.version=17`) while still
  accepting the older jvm-8/11 libraries.
- **Build + runtime JVM = Java 17.** CI runs on JDK 17 (`.github/workflows/build-run-tests.yml`:
  "Set up JDK 17", `java-version: '17'`; the branch-1.11 comment notes "1.11 raised its Java floor
  to 17"). No module pins a Java toolchain / `languageVersion` to 8 or 11 (grep of `buildSrc` +
  root build finds none), so the runtime JVM is simply whatever runs the build/app — Java 17.

## Empirical proof
1. **Metadata-writer emits Java-8 bytecode, built on JDK 17.** Building the metadata-writer module
   on `java-17-openjdk-amd64`:
   ```
   $ ./gradlew :iceberg:openhouse:internalcatalog:compileJava   # JDK 17
   BUILD SUCCESSFUL
   $ javap -verbose OpenHouseInternalCatalog.class        | grep 'major version'  -> major version: 52
   $ javap -verbose OpenHouseInternalTableOperations.class | grep 'major version' -> major version: 52
   ```
   Major version **52 = Java 8**, produced by a **Java 17** compiler. This is the metadata-writer
   (`OpenHouseInternalCatalog` / `OpenHouseInternalTableOperations`) whose output Java-8 readers
   consume — bytecode boundary confirmed intact.
2. **Server boots + serves on Java 17.** The embedded OpenHouse server (`OpenHouseLocalServer`,
   Spring Boot 2.7) is started inside the JDK-17 test JVM by the entire Spark-4.0 REST itest e2e
   suite (catalog CRUD, snapshots, policy DDL, GRANT/REVOKE, and the Rung-3 v3 deletion-vector
   server) — all green on the `Branch 1.11 CI` push gate. A server that could not run on Java 17
   could not serve those thousands of embedded-server requests.

## Production note
The code and build fully support a Java-17 runtime; a deployment image should use a Java-17 JRE.
The repo carries no server Dockerfile (deployment images are external), so there is nothing in-repo
pinning the server to an older JRE. Spring Boot 2.7 is supported on Java 17. The metadata-writer
bytecode stays at Java 8 so its Iceberg metadata output remains readable by Java-8 clients.

## Conclusion
Runtime = Java 17; metadata-writer bytecode = Java 8 (where consumed). Both requirements are met and
verified; no code change was needed beyond what the 1.11 upgrade already carried.
