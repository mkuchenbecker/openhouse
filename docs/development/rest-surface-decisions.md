# REST surface decisions: scan planning, credentials and auth

## Problem

OpenHouse serves 20 of the Iceberg REST specification's 32 operations, and declines a 21st,
`registerTable`, with a written reason. Seven are neither served nor declined:

| Operation | Route |
|---|---|
| `planTableScan` | `POST /v1/{prefix}/namespaces/{namespace}/tables/{table}/plan` |
| `fetchPlanningResult` | `GET .../tables/{table}/plan/{plan-id}` |
| `cancelPlanning` | `DELETE .../tables/{table}/plan/{plan-id}` |
| `fetchScanTasks` | `POST .../tables/{table}/tasks` |
| `loadCredentials` | `GET .../tables/{table}/credentials` |
| `getToken` | `POST /v1/oauth/tokens` |
| `signRequest` | `POST .../tables/{table}/sign` |

(Paths in `spec/iceberg-rest-catalog-open-api.yaml` at `:196`, `:648`, `:737`, `:855`, `:1247`,
`:1293`.) The programme's rule is that every operation is either implemented or carries a recorded
decision not to serve it. **This document declines all seven, in two decisions: scan planning
because it is data-path work a control plane should not take on, and the credential and auth
operations because vending would make OpenHouse hold storage authority it does not have today.** It
changes no code.

## Requirements

**Must**

1. Every claim about current behaviour is cited to a file and line, or marked unverified.
2. A stock Apache Iceberg 1.11 client must keep working against OpenHouse under whatever is
   decided, without client-side configuration.
3. Nothing decided here may widen what OpenHouse holds on a user's behalf without saying so.
4. Each decline must be expressible durably — a mechanism that keeps working as the suite and the
   client version move — not as a test annotation someone must remember to retire.

Requirements 2, 3 and 5 are the columns of the options tables below; 1 and 4 do not discriminate
between options, so they are checked once rather than tabulated.

**Should**

5. Prefer a decline that the client can discover from `/v1/config` over one it discovers from a
   failed request.

**Won't**

6. No implementation, no `@Disabled` change, no capability-flag change; where this document
   concludes one should move, it says so and leaves the change to a slice with tests.
7. No view, transaction or multi-table-commit operations. They are outside these two decisions and
   remain undecided.

## Decision 1: server-side scan planning

**Recommendation: OpenHouse declines all four scan-planning operations and continues not to
advertise them, because planning requires the catalog to read every manifest of every scanned table,
which turns a control plane into a data-path service for no client that needs it.**

| Option | Client keeps working (req. 2) | New state OpenHouse holds (req. 3) | Discoverable decline (req. 5) | Cost |
|---|---|---|---|---|
| **A. Decline all four (recommended)** | Yes — client-side planning is the client default | None | Yes, by omission from `endpoints` | None |
| B. Synchronous-only `planTableScan` (runner-up) | Yes | None persisted, but manifest I/O per scan | Yes | Manifest read + filter per scan, on the tables service |
| C. Full async lifecycle | Yes | Plan id → task list, with a TTL and a cancel path | Yes | B, plus a plan store and its expiry |

The deciding criterion is requirement 3. The spec's lifecycle is: `planTableScan` returns either a
completed result or `submitted` with a `plan-id`; the client polls `fetchPlanningResult`, fetches
each plan task through `fetchScanTasks`, and calls `cancelPlanning` when it abandons the plan
(`spec/iceberg-rest-catalog-open-api.yaml:690`). To answer any of that the server must open the
table's manifest list, read its manifest files, apply the residual filter, and return file-scan
tasks naming data files and their delete files — the whole metadata phase of a scan, moved from the
engine into the tables service.

