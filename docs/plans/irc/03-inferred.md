# What Claude inferred

Bottom line: everything in this file is Claude's judgement and none of it is agreed. Each item cites the
statements (`S`, in `01-stated.md`) and facts (`F`, in `02-facts.md`) it rests on. The three questions at the end were answered
on 2026-11-22. The work list in `04-work.md` is built on these judgements, so a rejected judgement
changes that list.

## The seam

- J1. The REST-shaped seam (S18, S19) is Iceberg's `RESTCatalog` running over a client we supply. That
  client dispatches each request to Iceberg's `CatalogHandlers`, which calls today's `OpenHouseCatalog`
  unchanged (F14, F15). A config value picks this path or today's path. Today's path stays the default.
- J2. The seam needs no service change and no Iceberg upgrade. It works on Spark 3.5 with Iceberg 1.5.2.
- J3. The legacy side of the seam cannot carry v3 content, because its commits still travel as the
  snapshot list (F6). Every v3 function therefore exists only on the native path.
- J4. "Catalog plugin in the Iceberg fork" (S6) means Iceberg's `Catalog` interface, with `RESTCatalog` as
  the implementation. No OpenHouse-specific plugin exists in the fork. Mike has not confirmed this reading.

## Capabilities

- J5. OpenHouse-only features become named capabilities (S16): grants, unlock, policies, column
  defaults, soft delete. The mechanism that exists today (F12) is the starting point. It has one
  capability (F13).
- J6. They compose with REST (S21) in two directions. The server advertises what it offers through the
  IRC config response, using the endpoint list where the Iceberg line has one (F16). The client declares
  what it understands through the existing header, which `RESTCatalog` can already send.
- J7. The SQL extensions already look features up by interface and by table property (F8, F9). Moving
  them to capabilities is a small change, not a rewrite.

## Iceberg line

- J8. Decided by Mike (S36): the clean base is Apache 1.12.0. It ships Spark 3.5 and 4.2 itself, so the
  641-file Spark 4.2 port on the fork is not needed (facts table). The fork's `1.11.x` and `spark-4.2`
  become reference only.
- J9. LinkedIn's patches are reapplied one commit each, each with a test written first. A patch already
  present in the Apache base is recorded as such and not reapplied (F3).
- J10. LinkedIn has no line to sync to for this. `openhouse-master` is a stale Apache snapshot with no
  LinkedIn patches (facts table). The 1:1 requirement (S13) is met by the two release branches and main.

## Service

- J11. IRC endpoints for tables and views do not depend on the Iceberg upgrade. Iceberg 1.5.2 already
  has the request and response classes (F15). Scan planning, credentials, signing, view registration and
  every v3 function do depend on it.
- J12. That keeps the hardest item, moving the service to the new line, off the path to the first
  working IRC. The price is serving an older revision of the spec first.
- J13. The service's commit path has to accept a complete candidate metadata, with rules and persistence
  separable from the native request decoding. Otherwise IRC commits cannot reuse today's rules (S9).
- J14. Moving the service to the new line forces JDK 17 on service modules (F4), while the Spark 3.1 and
  3.5 client modules stay on Java 8 bytecode (F18).

## Tests

- J15. "Spark SQL behavior" (S10) is defined by a small smoke suite on the existing embedded-service
  harness (F19). It asserts through SQL only and takes the catalog path as a parameter (S25).
- J16. The delta-harness is landed as it is, runnable on demand, and does not gate CI (S24). It is the
  deep check before any default is flipped.
- J17. Black-box tests before change (S26) are needed for three modules: the catalog plugin, the service
  commit path, and the SQL extensions. Existing tests count where they are already black box (F20).
- J18. Any difference the smoke suite shows between paths is written down as a priced decision for Mike.
  It is neither silently fixed nor silently accepted (S9).
- J27. Delegated agents do all code, test, git and note changes (S40). Claude writes the briefs, reads the reports and decides what is done.

## Platforms

- J19. The Spark 4.2 runtime is IRC only: native path, capabilities and SQL extensions. The legacy client
  is never ported to it (S23).
- J20. v3 functions on Spark 3.5 need a Spark 3.5 runtime built on the new line. That artifact is Java 17
  bytecode (F4).

## v3

- J21. "v3" (S5) breaks into: metadata round trip and upgrade, deletion vectors, row lineage, native
  default values, new types, maintenance jobs, replication.

## After the start

- J22. After the 1:1 start (S13) the fork diverges on purpose, and LinkedIn main is merged in regularly.
- J23. The 12 fork-only commits (F1) are parked on an archive branch. The delta-harness comes back as its
  own item. Whether the optimizer commits are still needed is not verified.

## Not yet statable as work

- J24. Three in-scope items (S4) need a short investigation before they can be stated: multi-table
  commit against the house table store, credentials and signing on HDFS, and v3 replication.

## Questions for Mike, answered

- Q1. Reset fork main: yes, and rewrite history (S34). The rule on main refused it (F2a). See J26.
- Q2. Artifact source: the mkuchenbecker Iceberg fork (S35). Mike did not name the mechanism.
- Q3. Clean base: Apache 1.12 (S36).

## New judgement from the answers

- J25. The mechanism for S35 is JitPack building the fork's release branch, with the openhouse build
  mapping the Iceberg coordinates to it. For local work, a composite build against a checkout of the
  fork. Publishing under LinkedIn's Maven group is not possible from the fork (F21).
- J26. Settled by Mike (S38, S39): work lands on a branch in the mkuchenbecker forks, and main is not
  part of this. The branches are `irc-integration` on the openhouse fork (F2b) and `openhouse-1.12.0` on
  the Iceberg fork. Choosing those two names was Claude's call.
