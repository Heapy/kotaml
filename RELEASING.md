# Releasing kotaml

This is the release runbook for both `kotaml` and `kotaml-json`. Both modules use
the version in `gradle.properties` and are released together.

There are two processes:

1. **Current:** build and sign on the maintainer's local machine, then publish to
   Maven Central through the Sonatype Central Portal.
2. **Planned:** build unsigned artifacts in CI, transfer them to `scarb`, then sign
   and publish from `scarb`.

The second process is a design for future implementation. The current GitHub
Actions workflow runs checks; it does not implement the CI-to-scarb release flow.

## 1. Current process: local build and publication

### Prerequisites

- Use the maintainer's release machine with the JDK and Kotlin/Native toolchains
  needed to build the published targets, including Apple targets.
- Have the local `publish.sh`, signing key, and Sonatype Central credentials
  configured. `publish.sh` is intentionally ignored by Git and is not available
  in a fresh clone.
- Have Git push access and an authenticated `gh` CLI for creating the GitHub release.
- Start from the intended `main` revision with unrelated work committed or set
  aside, and check that its GitHub Actions checks have passed.

Execute the steps sequentially. Stop and investigate if any step fails.

### Choose and update the version

Choose the release version explicitly; if it has not been provided, ask for it.
Confirm that neither the release tag nor that version's Central publication
already exists.

Record the previous release tag before creating the new one:

```bash
previous_tag=$(git describe --tags --abbrev=0)
```

Update `version=` in `gradle.properties` and the dependency examples in `README.md`
to the new version. Review the diff so the publication contains only the intended
release changes.

### Build, sign, and publish locally

```bash
bash publish.sh
```

The current local script runs checks, cleans the build, publishes signed Maven
artifacts to `build/staging-deploy`, and uploads a bundle to Sonatype Central with
automatic publishing enabled. Running it is the publication step, not a dry run.

Wait for Central to report the deployment as published before proceeding. An
accepted upload alone does not confirm publication. If the command fails after
uploading, inspect the existing deployment before retrying.

### Commit, tag, and push

After successful Central publication, commit the version changes and tag the
exact source revision used for the release:

```bash
git add gradle.properties README.md
git commit -m "Release v<version>"
git tag v<version>
git push origin main
git push origin v<version>
```

Do not make further source changes between building the artifacts and creating
the release commit. Push the specific release tag rather than all local tags.

Finish by creating the GitHub release as described below.

## 2. Planned process: CI build, signing and publication on scarb

This process is not operational yet. Its intended sequence is:

1. **Prepare the release commit.** Update `gradle.properties` and the README
   dependency examples, commit as `Release v<version>`, and push to `main`. Record
   the previous release tag and the exact release commit SHA.
2. **Build and test in CI.** Check out that exact commit. Run the required checks
   and build unsigned Maven publications for both modules and all published
   targets on suitable runners, including macOS for Apple targets. CI must not
   hold the release signing key or Central publishing credentials.
3. **Collect the release artifacts.** Assemble a complete Maven repository layout
   containing the artifacts, POMs, Gradle module metadata, sources, and Javadoc
   artifacts. Record the version, commit SHA, CI run, expected publication list,
   and file checksums in a manifest. Fail on missing publications or conflicting
   files when combining outputs from multiple runners.
4. **Transfer to scarb.** Send the unsigned artifact bundle and manifest from the
   successful CI run to `scarb`. The transfer mechanism and destination still
   need to be implemented.
5. **Verify and sign on scarb.** Verify the manifest, checksums, version, commit,
   and successful CI run. Sign the received publications using the key held on
   `scarb`, generate the required Central bundle checksums, and assemble the
   upload bundle. Do not rebuild or change the artifacts on `scarb`.
6. **Publish from scarb.** Upload the signed bundle using Central credentials held
   on `scarb`, complete Central validation and publication, and record the
   deployment ID. Check deployment status before retrying an interrupted upload.
7. **Tag and announce from scarb.** Once Central publication succeeds, create and
   push `v<version>` pointing to the recorded release commit, then create the
   GitHub release using the shared release-notes procedure below.

Implementation work remains: separate unsigned artifact staging from the current
Gradle signing tasks; add the CI release workflow and artifact manifest; implement
transfer to `scarb`; and provide the signing, Central publication, and GitHub
release commands on `scarb`. The existing local `publish.sh` is not the planned
scarb entry point because it also builds the project.

## GitHub release notes (both processes)

Inspect all changes between the previous release tag and the new release tag:

```bash
git log "$previous_tag..v<version>" --pretty='%h %s — %an'
git diff --stat "$previous_tag..v<version>"
```

Inspect the associated merged PRs for descriptions and contributor handles. Use
the commit range to determine which PRs belong to this release; an unfiltered
list of recently merged PRs is not sufficient.

Write curated notes containing:

- **Significant changes:** summarize notable user-facing features, behavior
  changes, fixes, and breaking changes. Group related changes and reference PR
  numbers. Omit dependency bumps and internal chores unless they affect users.
- **Contributions:** credit every contributor in the release by `@handle`, call
  out first-time contributors, and note external contributions specifically.

Show the drafted notes to the maintainer for confirmation before publishing the
GitHub release. Save the approved notes to a temporary file, then run:

```bash
gh release create v<version> --verify-tag --title "v<version>" \
  --notes-file <notes-file> --generate-notes
```

`--generate-notes` appends GitHub's generated changelog and comparison link to the
curated notes; it does not replace the human-written summary. Verify the published
release links to the intended tag and that both modules are available on Central.
