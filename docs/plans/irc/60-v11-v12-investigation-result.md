# V11 and V12 investigation: result

Bottom line: scan planning (V11) is cheap and safe to start, and credential vending (V12) cannot be built properly from
what the repository has. Neither moves the compatibility kit. The first steps are decided in D44 and D45
(`56-decisions-continued-5.md`) and are work items V11a and V12a (`41-work-platforms-v3.md`). Dated 2026-11-24. The
source is a read-only investigation by a delegated agent; nothing was built or run. "[read]" marks a claim read in code,
"[inferred]" one the agent reasoned. It was made on `irc-integration` at 72947228 and the Iceberg fork at a88f556c5.

## The compatibility kit does not move

None of the 96 kit failures is about scan planning or credentials, and the kit has no test for either [read]. Of the
96, 77 are OpenHouse's own rules, 9 are routes not served (namespace create and drop 7, view rename 2) and 10 are
kit assumptions [read]. The count "conforming" would need is mostly policy (D32), not V11 or V12 work.

| Cause | Tags (count) | Failed |
|---|---|---|
| Namespace has no independent existence (a view cannot create its database; empty-namespace listing is 404) | VIEWDB 24, LISTNS 2 | 26 |
| Namespace create/alter/drop not served (406) | NSWRITE 7 | 7 |
| View rename not served (406) | VRENAME 2 | 2 |
| View policy: Spark dialect only, reserved view properties | DIALECT 13, DIALECTRES 2 | 15 |
| Table policy: replace needs `replace.enabled` | RTAS 16 | 16 |
| Schema and partition evolution rules | SCHEMA 4, SPEC 9 | 13 |
| Other model differences: format v1 refused, ids with hyphens, create history, drop always purges | FMT 2, IDS 3, CREATEHIST 1, DROPPURGE 1 | 7 |
| Kit asserts reference message text, or 404 versus 500 on a missing metadata file | MSG 8, NOTFOUND 1 | 9 |
| Kit's own contradiction under `requiresNamespaceCreate=false` | KITNS 1 | 1 |
| Scan planning | none | 0 |
| Credentials or signing | none | 0 |

The total is 96 (class b 77, c 9, e 10). The 23 skipped are 22 capability flags and one kit `@Disabled`. Each test is
classed by the first step it fails [read]. Serving namespace create would not fix the 24 VIEWDB failures unless
namespaces could also exist empty, which is an OpenHouse model change (D20) [inferred].

## Why scan planning is cheap and safe (V11)

- A 1.12 client plans on the server only when `scan-planning-mode=server` is set in the table's load config or in the
  client's catalog properties. The default is `client`. Listing the endpoint alone changes nothing [read].
- The service sets nothing of the kind: the read bridge sends only column defaults. So no client changes path. The
  one change is for a client already configured for server planning, which now plans on the server instead of failing
  fast. A 1.5.2 client ignores the endpoint list [read and inferred].
- Spark on 3.5 and 4.2 under 1.12 then sends SELECT and the copy-on-write scans of MERGE, UPDATE and DELETE through the
  server, and pushes LIMIT as `min-rows-requested`. The fork already runs Spark's `TestSelect` with server planning
  [read]. Incremental and changelog scans and metadata tables still plan on the client [read; changelog inferred].
- Most of the server side exists: the request and response classes and serializers, which `IcebergRestJson` already
  registers, and the response builder that derives the delete files. v3 deletion vectors appear as position deletes
  with a content offset [read].
- The service adds a route secured like load, the same checks as load, and a live Iceberg `Table` with its
  per-storage FileIO, for which a new repository or service method is needed. The paths in the response are those load
  already exposes, so no new authorization is needed [read and inferred].
- Two hazards rule out Iceberg's own handler: its state is a static map that only cancel clears, a leak that is also
  wrong behind several instances, and it cuts the list to `min-rows-requested` files, so a LIMIT over files whose rows
  are all deleted could return fewer rows than client planning [read; consequence inferred].
- Cost moves to the service, because manifests are read on its heap. A bound is a design choice (V11c) [inferred].
- Registering the route lists it and takes it out of the 406 catch-all; `IrcConfigEndpointsTest` pins the list and
  must change [read and inferred].

## Why credential vending cannot be built from what is here (V12)

- OpenHouse has no credential it could scope down. S3 and ADLS use the cluster's long-lived keys from cluster
  configuration, and HDFS uses the service's own delegation token. No STS, SAS minting, presigner or signer exists in
  the service or the native client [read]. Engines bring their own storage credentials (the V3b record says so).
- Handing out the cluster's static keys would vend root access and must not be done [inferred].
- S3 needs STS AssumeRole with a session policy scoped to the table prefix: a new AWS dependency, an IAM role and a
  secret. ADLS needs a user-delegation SAS (an Entra identity) or an account-key SAS (a secret). Remote signing for S3
  works without STS but puts every data request through the service [inferred].
- HDFS cannot be vended to any 1.12 client: `HadoopFileIO` accepts no credentials, and remote signing is S3 only
  [read; the conclusion inferred]. Declare it not applicable.
- ADLS credentials do not reach `ADLSFileIO` through the standard credentials interface; it takes a SAS from config
  and refreshes through its own endpoint [read].
- OpenHouse's `SELECT` privilege is defined but no service code checks it, so there is no access contract to vend
  against [read]. These are Mike's decisions: V12b to V12e.
- Only the empty `GET .../credentials` is free of those decisions. Its value is low, since it only makes the route
  exist [inferred].

## What reading could not settle

- Whether the service runs as several instances behind one address. This decides whether any plan state is acceptable.
- The service's real memory and latency cost of planning large tables.
- Whether MinIO's STS supports a prefix-scoped session policy as AWS does, in the image the recipes use.
- Whether LinkedIn's HDFS deployment would want delegation-token issuance through an OpenHouse extension at all.
