# kotaml

**kotaml** is a supported fork of [kaml](https://github.com/charleskorn/kaml) by [Charles Korn](https://github.com/charleskorn).

[![Pipeline](https://github.com/Heapy/kotaml/actions/workflows/build.yml/badge.svg)](https://github.com/Heapy/kotaml/actions/workflows/build.yml)
[![License](https://img.shields.io/github/license/Heapy/kotaml.svg)](https://opensource.org/licenses/Apache-2.0)
[![Maven Central](https://img.shields.io/maven-central/v/io.heapy.kotaml/kotaml.svg?label=maven%20central)](https://central.sonatype.com/artifact/io.heapy.kotaml/kotaml)

## What is this?

This library adds YAML support to [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization/).

Currently, only Kotlin/JVM is fully supported.

Kotlin/JS and Kotlin/Wasm support are considered highly experimental. It is not yet fully functional, and may be removed or modified at any time.

YAML version 1.2 is supported.

## Usage samples

### Parsing from YAML to a Kotlin object

```kotlin
@Serializable
data class Team(
    val leader: String,
    val members: List<String>
)

val input = """
        leader: Amy
        members:
          - Bob
          - Cindy
          - Dan
    """.trimIndent()

val result = Yaml.default.decodeFromString(Team.serializer(), input)

println(result)
```

### Serializing from a Kotlin object to YAML

```kotlin
@Serializable
data class Team(
    val leader: String,
    val members: List<String>
)

val input = Team("Amy", listOf("Bob", "Cindy", "Dan"))

val result = Yaml.default.encodeToString(Team.serializer(), input)

println(result)
```

### Parsing into YamlNode

It is possible to parse a string or an InputStream directly into a YamlNode, for example
the following code prints `Cindy`.
```kotlin
val input = """
        leader: Amy
        members:
          - Bob
          - Cindy
          - Dan
    """.trimIndent()

val result = Yaml.default.parseToYamlNode(input)

println(
    result
        .yamlMap.get<YamlList>("members")!![1]
        .yamlScalar
        .content
)
```

When a parsed `YamlNode` is serialized, plain numeric and boolean scalars keep their
source text, including `007`, `0x7`, `1E+003` and `True`. This preserves numeric
precision and distinct mapping keys. String quoting and document formatting follow
the writer configuration.

### Temporary reader compatibility when upgrading to 0.111.0

`LegacyV0_110` is intended **only for a quick upgrade to 0.111.0** and **will be removed
in a future release**. Use its diagnostics to migrate existing YAML, then remove the
option and return to the default strict reader.

```kotlin
val compatibility = YamlReadCompatibility.LegacyV0_110(
    onUse = { issue ->
        println("${issue.reason} at ${issue.path.toHumanReadableString()} " +
            "(${issue.location.line}:${issue.location.column}): " +
            "replace ${issue.originalValue} with ${issue.replacement}")
    }
)
val yaml = Yaml(configuration = YamlConfiguration(readCompatibility = compatibility))
```

The mode accepts legacy signed radix integers (`-0x11`, `-0o11`), Unicode integer
digits, floating point suffixes (`1f`, `1d`), `Infinity` / `-Infinity` / `NaN`,
hexadecimal floats (`0x1p3`), and radix integers as floats (`0x2`, `0o2`, `0b10`).
These forms are supported on every platform, including forms previously accepted
only by JVM or JS. Integer range checks still apply.

Unquoted `Null` and `NULL` remain strings in values and keys, including nullable
string fields. Their diagnostics occur during parsing; numeric diagnostics occur
when decoding a numeric type. Ordinary string values such as `Infinity` do not
trigger numeric diagnostics. The callback is optional, runs synchronously, and
may receive events before a later document error. Callback exceptions propagate.

**Breaking in 0.111.0:** strict reading rejects empty plain mapping keys, just as it
rejects `null` and `~` keys. To use an empty string key, quote it:

```yaml
? ""
: 1
```

`LegacyV0_110` preserves empty plain keys as empty strings and reports `LegacyNullKey`
with `""` as the replacement. Empty values still resolve to null in both modes.

Use this configured `Yaml` instance for both `parseToYamlNode` and typed decoding;
an already resolved `YamlNull` cannot recover its original spelling. Direct scalar
conversions require an explicit argument, for example `node.yamlScalar.toInt(compatibility)`.
The parameterless scalar conversions remain strict.

Encoding continues to use the new format. A typical migration reads a typed object
with compatibility enabled, writes it back, and verifies that the strict reader
can load the result. Parsing an untyped `YamlNode` does not infer numeric types for
legacy numeric strings.

## Referencing kotaml

Add the following to your Gradle build script:

```kotlin
plugins {
    kotlin("jvm").version("2.4.20")
    kotlin("plugin.serialization").version("2.4.20")
}

dependencies {
    implementation("io.heapy.kotaml:kotaml:0.111.0")

    // Optional kotlinx-serialization-json integration
    implementation("io.heapy.kotaml:kotaml-json:0.111.0")
}
```

Check the [releases page](https://github.com/Heapy/kotaml/releases) for the latest release information,
and the [Maven Central page](https://search.maven.org/artifact/io.heapy.kotaml/kotaml) for examples of how
to reference the library in other build systems.

## Features

* Supports most major YAML features:
  * Scalars, including strings, booleans, integers and floats
  * [Sequences (lists)](https://yaml.org/type/seq.html)
  * [Maps](https://yaml.org/type/map.html)
  * [Nulls](https://yaml.org/type/null.html)
  * [Aliases and anchors](https://yaml.org/spec/1.2/spec.html#id2765878), including [merging aliases to form one map](https://yaml.org/type/merge.html)

* Supports parsing YAML to Kotlin objects (deserializing) and writing Kotlin objects as YAML (serializing)

* Supports [kotlinx.serialization's polymorphism](https://github.com/Kotlin/kotlinx.serialization/blob/master/docs/polymorphism.md) for sealed and unsealed types

  Two styles are available (set `YamlConfiguration.polymorphismStyle` when creating an instance of `Yaml`):

  * using [YAML tags](https://yaml.org/spec/1.2/spec.html#id2761292) to specify the type:

    ```yaml
    servers:
      - !<frontend>
        hostname: a.mycompany.com
      - !<backend>
        database: db-1
    ```

  * using a `type` property to specify the type:

    ```yaml
    servers:
      - type: frontend
        hostname: a.mycompany.com
      - type: backend
        database: db-1
    ```

  The fragments above could be generated with:

  ```kotlin
  @Serializable
  sealed class Server {
    @SerialName("frontend")
    @Serializable
    data class Frontend(val hostname: String) : Server()

    @SerialName("backend")
    @Serializable
    data class Backend(val database: String) : Server()
  }

  @Serializable
  data class Config(val servers: List<Server>)

  val config = Config(listOf(
    Frontend("a.mycompany.com"),
    Backend("db-1")
  ))

  val result = Yaml.default.encodeToString(Config.serializer(), config)

  println(result)
  ```

* Supports [Docker Compose-style extension fields](https://medium.com/@kinghuang/docker-compose-anchors-aliases-extensions-a1e4105d70bd)

  ```yaml
  x-common-labels: &common-labels
    labels:
      owned-by: myteam@mycompany.com
      cost-centre: myteam

  servers:
    server-a:
      <<: *common-labels
      kind: frontend

    server-b:
      <<: *common-labels
      kind: backend

    # server-b and server-c are equivalent
    server-c:
      labels:
        owned-by: myteam@mycompany.com
        cost-centre: myteam
      kind: backend
  ```

  Specify the extension prefix by setting `YamlConfiguration.extensionDefinitionPrefix` when creating an instance of `Yaml` (eg. `"x-"` for the example above).

  Extensions can only be defined at the top level of a document, and only if the top level element is a map or object. Any key starting with the extension prefix must have an anchor defined (`&...`) and will not be included in the deserialised value.

### Converting a YamlNode tree to a JsonElement tree

The separate `kotaml-json` artifact converts a parsed YAML tree into a
[kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) `JsonElement` tree. It is a separate
artifact so that projects that do not need it do not take a dependency on `kotlinx-serialization-json`.

```kotlin
val input = """
    name: "123"
    port: 8080
    ratio: 1.10
    enabled: true
""".trimIndent()

val json = Yaml.default.parseToYamlNode(input).toJsonElement()

// {"name":"123","port":8080,"ratio":1.10,"enabled":true}
println(Json.encodeToString(JsonElement.serializer(), json))
```

A quoted scalar without an explicit type tag becomes a JSON string, so `"123"` stays the string `"123"`. A plain scalar is resolved
with the YAML 1.2 core schema, so `8080` becomes a number and `enabled: true` becomes a boolean. Numbers keep
their original text wherever JSON allows it, so `1.10` does not become `1.1` and an integer too large for a
`Long` keeps every digit.

Plain `null`, `Null`, `NULL`, `~` and empty content become JSON null, including in manually constructed
nodes: `YamlScalar("", path)` becomes null, while `YamlScalar("", path, plain = false)` stays an empty
string. In YAML text, `value:` therefore becomes `{"value":null}`, and `value: ""` becomes `{"value":""}`.
The converter always uses the Core Schema, including for plain scalars retained by legacy parsing.

Explicit `!!int`, `!!bool` and `!!float` tags determine the type even when the scalar is quoted:
`!!int "8080"` becomes the JSON number `8080`, `!!bool "false"` becomes `false`, and `!!float "1.50"`
becomes the number `1.50`. Values incompatible with their tag, such as `!!int "hello"` or `!!int "1.5"`,
throw `YamlException` with the value's path. `!!str` produces a string and `!!null` produces JSON null;
other tags are discarded before converting their contents.

`.inf`, `-.inf` and `.nan` have no JSON representation. By default the conversion throws
`NonFiniteNumberException`; pass `NonFiniteNumbers.AS_STRING` or `NonFiniteNumbers.AS_NULL` to convert them
anyway.

Both conversions are annotated with `@ExperimentalSerializationApi`. `toJsonElement` uses
`JsonUnquotedLiteral` to preserve the exact text of numbers, and `toYamlNode` carries the same marker so
that the pair stays opt-in together.

### Converting a JsonElement tree to a YamlNode tree

The same artifact converts the other way with `toYamlNode()`.

```kotlin
val json = Json.parseToJsonElement("""{"name":"123","port":8080}""")

val node = json.toYamlNode()

println(node.yamlMap.getScalar("port")!!.content) // 8080
```

This direction always succeeds. A JSON string becomes a quoted scalar, so it stays a string, and a JSON
number or boolean becomes a plain scalar carrying its exact original text. Converting a valid JSON tree
to YAML and back to JSON returns an equal JSON tree. Starting from YAML can normalize scalar text:
for example, `007` becomes `7` after conversion to JSON and back.

Nodes built this way are given the location line 1, column 1, because there is no source document behind
them. Their paths identify a position in the tree, not in any text.

## Contributing to kotaml

Pull requests and bug reports are always welcome!

kotaml uses Gradle for builds and testing:

* To build the library: `./gradlew assemble`
* To run the tests and static analysis tools: `./gradlew check`
* To run the tests and static analysis tools continuously: `./gradlew --continuous check`

## Reference links

* [YAML 1.2 Specification](http://yaml.org/spec/1.2/spec.html)
* [snakeyaml-engine-kmp](https://github.com/krzema12/snakeyaml-engine-kmp), a Kotlin Multiplatform port of [snakeyaml-engine](https://bitbucket.org/snakeyaml/snakeyaml-engine/wiki/Home), the YAML parser this library is based on