OpenHouse is not positioned for it. The tables service reads exactly one object per table from
storage: it resolves a `FileIO` and reads `metadata.json` through it
(`iceberg/openhouse/internalcatalog/.../OpenHouseInternalCatalog.java:352-354`, resolver `:357-379`).
It never opens a manifest — the one place it touches a manifest reference, `TableUUIDGenerator`,
parses the `manifest-list` **path string** out of the committed snapshot JSON to recover the table
UUID and never reads the file (`.../tables/utils/TableUUIDGenerator.java:216-231`). Manifests are
unbounded in the table's file count, and the engines already read them: OpenHouse hands a client a
table location and nothing else, and the client reads it with its own `FileIO`
(`integrations/java/iceberg-1.5/openhouse-java-runtime/src/iceberg-version-specific/java/com/linkedin/openhouse/javaclient/OpenHouseCatalog.java:192-196`).
Serving plans would move that work onto hardware sized for control-plane traffic, for data the
engine can already reach.

### Consequence of declining

Nothing stops working, and a stock 1.11 client falls back cleanly because there is nothing to fall
back from: server-side planning is opt-in and off by default. `RESTCatalogProperties.SCAN_PLANNING_MODE`
is `scan-planning-mode` and `SCAN_PLANNING_MODE_DEFAULT` is `ScanPlanningMode.CLIENT` (Appendix B),
so a `RESTCatalog` not told otherwise builds a plain `RESTTable` and plans client-side against the
metadata it loaded — the path every OpenHouse client uses today.

The one failure mode is not silent. If a client sets `scan-planning-mode=server`, `RESTSessionCatalog`
asserts the server advertises `V1_SUBMIT_TABLE_SCAN_PLAN` and otherwise throws `IllegalStateException:
Server requires server-side scan planning for table %s but does not support endpoint %s`. That check
reads `/v1/config`, so the message is accurate and arrives at table load, not mid-scan. OpenHouse
advertises 14 endpoints, none of them these four
(`services/tables/src/test/.../e2e/h2/IcebergRestCatalogRoundTripTest.java:158-186`). Declining is
spec-conforming: the endpoint-advertisement mechanism exists so a server can serve a subset,
`Endpoint.check` is the client half of that contract, and scan planning is not in the default set a
client assumes.

### Suite flag

There is none, and none is needed. `CatalogTests` in the Iceberg 1.11 test-jar declares eight
capability flags — `supportsNamespaceProperties`, `supportsNestedNamespaces`, `requiresNamespaceCreate`,
`supportsServerSideRetry`, `overridesRequestedLocation`, `supportsNamesWithSlashes`,
`supportsNamesWithDot`, `supportsEmptyNamespace` — and no method in the class mentions planning
(Appendix B). The suite never exercises these operations: no test to disable, no flag to set. The
decline's durable expression is instead the omission from
`IcebergRestOpenHouseSupport.SUPPORTED_ENDPOINTS`, generated from `x-openhouse-support` in the spec:
an operation without that annotation cannot be advertised, and one with it fails compilation until
implemented. That mechanism is in place already and outlives any annotation.

### What would flip this

- **A client population that cannot plan for itself.** If OpenHouse must serve an engine with a read
  path to the catalog but not to storage, planning is the only way it can scan and the choice
  becomes B or nothing. This is the same condition that flips Decision 2; decide it once for both.
- **Planning cost a catalog can amortize and an engine cannot.** If a workload re-plans the same
  snapshot with the same filter often enough that a server-side cache is materially cheaper than
  each engine's own, option B (synchronous, no plan store) is worth measuring. B is the runner-up
  rather than C because the async lifecycle only earns its state when one plan exceeds a request
  timeout, and nothing suggests one does — unverified: no planning latency has been measured
  against an OpenHouse table.

## Decision 2: credentials and auth

**Recommendation: OpenHouse declines `loadCredentials`, `getToken` and `signRequest` and continues
to vend no storage credential, because vending would require the tables service to hold a storage
principal with authority over every table's prefix — authority it does not have today and would have
to acquire to give away.**

| Option | Client keeps working (req. 2) | New state OpenHouse holds (req. 3) | Security posture |
|---|---|---|---|
| **A. Decline all three (recommended)** | Yes — engine credentials, as today | None | Unchanged: storage access governed by the engine's own identity |
| B. `loadCredentials` + access delegation (runner-up) | Yes | A credential-minting storage principal, cluster-wide | Catalog becomes the storage gate; also a new blast radius |
| C. `signRequest` | Yes | Signing key, plus the whole object request stream | Same as B, and OpenHouse is on the data path per object |

