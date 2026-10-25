# Runnable quick-start examples

These examples use published Kotaml 0.112.0 artifacts from Maven Central, not the
working tree. From the repository root, with a JDK suitable for the Gradle wrapper:

```sh
./gradlew -p docs/examples run
./gradlew -p docs/examples runTree
./gradlew -p docs/examples runJson
```

The three entry points mirror the page's read/write, tree and JSON snippets. The
package declarations keep their `main` functions separate. `kotaml-json` is only
needed by the JSON example.
