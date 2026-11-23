# Renovate: linkedin/iceberg version sync

The default build uses Iceberg 1.12, built from a commit pinned in
`scripts/iceberg-fork/iceberg-fork.properties`; Renovate does not manage that pin
(see [iceberg-fork.md](iceberg-fork.md)). The reference build (`-PicebergLinkedIn`)
uses the LinkedIn Iceberg fork's releases (`com.linkedin.iceberg`, published to
Maven Central) on **one minor line**, which Renovate keeps current:

| Source of truth | Line | Drives |
| --- | --- | --- |
| `iceberg_1_5_version` in `build.gradle` | `1.5.x` | the reference build: Spark 3.5 stack, `iceberg-1.5` integrations, services, `datalayout` |

Until the Iceberg 1.2 / Spark 3.1 projects were deleted there was a second pin,
`iceberg_1_2_version`, held on `1.2.x` in the same way.

Renovate ([`.github/renovate.json`](../../.github/renovate.json)) keeps the pin at
the newest release **within its own line** and opens a PR when a newer
version exists. It runs hourly via the self-hosted
[`renovate.yml`](../../.github/workflows/renovate.yml) workflow.

## Why Renovate (and not native Dependabot)

Native Dependabot cannot express this repo's setup:

1. **A pin held on one minor line.** `iceberg-core`, `iceberg-common`,
   `iceberg-data`, and `iceberg-bundled-guava` must stay on `1.5.x` while newer
   lines of the same artifacts exist. Renovate's `matchCurrentVersion` and
   `allowedVersions` cap the pin at its own line.
2. **Cross-file `ext` indirection.** Versions are defined once in the root
   `build.gradle` and read elsewhere via `rootProject.ext.…` and `buildSrc`
   convention plugins. Instead of relying on a parser to follow that chain, a
   Renovate `customManager` (regex) updates the source-of-truth lines directly.

`enabledManagers: ["custom.regex"]` scopes Renovate to **only** this
version string — it does not touch any other dependency. GitHub Actions updates
remain handled by the existing [`dependabot.yml`](../../.github/dependabot.yml).

## How the line cap works

The line is held by a `packageRule` keyed on the current version:

```json
{
  "matchPackageNames": ["com.linkedin.iceberg:iceberg-core"],
  "matchCurrentVersion": "/^1\\.5\\./",
  "allowedVersions": "/^1\\.5\\./"
}
```

The 4-part LinkedIn versions (`1.5.2.11`) are compared with Renovate's `maven`
versioning, and release candidates (`1.5.2.0-rc1`) are skipped as unstable.

## One-time setup

Create a repository (or org) secret named **`RENOVATE_TOKEN`** so Renovate can
open PRs **and** so CI runs on them — PRs opened with the default `GITHUB_TOKEN`
do not trigger other workflows. Use one of:

- a classic PAT with `repo` + `workflow` scope, or
- a fine-grained PAT / GitHub App installation token with read+write on
  **Contents** and **Pull requests**.

Without `RENOVATE_TOKEN` the workflow falls back to `GITHUB_TOKEN`: PRs are still
created, but their CI must be re-run manually.

## Verifying / running on demand

- Trigger **Actions → "Renovate (linkedin/iceberg sync)" → Run workflow** with
  the `Dry run` option to see what Renovate would do without opening PRs.
- Validate config changes locally:
  `npx --yes --package renovate -- renovate-config-validator .github/renovate.json`