`getToken` is not a real option: the spec marks it `deprecated: true` and "DEPRECATED for REMOVAL",
tells servers not to implement it, and schedules its removal for Iceberg 2.0
(`spec/iceberg-rest-catalog-open-api.yaml:203-215`). Declining it is the spec's own recommendation,
and it is grouped here only so that its silence ends too.

### How a client gets storage credentials today

It does not get them from OpenHouse. Verified:

- The facade vends nothing. `getConfig` returns the `prefix` map, one empty map and the endpoint
  list — no storage properties, no `io-impl`, no credential
  (`.../tables/api/handler/impl/OpenHouseIcebergRestApiHandler.java:60-65`).
- Access delegation is accepted and ignored. `X-Iceberg-Access-Delegation` is bound and passed
  through by the controller (`.../tables/controller/IcebergRestCatalogController.java:65`, `:85`),
  but the handler never reads it: it appears once as the `loadTable` parameter
  (`.../OpenHouseIcebergRestApiHandler.java:97`, body `:93-111`) and once as the `createTable`
  parameter (`:122`, body `:122-129`), and nowhere else.
- The string `credential` occurs nowhere in the facade or the tables service, other than Spring's
  `AuthenticationCredentialsNotFoundException` (`.../tables/authorization/AuthorizationInterceptor.java:11`).
- The client builds its own `FileIO` from its own Hadoop `Configuration`, defaulting to
  `HadoopFileIO` (`.../javaclient/OpenHouseCatalog.java:192-196`). A stock `RESTCatalog` does the
  same from its own properties, since OpenHouse supplies no overrides.
- Storage configuration is cluster-level and server-side — a root path, an endpoint and a parameter
  map per storage type, with no per-principal or per-table credential in it
  (`cluster/storage/src/main/java/com/linkedin/openhouse/cluster/storage/configs/StorageProperties.java:30-44`).
- The one credential OpenHouse refreshes is its own: `HdfsDelegationTokenRefresher` reads a token
  file into the server process's `UserGroupInformation`, never into a response
  (`cluster/storage/.../hdfs/HdfsDelegationTokenRefresher.java:61-77`).
- OpenHouse consumes a bearer token and does not issue one. The only interceptor in the tree parses
  a JWT from the `Authorization` header and is documented as for unit tests and local docker
  (`services/common/.../security/DummyTokenInterceptor.java:25-27`, `:54-56`).

So the engine authenticates to storage as itself, and OpenHouse tells it only where the table is — a
location OpenHouse's storage selector allocated at create time
(`services/tables/src/main/java/com/linkedin/openhouse/tables/repository/impl/OpenHouseInternalRepositoryImpl.java:137-145`).

### Does the write path change this

No; it raises the stakes and leaves the mechanism identical. A REST write client writes its data
files itself, to the location OpenHouse allocated, using the same `FileIO` it reads with, then
commits metadata through `updateTable`. The credential it needs is a write credential rather than a
read one, obtained the same way — from its own environment. The write adapter neither reads nor
returns one (`.../handler/impl/IcebergRestTableWriteAdapter.java`, whole file: no `credential`).

### The security consequence, both ways

Vending is not neutral. To answer `loadCredentials`, OpenHouse must hold a storage principal that
can mint a scoped credential for any table's prefix — write authority over every table in the
cluster, in one process. (The one shape that avoids this is exchanging the caller's own token at an
external STS, which OpenHouse cannot do today: it holds a bearer JWT it parses but never forwards,
and no storage identity for the caller.) The gain is real — the catalog's table ACLs would become a
storage boundary. The cost is that a scoping bug hands a caller another table's prefix, and the
tables service becomes the single credential worth attacking. `signRequest` is worse: OpenHouse
would sit on the request path for every object, scaling with file count rather than table count.

Not vending has a consequence too, and it is the honest one to record: **OpenHouse's authorization is
an API boundary, not a storage boundary.** A caller holding storage credentials can read or write a
table's files without passing through OpenHouse at all. That is true today, and declining to vend
leaves it true; this document does not create the gap, it declines to close it by this means.

