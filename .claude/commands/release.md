# Release

Perform a full release of kotaml. The user provides the new version as an argument: `$ARGUMENTS`

If `$ARGUMENTS` is empty, read the current version from `gradle.properties` and ask the user what the new version should be.

## Steps

Execute these steps sequentially. Stop and report if any step fails.

### 1. Update version

Update the `version=` line in `gradle.properties` to the new version.

Search `README.md` for any occurrences of the old version string and replace them with the new version.

### 2. Run publish.sh

```bash
bash publish.sh
```

This builds, signs, publishes artifacts, and uploads the bundle to Sonatype Central.

### 3. Commit

Stage `gradle.properties` and `README.md`, then commit with message:

```
Release v<version>
```

### 4. Create tag

```bash
git tag v<version>
```

### 5. Push

```bash
git push && git push --tags
```

### 6. Create a GitHub release

First, gather the material for the release notes by inspecting everything merged since the previous tag:

```bash
# Previous tag (the one before the new tag you just pushed)
PREV=$(git describe --tags --abbrev=0 v<version>^)

# Commits since the previous tag, with author
git log "$PREV..v<version>" --pretty='%s — %an'

# Merged PRs since the previous tag (number, title, author)
gh pr list --state merged --base main --json number,title,author,mergedAt \
  --jq '.[] | "#\(.number) \(.title) — @\(.author.login)"'
```

Use this to write the notes by hand (do **not** rely solely on `--generate-notes`). The notes must contain:

- **Significant changes** — a short, human-curated summary of the notable user-facing changes (new features, behavior changes, bug fixes, breaking changes). Group related commits, lead with what matters, and skip noise like dependency bumps and internal chores unless they affect users. Reference the PR number for each (e.g. `#12`).
- **Contributions** — credit every contributor in this release by `@handle`, and call out first-time contributors. Note any external (non-maintainer) contributions specifically.

Write the curated notes to a temp file and create the release. Append `--generate-notes` so GitHub's auto-generated changelog and full-changelog link are included below your hand-written summary:

```bash
gh release create v<version> --title "v<version>" --notes-file <notes-file> --generate-notes
```

Before publishing, show the drafted notes to the user for confirmation.
