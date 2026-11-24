# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

This is the **closed-source tag content repository** under `@zerobias-com`. It hosts global tag artifacts that the ZeroBias platform's dataloader pulls in to populate the `tag` table on platform load.

Today the repo contains a single tag package — `package/zerobias/global-tags` (npm: `@zerobias-com/tag-zerobias-global-tags`). The structure is built to support more in the future without code changes.

## Essential Commands

### Development Setup

```bash
# Root devDeps (commitlint, tsx for scripts/correctDeps.ts)
npm install

# Reset workspace
npm run reset
```

### Working with Tags

```bash
# Schema check on a tag package (fast, offline)
./gradlew :zerobias:global-tags:validateContent

# Full gate (validate + dataloader on an ephemeral Neon Postgres branch);
# writes gate-stamp.json on success — commit that file with your changes
./gradlew :zerobias:global-tags:gate

# List auto-discovered tag projects
./gradlew projectPaths
```

The gate's `testIntegrationDataloader` step needs `NEON_API_KEY` / `NEON_PROJECT_ID` (sourced from the operations-kv vault via `zbb.yaml`'s env block). Locally, if the keys aren't set the dataloader step skips cleanly; CI always runs the full gate.

### Publishing and Version Management

Publishing is driven by the gradle `Publish` GitHub Actions workflow, triggered on push to `main` / `qa` / `dev` / `uat`. It:

1. `detect` — diffs `package/**/build.gradle.kts` against `origin/main` to find changed tag packages
2. matrix `publish (<package>)` — runs `zbb publish` per package (gate preflight via committed `gate-stamp.json` → `npm publish --tag next` → cumulative `promoteAll` to dev/qa/uat/latest)
3. `sync` — propagates main → uat → qa → dev after a successful main publish

```bash
# Manually trigger the workflow on a branch
gh workflow run publish.yml --repo zerobias-com/tag --ref dev
```

## Architecture

### Package Layout

A tag package's payload is **directory-based**, not file-based — each subdirectory under the package root is a *tag-type*, and each `*.yml` file inside (other than `index.yml`) is a single tag.

```
package/zerobias/global-tags/
├── package.json                # zerobias.import-artifact = "tag"
├── build.gradle.kts            # one-line marker: plugins { id("zb.content") }
├── gate-stamp.json             # written by ./gradlew :<pkg>:gate
├── env-type/                   # tag-type (directory name = Nmtoken)
│   ├── index.yml               # OPTIONAL — description + color for the tag-type
│   ├── prod.yml                # tag: id (UUID) + description
│   ├── qa.yml
│   ├── ci.yml
│   ├── dev.yml
│   └── lab.yml
├── product-segment/
├── service-segment/
├── query-folder/
├── marketplace/
└── other/
```

### What the validator enforces

The dataloader is the source of truth for schema rules (UUID format on tag ids, Nmtoken regex on tag-type dir names, `description` non-blank, etc.). Re-validating those at gate time would just create drift risk — the full schema is exercised by `testIntegrationDataloader` against an ephemeral Neon Postgres branch as part of `gate`.

The inline validator at `build.gradle.kts` (`extra["contentValidator"]`) only enforces what the dataloader **cannot** or **does not** check:

1. **Filesystem ↔ npm ↔ zerobias-block triangulation** — for a tag package at `package/{scope}/{name}/`:
   - `package.json` `name` must equal `@zerobias-com/tag-{scope}-{name}`
   - `zerobias.package` (or legacy `auditmation.package`) must equal `{scope}.{name}.tag` (the `.tag` suffix distinguishes from other artifact types)
   - The dataloader reads `zerobias.package` but never the npm `name` field — a wrong name would publish under the wrong package and only surface in production. Implemented via `SchemaPrimitives.requirePackageIdentity`.
2. **`zerobias.import-artifact` is `"tag"`** — sanity check against accidentally publishing as the wrong artifact type.
3. **Repo-wide unique tag `id` UUIDs** — registered as `:validateUniqueTagIds` at the root, automatically a dependency of every per-package `validateContent`. Walks every `*.yml` (excluding `index.yml` and `node_modules`) and fails on collisions. The dataloader processes one tag at a time, so collisions only surface when the second one tries to load to the same DB row.

