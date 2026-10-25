# Documentation site

`site/index.html` is the canonical user documentation. Keep the root README short and link to the site instead of duplicating usage or behavior reference.

`site/` is a static GitHub Pages site. It has no build-time JavaScript dependencies
and works without JavaScript; install tabs, dependency options, syntax highlighting,
copy buttons and navigation highlighting are enhancements. Without JavaScript,
all installation snippets remain visible.

Highlight.js 11.12.0 is vendored in `site/vendor/highlight/`: the common browser
bundle, Kotlin grammar, GitHub Dark theme and upstream BSD license. Assets come
from the pinned `@highlightjs/cdn-assets@11.12.0` package on jsDelivr; the license
comes from `highlight.js@11.12.0`. The site makes no CDN requests at runtime.
Kotlin, YAML, INI/TOML and XML code blocks declare their languages explicitly.

Run the scripts from the repository root with Kotlin 2.4.21 (`kotlinr`) and JDK 25,
matching CI. The first run downloads pinned dependencies and compiles the script;
later runs use the script cache. No Gradle build or serialization compiler plugin is needed.

Preview locally:

```sh
kotlinr docs/serve.main.kts
```

Open <http://127.0.0.1:8000>. The server binds only to loopback; stop it with Ctrl+C.
To choose another port, use `kotlinr docs/serve.main.kts 8765`.

## Diagrams

Edit `diagrams/*.puml` and the shared `diagrams/theme.puml.inc`. Install PlantUML
and Graphviz, then run:

```sh
kotlinr docs/render.main.kts
kotlinr docs/check.main.kts
```

Commit both the PlantUML sources and generated SVGs. The manifest records source
and output hashes plus the PlantUML version. CI checks links and detects stale
diagrams without downloading a renderer or sending sources to an external server.
Layout can vary with the Graphviz and font versions on the rendering machine.

## Publication

`.github/workflows/pages.yml` validates documentation on pull requests and publishes
`docs/site/` on changes to `main` or manual dispatch. The repository's Pages source
must be **GitHub Actions** and the `github-pages` environment must allow `main`.

Quick-start dependencies follow the published release; architecture diagrams follow
`main`. Update versions and verify examples when changing the quick start. Changes
in the reader or writer may also require updating a diagram.
