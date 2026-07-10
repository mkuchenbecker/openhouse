# delta-harness — findings

Real issues surfaced by running the harness against the OpenHouse catalog. These are the
harness doing its job: distinguishing environment/packaging problems from test failures.

## F1 — Avro data path: shaded-Avro `ClassCastException` (packaging prerequisite)

**Symptom.** Any test that writes/reads **Avro** data files (`write.format.default=avro`) fails:

```
java.lang.ClassCastException: class org.apache.avro.Schema$RecordSchema cannot be cast to
class org.apache.iceberg.shaded.org.apache.avro.Schema
```

`CREATE TABLE ... 'write.format.default'='avro'` succeeds (metadata only); the failure is on
the first data operation (INSERT/SELECT).

**Diagnosis.** The OpenHouse Spark runtime uber jar shades Avro to
`org.apache.iceberg.shaded.org.apache.avro`, while Spark's Avro data source uses unshaded
`org.apache.avro`. On the Avro data path the two meet and the cast fails. Parquet and ORC are
unaffected (verified green). This is an **OpenHouse runtime packaging** issue, not a defect in
the tests or the catalog semantics. Per repo maintainer, this is likely triggered by a recent
Avro version bump (the root build forces `org.apache.avro:avro:1.11.4` for CVE-2024-47561)
diverging from the Avro version Iceberg's shaded runtime expects.

**Harness treatment.** The `fileFormat=avro` slice is **disabled** (doc 08) with this reason,
so it shows as a visible `SKIP` rather than a red `ERROR` or a silent drop. Note the
classifier correctly filed the raw failure as `ERROR` (unclassified/terminal), *not* `FAIL` —
the infra-vs-failure firewall held.

**Experiment — is it the Avro version bump?** Swapping the unshaded Avro jar on the run
classpath from **1.11.4 → 1.11.2** (the version bumped in commit `ffc5517`) made **no
difference**: the `ClassCastException` is byte-for-byte identical, all 36 avro cases still fail.
So the Avro *version force* is not the cause. Commit `ffc5517` also bumped the **linkedin/iceberg
fork** (`1.5.2.11 → 1.5.2.14`), and iceberg's shaded Avro (`org.apache.iceberg.shaded.org.apache.avro`)
travels *inside that fork jar*. The collision is between that shaded copy and the unshaded
`org.apache.avro` on the classpath — a shading/relocation mismatch, independent of the Avro jar
version. The still-untested half of the hypothesis is reverting the **iceberg-fork** version,
which requires a full rebuild against `1.5.2.11`.

**To re-enable Avro.** Align the shaded Avro in the iceberg-fork runtime with the unshaded Avro
reaching Spark's Avro data source (un-shade in the runtime, or keep only one Avro on the run
classpath). Once data ops work, remove the `avro` entry from the disable list in
`OpenHouseMatrix.scala`.

## Verified green (JDK 17, embedded OpenHouse server, `openhouse.dbMatrix`)
- **Parquet, ORC** — CREATE (schema), READ (projection + filter), format-materialization
  (data files carry the right extension), and all four DELETE behaviors.
- **Firewall self-tests** — transient `IOException` heals via retry (never a `FAIL`);
  deliberately-wrong expectation surfaces as `FAIL`.
- 26/26 checks verified.
