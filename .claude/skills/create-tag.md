# Skill: create-tag

Step-by-step workflow for adding new tags or tag-types to a tag content
package in `zerobias-org/tag`. Companion to [CLAUDE.md](../../CLAUDE.md).

## When to use

- Adding a new tag (`<name>.yml`) to an existing tag-type directory
- Adding a brand-new tag-type subdirectory
- Renaming or fixing tag descriptions / IDs
- Investigating why a tag YAML failed at gate or load time

## Architecture recap (read first)

A tag package's payload is **directory-based**:

```
package/<scope>/<package>/
├── package.json              # zerobias.import-artifact = "tag"
├── build.gradle.kts          # plugins { id("zb.content") }
├── gate-stamp.json           # produced by ./gradlew :<pkg>:gate
└── <tag-type>/               # one directory per tag-type
    ├── index.yml             # OPTIONAL — { description, color }
    ├── <tag-a>.yml           # one tag per file: { id, description }
    └── <tag-b>.yml
```

The dataloader (`com/platform/dataloader/src/processors/tag/TagFileHandler.ts` + `TagArtifactLoader.ts`) is the source of truth for what loads on prod. The gradle validator at the repo root (`build.gradle.kts` → `extra["contentValidator"]`) mirrors it so failures surface at gate time, not after publish.

## Step 1: Pick the package

Today there's only `package/zerobias/global-tags`. If a new package is needed, create the directory + `package.json` + one-line `build.gradle.kts` marker; the auto-discovery in `settings.gradle.kts` will pick it up.

## Step 2: Add the tag YAML

### 2a. Existing tag-type

Pick the tag-type directory (e.g. `env-type/`, `marketplace/`, `query-folder/`) and create a new file:

```bash
cd package/zerobias/global-tags/env-type
cat > my-new-tag.yml <<'EOF'
id: <fresh-uuid-v4>
description: Human-readable description of the tag
EOF
```

Generate the UUID v4 from any standard tool (`uuidgen`, the `crypto.randomUUID()` function, etc.). The dataloader rejects non-UUID values via `new UUID(dict.id)`.

The **filename** (without `.yml`) becomes the tag's name — pick something kebab-case-friendly. The dataloader records it directly as the Tag's `name` field.

### 2b. New tag-type

Create the subdirectory under the package. The directory name must match the Nmtoken regex `^[a-z0-9:_.\-]+$` (lowercase alphanumeric plus `:`, `_`, `.`, `-`):

```bash
cd package/zerobias/global-tags
mkdir my-new-tag-type
cd my-new-tag-type

# Optional but recommended for new tag-types:
cat > index.yml <<'EOF'
description: What this tag-type categorizes
color: '#0066CC'
EOF

# At least one tag inside
cat > example.yml <<'EOF'
id: <fresh-uuid-v4>
description: Example tag in this new tag-type
EOF
```

`index.yml`'s `color` is read as-is by the dataloader (no format constraint), but conventionally a hex color (`#RRGGBB`).

## Step 3: Validate locally

Schema check first (fast, offline):

```bash
./gradlew :zerobias:global-tags:validateContent
```

If that passes, run the full gate (loads the artifact into an ephemeral Neon Postgres branch — needs `NEON_API_KEY`/`NEON_PROJECT_ID`):

```bash
./gradlew :zerobias:global-tags:gate
```

`gate` writes a fresh `package/zerobias/global-tags/gate-stamp.json`. **Always commit that file with your tag changes** — the publish workflow's preflight rejects pushes with a missing or stale stamp.

## Step 4: Commit and push

Conventional Commits format. Examples:

```
feat(tag): add canary env-type tag for pre-prod testing
feat(tag): add new compliance-segment tag-type with initial tags
fix(tag): correct prod env-type description
```

Stage:
- the new/modified tag YAML files
- the regenerated `gate-stamp.json`
- (if the tag-type is new) the new directory's `index.yml`

```bash
git add package/zerobias/global-tags/<paths>
git commit -m "feat(tag): ..."
git push origin <your-branch>
```

Open a PR against `main` (the repo flows through main → uat → qa → dev sync after publish, same pattern as vendor).

## Step 5: Verify the publish

After merge:

```bash
gh run list --repo zerobias-org/tag --branch main --limit 3
```

The `Publish` workflow's `detect` job picks up changed packages, the matrix `publish (<package>)` step runs `zbb publish` (gate-stamp preflight → `npm publish --tag next` → cumulative promote to `dev`/`qa`/`uat`/`latest`), and `sync` rebases the protected branches.

Check npm dist-tags:

```bash
npm dist-tag ls @zerobias-org/tag-zerobias-global-tags
# expect dev/qa/uat/latest all on the new version
```

To verify the tag landed on the platform, switch to a prod profile and query the tag service via the ZeroBias MCP (`platform.Tag.*` operations).

## Common Issues

### Gate fails on `validateContent`

- `id` is missing or not a UUID v4
- `description` is missing or blank
- A tag-type directory name has invalid characters (e.g. uppercase, spaces) — it must match `^[a-z0-9:_.\-]+$`
- Tag-type's `index.yml` has empty `description` or `color`

### Gate fails on `testIntegrationDataloader`

- Locally: `NEON_API_KEY` not set in your zbb slot — the task should skip cleanly. If it errors, check `zbb stack` is on the right slot.
- CI: dataloader rejected the artifact. Read its stderr in the job log; common causes are duplicate IDs across tags (each `id` must be globally unique among all tags) or a `Tag.type` Nmtoken violation.

### `Gate stamp invalid — stamp-missing`

`gate-stamp.json` was deleted or never generated. Re-run `./gradlew :<pkg>:gate` locally and commit the result.

### Detect picks up no packages

The publish workflow diffs `package/**/build.gradle.kts` against `origin/main`. If you only edited tag YAMLs but not the package's `build.gradle.kts`, `detect` won't list the package and the matrix step is skipped. The fix is on the workflow side, not your branch — current behavior is intentional for the bulk-migration pattern. Push a no-op touch to the marker file if you need a deliberate republish.

## Quick reference

| Field | Required? | Format |
|---|---|---|
| `id` (in tag YAML) | yes | UUID v4 |
| `description` (in tag YAML) | yes | non-blank string |
| Tag YAML filename | yes | `<name>.yml`, becomes `Tag.name` |
| Tag-type directory name | yes | Nmtoken: `^[a-z0-9:_.\-]+$` |
| Tag-type `index.yml` | optional | `{ description?: string, color?: string }` |
| `skip: true` (in tag YAML) | optional | bool — dataloader skips this tag on load |
| `deprecate: true` (in tag YAML) | optional | bool — dataloader logs only (no-op currently) |

## See also

- `CLAUDE.md` — repo overview
- `build.gradle.kts` — validator implementation
- `com/platform/dataloader/src/processors/tag/` — load-side source of truth
