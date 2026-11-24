# Waiting on Mike

Bottom line: nothing blocks work. These are the decisions that are Mike's, most of them one-way doors that matter
before any release outside the forks. Moved out of `18-state.md` to keep it under 150 lines. Dated 2026-11-23. Only
`01-stated.md` carries Mike's authority.

- D32: what "conforming Iceberg REST Catalog server" means. The compatibility kit's 96 failures, by cause
  (`60-v11-v12-investigation-result.md`): 33 are the namespace model (a namespace cannot exist on its own, and
  create, alter and drop are not served); 44 are OpenHouse rules the kit does not expect (replace needs
  `replace.enabled` 16, view dialect policy 15, schema and partition evolution rules 13); the other 19 are small model
  differences (7), kit message-text assertions (9), view rename (2) and one kit contradiction (1). None is scan
  planning or credentials.
- Whether the server should turn server-side scan planning on for clients (V11d, D44). The route is served either way;
  turning it on changes every client's read path. Nothing waits on this.
- Credential vending needs an identity service and an access contract (V12b, D45): which storages, which service
  (an AWS STS role, an Azure identity), which privilege grants read or write, and the lifetimes. HDFS cannot be vended
  to Iceberg 1.12 clients at all. Nothing waits on this.
- The one-way doors in D12 and D24, before any release outside the forks. D34 adds one: the register and metrics
  route semantics become a public contract at release.
- D35: whether to fix the native directory-reuse defect (N5), because the fix changes native behavior. Four native
  requests can still delete a renamed table's files.
- A veto, if wanted, of the accepted Spark 4.2 differences in D37 (list in `50-n3b-spark42-smoke-result.md`). They
  become user-visible behaviour at release.
- Publishing the fork's Iceberg jars anywhere is a one-way door, and the 1.12 default makes it needed before any
  release. C13, the release workflow's order of tag and publish, belongs with it (D38 addendum).
- D40: whether to build real atomic multi-table commit. It needs a new House Tables endpoint, a regenerated client
  and a split of the commit core, and is a one-way door. No engine sends the route, so leaving it as built is a
  reasonable choice.
- Worker sessions on separate machines, because a bigger container is not available (D21;
  `20-iteration-friction.md`).
- Format v3 (D12, D42, D43): turning the v3 switch on for a real cluster, and upgrading a real table. An upgrade is
  irreversible, and LinkedIn 1.5.2 clients cannot load the table afterwards. The service now supports both behind the
  switch, which is off by default (`57-x1-format-v3-result.md`).
- D46: how a retention backup should treat deletes on a merge-on-read table. Today the backup holds rows deleted before
  retention and nothing records the deletes; on v3 no deletion vector is backed up. The author recommends rewriting the
  expiring partitions with deletes applied first, at the cost of an extra rewrite and a behavior change on v2. It touches
  an external uploader that reads the manifests. Nothing waits on this; behavior is pinned (X8, X9).
- Format v3 column types (X5, pull request 129, merged 2026-11-24): on v2 tables a column of a type only format v3 has
  answers 500 "Invalid schema for v2". A 400 would be right but changes a v2 answer. Nothing waits on this.
- Format v3 column types (X5): a default value on a `variant` or `unknown` column answers 500 on every table version, from
  Iceberg's parser. Turning it into a 400 changes v2 answers too. Nothing waits on this.
- Format v3 column types (X5, narrowed by D48): `variant` is now off by default behind
  `cluster.iceberg.format-v3.variant.enabled`, so nothing opens by turning on the v3 switch. What is left for Mike is
  whether and when to turn that property on for a real cluster, his call like the v3 switch itself. A table that takes a
  `variant` column can never drop it, cannot be read from Spark 3.5, is not compacted and cannot carry a retention
  policy until the jobs run on Spark 4 (N11). Nothing waits on this.
- D47: whether to add a view-only rename to the House Tables API so the Iceberg REST view rename route can be served.
  Without it a rename is two calls and can leave two names for one view or lose a name. The author recommends a new
  atomic House Tables endpoint, and against a non-atomic version. A new House Tables API is a one-way door. Nothing
  waits on this; the route keeps answering 406 (V21).
- Spark 4.2 jobs (N11, D50, `70-n11-jobs-spark42-scout-result.md`): four things are his: a Spark 4.2 uber jar and image
  with `jobs.yaml` entries and the jobs service's token key (N12, N13); routing `variant` tables or a cluster to those
  jobs and then the `variant` default (N14); job state and optimizer reporting without House Tables access (N15).
  Nothing waits on them: the jobs project and its tests land without them. Until they are decided a Spark 4.2 platform
  is not maintainable by Spark 4.2 jobs in a deployment.
- Item order: D29.
