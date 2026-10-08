# GitHub Actions maintenance

Every non-local `uses` reference in a workflow must use a full 40-character
commit SHA. Keep the release version on the same line, for example:

```yaml
uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
```

This applies to action subpaths and reusable workflows as well as ordinary
actions. Local `./` references are allowed. Docker image references are not
accepted by this commit-SHA policy.

Run the same check as CI from the repository root with the Kotlin script runner
(`kotlinr`, Kotlin 2.4.10+). The script uses a pinned published Kotaml dependency
to parse YAML, including aliases and merges, without building this repository:

```sh
kotlinr .github/scripts/check-action-pins.main.kts
```

The first run downloads dependencies and compiles the script; later runs use
the script cache. Optional arguments select individual workflow files. For
direct execution, run `chmod +x .github/scripts/check-action-pins.main.kts`
once, then `./.github/scripts/check-action-pins.main.kts`.

CI uses the SHA-pinned `Heapy/setup-main-kts` action to install Kotlin 2.4.20
and Java 25, and cache the compiler, dependencies, and compiled script.

The weekly `github-actions` Dependabot configuration opens update PRs for the
pins and their release comments. Review the upstream release and changes,
confirm the SHA belongs to the upstream repository and matches the commented
release (peel annotated tags to their commit), and require the workflow lint
to pass before merging. Do not replace a SHA with a version tag or branch.

GitHub documents Dependabot's support for updating these comments in its
[release announcement](https://github.blog/changelog/2022-10-31-dependabot-now-updates-comments-in-github-actions-workflows-referencing-action-versions/).
