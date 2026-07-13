# Evaluation — embedded shim vs full Docker service (for REST-only features: lock, undrop)

**Question:** to test lock / soft-delete-undrop (server-enforced, no SQL surface), do we (A) make HTTP
calls to the **embedded `OpenHouseLocalServer`** the harness already runs, or (B) stand up the **full
REST service via Docker** and target that? User's fidelity bar: *the shim is fine as long as the
feature logic is not inside the shim.* Evaluation only — nothing executed.

## Finding 1 — the embedded server runs the REAL feature logic (logic is NOT in the shim)
`OpenHouseLocalServer.start()` boots `new SpringApplication(SpringH2TestApplication.class).run()` — a
real Spring Boot + embedded Tomcat app (`tables-test-fixtures/.../OpenHouseLocalServer.java`,
`SpringH2TestApplication.java`). `SpringH2TestApplication` is a `@SpringBootApplication` whose
`@ComponentScan` pulls in the **production** packages:
- `com.linkedin.openhouse.tables.controller` → the real `TablesController` (the `/lock`,
  `/softDeletedTables`, `/restore`, `/purge` endpoints)
- `com.linkedin.openhouse.tables.services` → the real `TablesServiceImpl` (`createLock`/`deleteLock`,
  `restoreTable`, `LOCKED_TABLE_OPERATION`, grant gating)
- `com.linkedin.openhouse.tables.repository` + `internal.catalog` → the real
  `OpenHouseInternalRepositoryImpl` (skipEligibilityCheck, RTAS guard, reserved-props, spec validation)
- `com.linkedin.openhouse.common.exception.handler` → the real exception handler (the one whose
  error-body/stacktrace behavior we audited)
- `tables.authorization`, `tables.toggle`, `cluster.storage` → real beans

**Conclusion:** the lock/undrop **feature logic is the production code**, not a stub. The embedded
server is the exact target the repo's own H2 e2e tests (`TablesControllerTest`) already hit, including
lock-policy and soft-delete/restore. So per the user's bar, the shim is **high-fidelity** for these
features.

## Finding 2 — what the embedded server SUBSTITUTES (and whether any is feature logic)
| Substitution | vs production | Is it feature logic? | Impact on lock/undrop |
|---|---|---|---|
| **H2 in-memory** (auto-config) instead of **MySQL** | different SQL engine behind the same JPA repositories | No — persistence backend | None for lock/undrop logic (same validators/services run). Minor risk only for MySQL-specific SQL, irrelevant here |
| **Security auto-config EXCLUDED** (`SecurityAutoConfiguration`, `ManagementWebSecurityAutoConfiguration`) | no auth filter; requests run unauthenticated/default principal | **Partly** — the authorization *layer* | **This is the one real gap.** Privilege gating — `LOCK_ADMIN`-gated reads, non-admin GRANT rejection — cannot be faithfully exercised (explains why GRANT-when-shared didn't reject earlier). Lock *enforcement* (locked → mutation rejected) runs for real. **Undrop is a separate blocker** — the embedded soft-delete repo is a `@Primary` stub (see Addendum), so undrop does NOT run for real |
| **No-op file securer** bean (replaces `SnapshotInspector#fileSecurer` chown) | real chowns files to a group | No — a filesystem-permission side effect | None |

## Finding 3 — the full Docker stack: footprint + what it actually adds
`infra/recipes/docker-compose/oh-s3-spark/docker-compose.yml` stands up **~11 containers**:
`openhouse-tables`, `openhouse-jobs`, `openhouse-jobs-scheduler`, `openhouse-housetables`, `mysql`,
`minioS3` (+ client), **`opa`** (Open Policy Agent — real authorization), `prometheus`, and a Spark
cluster (`spark-master`, `spark-worker-a`, `spark-livy`).
- **Adds over the shim:** real MySQL (negligible for this logic), real object storage (MinIO/S3),
  the jobs scheduler (not needed — maintenance is `CALL` ops), and **real authorization via OPA +
  security** — the *only* fidelity dimension the shim lacks.
- **Cost:** several GB RAM, multi-container orchestration, minutes of setup/teardown (one-time but
  heavy), and it duplicates a Spark cluster the harness already provides in-process.

## Finding 4 — environment reality (blocking)
**Docker is not usable in this environment** (`docker info` fails — no running/authorized daemon).
RAM (15 GB) would fit, but with no Docker daemon the full-service option is **not runnable here at
all**. It would require a different, Docker-capable environment.

## Recommendation
Use the **embedded shim** (a small `java.net.http` client to the already-running
`OpenHouseLocalServer`) for **lock enforcement** (undrop turned out to be a stub in the embedded
harness — see Addendum — so it's tagged SKIP, not tested here). Rationale:
1. **Fidelity is high** — the real controllers/services/repositories run; the feature logic is not in
   the shim (meets the user's bar). It's the same target the repo's own lock/soft-delete e2e tests use.
2. The substitutions (H2, no-op file securer) don't touch lock/undrop logic.
3. **Zero extra resource cost** — the server is already running; the Spark cluster is in-process.
4. The full Docker stack is **not runnable here** and its only real fidelity gain is the
   **authorization/OPA** layer.

**Document as an explicit fidelity gap (out of scope for the embedded harness):**
authorization / privilege gating — `LOCK_ADMIN`-gated reads, non-admin GRANT/lock rejection. Faithful
coverage of that needs the full Docker stack (MySQL + OPA + security) in a Docker-capable environment.
Everything else about lock/undrop is exercisable at full fidelity via the shim.

**Net:** shim now (real logic, no cost, runnable); reserve the Docker stack for a future auth-focused
pass in an environment that can run it.

---

## Addendum — HTS-vs-H2 investigation (undrop specifically)
Sub-agent verdict: real undrop is **not achievable at fidelity in the current embedded harness**, for
two independent reasons:
1. The active `HouseTableRepository` bean is a `@Primary` **in-memory stub** (`tablestest/
   HouseTablesH2Repository`) — a HashMap reimplementation of soft-delete/restore, not the real HTS
   JPA logic. Testing undrop here tests the stub (feature logic *in* the shim).
2. The public Tables `DELETE` **hard-codes `purge=true`** (`OpenHouseInternalRepositoryImpl.deleteById`
   → `catalog.dropTable(id, true)`), so drop→soft-delete is unreachable via the customer API in **any**
   environment (Docker included). Soft-delete is HTS-admin-only (`DELETE /hts/tables?isSoftDelete=true`).

**Real fidelity IS achievable in-process (not just Docker)** via option (a): a real H2-backed HTS boot
class already exists — `services/housetables/src/test/.../SpringH2HtsApplication` — running the genuine
soft-delete JPA code (`UserTablesServiceImpl` + `SoftDeletedUserTableHtsJdbcRepository`). Wiring it
requires: (i) putting housetables (+ that boot class) on the harness classpath (no fixtures module
exists — it's a test source), (ii) booting it on a second port and setting
`cluster.housetables.base-uri=http://localhost:<htsPort>`, (iii) de-`@Primary`-ing the stub so the real
HTTP-client `HouseTableRepositoryImpl` is injected. Even then the DROP half must be driven at the HTS
layer (public DROP can't soft-delete). Substantial restructure → deferred; `control.undrop` tagged SKIP.