### Suite flag, and what would flip this

No flag, same as Decision 1: `CatalogTests` has no credential flag and no credential test (Appendix
B), so there is nothing to disable; the decline is again expressed by omission from the advertised
endpoint set, which the 1.11 client honours through `Endpoint.check`. What flips the decision is a
deployment in which the engine has no storage identity of its own — where the operator intends
catalog-mediated access, so a table's ACL in OpenHouse is what decides whether its files can be
read. On object storage with per-table prefixes that is coherent, and it is the only case in which
vending buys something bucket policy cannot. Then the answer flips to B (`loadCredentials` plus
honouring `X-Iceberg-Access-Delegation` with `vended-credentials`) for S3 and ADLS, and stays A for
HDFS, where delegation tokens already carry the engine's identity. It does not flip to C: remote
signing costs the same authority as B and adds a data-path dependency. A weaker second condition: if
data-file writes ever move into the tables service, the client needs no storage credential at all
and the decision is moot rather than flipped.

## Appendix A: review

One pass, `writing-review` (with `DESIGN-DOCS.md`) and `arch-review`, against the humanizer
structure rules and sentence catalog fetched at review time.

| Raised | Reviewer | Disposition |
|---|---|---|
| Two recommendations, neither visible before line 49; a reader triaging the document finds only the problem | writing (rule 1, and DESIGN-DOCS "recommendation buried") | Fixed: both stated in the Problem section's closing sentence |
| Requirements 1 and 4 are not columns in either options table | writing (DESIGN-DOCS "criteria columns don't match requirements") | Fixed: stated that they do not discriminate, so they are checked once rather than tabulated |
| "The tables service does not read manifests" asserted without a citation | arch | Fixed: verified and cited (`TableUUIDGenerator:216-231` reads the manifest-list path, never the file) |
| "OpenHouse must hold a credential-minting principal" overstates — a token exchange at an external STS is a third shape | arch | Fixed: named, with why it is not available today |
| Heavy em-dash use (pattern 14) and bolded recommendation paragraphs (pattern 16) | writing | Accepted as conforming: house style matching `docs/iceberg-rest-catalog.md`, and DESIGN-DOCS requires the recommendation to be marked |
| `getToken` is grouped with two operations it shares no mechanism with | arch | Accepted: the grouping is the decision's scope (auth-adjacent silence), and the text says so |
| Whether option B should be split per storage type | arch | Tabled: it is a property of the flip condition, which already names S3 and ADLS separately |

## Appendix B: facts established outside the tree

Three claims above are about the Apache Iceberg 1.11 client, not OpenHouse. They were verified by
disassembling the jars this repository's compatibility module already depends on
(`tests/iceberg-rest-catalog-compat/build.gradle`), not by reading source:

1. `javap -constants ...rest.RESTCatalogProperties` gives `SCAN_PLANNING_MODE =
   "scan-planning-mode"`; `javap -c` on its static initializer shows `SCAN_PLANNING_MODE_DEFAULT`
   assigned `ScanPlanningMode.CLIENT`.
2. `javap -c ...rest.RESTSessionCatalog` shows the branch: when the resolved mode is `SERVER`, a
   `Preconditions.checkState` on `endpoints.contains(V1_SUBMIT_TABLE_SCAN_PLAN)` throws "Server
   requires server-side scan planning for table %s but does not support endpoint %s"; on a
   client/server mode mismatch it logs a warning and the server's value wins.
3. `javap ...catalog.CatalogTests` (the 1.11 tests jar) lists 8 `protected boolean` capability flags
   and 91 test methods, none of whose names contains `plan` or `credential`.

Two things are unverified. No scan-planning latency has been measured against an OpenHouse table, so
the claim that option C's async lifecycle is unnecessary rests on absence of evidence that a plan
exceeds a request timeout, not on a measurement. And whether a given deployment's storage ACLs
independently restrict engine access is a property of that deployment, not of this tree; what is
verified is only that OpenHouse hands the client no credential and the client's `FileIO` is built
from its own configuration.
