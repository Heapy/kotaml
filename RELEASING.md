# Release process

1. Set the release version in `library.module-template.yaml` and update the README dependency examples.
2. Restore locked plugins with `./ktc-plugins sync`, then run the CI platform matrix and
   `./ktc-plugins verify`. Review any JVM/KLib ABI changes.
3. Use macOS with Xcode to assemble all publication targets. Export these environment variables from your secret store:
   * `KOTLIN_TOOLCHAIN_MAVEN_CENTRAL_USERNAME`
   * `KOTLIN_TOOLCHAIN_MAVEN_CENTRAL_PASSWORD`
   * `KOTLIN_TOOLCHAIN_SIGNING_KEY` (ASCII-armored PGP private key)
   * `KOTLIN_TOOLCHAIN_SIGNING_KEY_PASSPHRASE` (if the key is encrypted)
4. Run `./kotlin publish mavenCentral`. The toolchain builds both libraries, signs the artifacts,
   and uploads to the Central Portal in **manual** mode. Inspect the deployment before releasing it.
5. Create the signed version tag and GitHub release after the deployment succeeds.

For a local consumer trial, `./kotlin publish mavenLocal` uses the same coordinates and signing
configuration. It also requires the signing key. Never publish the migration under an already
released version. The ignored legacy `publish.sh` is obsolete and should not be used.

Mention the migration's publication changes in the release notes: `watchosX64` is no longer
published, and the Wasm artifacts are named `kotaml-wasmjs` and `kotaml-json-wasmjs`
instead of `kotaml-wasm-js` and `kotaml-json-wasm-js`. Consumers using the root coordinates
resolve the Wasm variants through module metadata without changing their declarations.