Everything else (UUID parse, Nmtoken regex on tag-type dir names, `description` non-blank on tag YAMLs, `color`/`description` shape on optional per-tag-type `index.yml`, `zerobias.dataloader-version` non-blank, etc.) is delegated to the dataloader running during `gate`.

### Key Technologies

- **Gradle (zb.content plugin)**: Drives validate / gate / publishNpm / promoteAll per tag package. Plugin lives in `zerobias-org/util` and resolves via `settings.gradle.kts`.
- **zbb**: Lifecycle CLI used by CI (`zbb publish`); honors the workspace declaration for content-package discovery.
- **TypeScript**: `scripts/correctDeps.ts` (executed via `tsx`). Validation rules live in the root `build.gradle.kts` (`extra["contentValidator"]`) composed from `SchemaPrimitives` shipped by `zerobias-org/util` build-tools.
- **Lerna (legacy)**: Still referenced in root `package.json` scripts (`lerna:dry-run`, `lerna:version`) but unused — the gradle pipeline has superseded it. Don't add new lerna config. Nx has been removed entirely.
- **Conventional Commits**: Enforced via commitlint for automated versioning.

### Settings auto-discovery

`settings.gradle.kts` walks `package/` looking for `build.gradle.kts` markers — same pattern as `org/vendor`. The project path mirrors the filesystem path (`package/zerobias/global-tags` → `:zerobias:global-tags`). To add a new tag package, drop the marker and gradle picks it up.

## Common Workflows

### Adding a new tag to an existing tag-type

1. Pick the tag-type directory (e.g. `package/zerobias/global-tags/env-type/`)
2. Create `<tag-name>.yml` with a fresh UUID v4 + description:

```yaml
id: <new-uuid-v4>
description: <human-readable description>
```

3. `./gradlew :zerobias:global-tags:gate` — writes a fresh `gate-stamp.json`
4. Commit the new YAML + the bumped `gate-stamp.json` together
5. Push to `dev` (or open a PR); CI publishes on merge to `main`

### Adding a new tag-type

1. Create `package/zerobias/global-tags/<new-tag-type>/` (directory name must match Nmtoken regex `^[a-z0-9:_.\-]+$`)
2. Optionally add `index.yml` with `description` + `color` for the tag-type
3. Add at least one tag YAML inside
4. Same gate / commit / push flow as above

### Bumping the package version

The package follows the gradle-era version line (currently `2.x.x`). The pre-gradle line was lerna-managed at `1.x.x`. Don't downgrade across the major boundary.

## Important Notes

- **Authentication**: Set `ZB_TOKEN` (or `NPM_TOKEN` / `GITHUB_TOKEN`) for npm registry access; `NEON_API_KEY` + `NEON_PROJECT_ID` for the dataloader integration step (see `zbb.yaml`).
- **Commit Format**: All commits must follow Conventional Commits specification.
- **Private Registry**: Packages publish to `npm.pkg.github.com/@zerobias-com`.
- **No Direct npm publish**: Publishing is driven by the gradle `Publish` workflow on push to `main`/`qa`/`dev`/`uat`; locally use `./gradlew :<pkg>:gate` to validate before push.
- **Naming**: Package names follow `@zerobias-com/tag-<flat-package-path>` (e.g. `zerobias.global-tags` → `@zerobias-com/tag-zerobias-global-tags`).

## See Also

- **[.claude/skills/create-tag.md](.claude/skills/create-tag.md)** — step-by-step workflow for adding tags / tag-types
- **`build.gradle.kts`** — validator implementation (`extra["contentValidator"]`)
- **`com/platform/dataloader/src/processors/tag/`** (in the meta-repo) — source of truth for what loads on prod
- **`org/vendor/CLAUDE.md`** — sibling content repo on the same gradle pipeline; useful reference when patterns drift
