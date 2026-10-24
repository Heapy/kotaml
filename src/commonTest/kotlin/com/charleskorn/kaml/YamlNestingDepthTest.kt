/*

   Copyright 2018-2023 Charles Korn.
   Copyright 2026 Ruslan Ibrahimau.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       https://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

*/

package com.charleskorn.kaml

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString

class YamlNestingDepthTest :
    FlatFunSpec({
        for (shape in listOf("sequence", "mapping", "mixed")) {
            for (limit in listOf(1, 4, 12)) {
                val yaml = Yaml(configuration = YamlConfiguration(maxNestingDepth = limit))
                test("accepts $shape nesting at configured depth $limit") {
                    yaml.parseToYamlNode(nestedDocument(limit, shape)).contentToString().isNotEmpty() shouldBe true
                }
                test("rejects $shape nesting at configured depth $limit plus one") {
                    val error = shouldThrow<MalformedYamlException> { yaml.parseToYamlNode(nestedDocument(limit + 1, shape)) }
                    error.message shouldBe "Maximum YAML nesting depth of $limit exceeded."
                    error.line shouldBe 1
                }
            }
            test("rejects depth 10000 $shape deterministically with the default limit") {
                val document = nestedDocument(10000, shape)
                val error = shouldThrow<MalformedYamlException> { Yaml.default.parseToYamlNode(document) }
                error.message shouldBe "Maximum YAML nesting depth of 50 exceeded."
            }
        }
        test("reports the first excess collection exact path and coordinates") {
            val yaml = Yaml(configuration = YamlConfiguration(maxNestingDepth = 2))
            val error = shouldThrow<MalformedYamlException> { yaml.parseToYamlNode("items:\n  - [value]") }
            error.path.toHumanReadableString() shouldBe "items[0]"
            error.location shouldBe Location(2, 5)
        }
        test("supports zero for scalar-only input, including null and tagged scalar") {
            val yaml = Yaml(configuration = YamlConfiguration(maxNestingDepth = 0))
            yaml.decodeFromString<String>("value") shouldBe "value"
            yaml.decodeFromString<String?>("null") shouldBe null
            yaml.decodeFromString<String>("!custom value") shouldBe "value"
            for (document in listOf("[]", "{}", "!custom []", "!custom {}")) {
                shouldThrow<MalformedYamlException> { yaml.parseToYamlNode(document) }
            }
        }
        test("rejects negative configuration, including copy") {
            shouldThrow<IllegalArgumentException> { YamlConfiguration(maxNestingDepth = -1) }
            shouldThrow<IllegalArgumentException> { YamlConfiguration().copy(maxNestingDepth = -1) }
        }
        test("resets depth between siblings rather than counting collections cumulatively") {
            val yaml = Yaml(configuration = YamlConfiguration(maxNestingDepth = 2))
            yaml.decodeFromString<List<List<String>>>("[[one], [two], [three]]") shouldBe listOf(listOf("one"), listOf("two"), listOf("three"))
        }
        test("typed decoding enforces depth before deserialization") {
            val yaml = Yaml(configuration = YamlConfiguration(maxNestingDepth = 2))
            shouldThrow<MalformedYamlException> { yaml.decodeFromString<List<List<String>>>("[[[value]]]") }
        }
        test("accepts default limit boundary") {
            Yaml.default
                .parseToYamlNode(nestedDocument(50, "mixed"))
                .contentToString()
                .isNotEmpty() shouldBe true
        }
        for (depth in listOf(49, 50)) {
            test("decodes a recursive model at depth $depth with the default limit") {
                val document = "{child: ".repeat(depth) + "null" + "}".repeat(depth)
                val decoded = Yaml.default.decodeFromString<DepthRecursiveModel>(document)
                generateSequence(decoded) { it.child }.count() shouldBe depth
            }
        }
        test("rejects a recursive model beyond the default limit") {
            val document = "{child: ".repeat(51) + "null" + "}".repeat(51)
            shouldThrow<MalformedYamlException> { Yaml.default.decodeFromString<DepthRecursiveModel>(document) }
                .message shouldBe "Maximum YAML nesting depth of 50 exceeded."
        }
        test("counts both models and lists in recursive typed decoding") {
            val document = "{children: [".repeat(25) + "null" + "]}".repeat(25)
            val decoded = Yaml.default.decodeFromString<DepthRecursiveList>(document)
            generateSequence(decoded) { it.children.singleOrNull() }.count() shouldBe 25
            shouldThrow<MalformedYamlException> {
                Yaml.default.decodeFromString<DepthRecursiveList>("{children: [$document]}")
            }
        }
        test("a YamlNode property includes its enclosing model in the depth") {
            val yaml = Yaml(configuration = YamlConfiguration(maxNestingDepth = 2))
            yaml
                .decodeFromString<DepthNodeModel>("node: [value]")
                .node.yamlList.items
                .single()
                .yamlScalar.content shouldBe "value"
            shouldThrow<MalformedYamlException> { yaml.decodeFromString<DepthNodeModel>("node: [[value]]") }
        }
        test("ignored fields are subject to the same depth limit") {
            val yaml = Yaml(configuration = YamlConfiguration(maxNestingDepth = 2, strictMode = false))
            yaml.decodeFromString<DepthRecursiveModel>("unknown: [value]").child shouldBe null
            shouldThrow<MalformedYamlException> { yaml.decodeFromString<DepthRecursiveModel>("unknown: [[value]]") }
        }
        test("tree and typed map decoding agree on collection depth") {
            val document = "{a: {b: 1}}"
            val yaml = Yaml(configuration = YamlConfiguration(maxNestingDepth = 2))
            yaml
                .parseToYamlNode(document)
                .yamlMap.entries.size shouldBe 1
            yaml.decodeFromString<Map<String, Map<String, Int>>>(document) shouldBe mapOf("a" to mapOf("b" to 1))
            val shallowYaml = Yaml(configuration = YamlConfiguration(maxNestingDepth = 1))
            shouldThrow<MalformedYamlException> { shallowYaml.parseToYamlNode(document) }
            shouldThrow<MalformedYamlException> { shallowYaml.decodeFromString<Map<String, Map<String, Int>>>(document) }
        }
        test("aliases cannot grow a tree beyond the depth limit before path copying") {
            val yaml = Yaml(configuration = YamlConfiguration(maxNestingDepth = 3, anchorsAndAliases = AnchorsAndAliases.Permitted(maxAliasCount = null)))
            val prefix = "a: &a [value]\nb: &b [*a]\n"
            yaml
                .parseToYamlNode(prefix)
                .yamlMap.entries.size shouldBe 2
            val error = shouldThrow<MalformedYamlException> { yaml.parseToYamlNode(prefix + "c: &c [*b]\n") }
            error.message.shouldContain("depth of 3")
            error.path.toHumanReadableString() shouldBe "c[0]"
            error.location shouldBe Location(3, 8)
        }
        test("merge syntax is subject to the same collection depth limit") {
            val configuration = YamlConfiguration(maxNestingDepth = 3, anchorsAndAliases = AnchorsAndAliases.Permitted())
            val document = "a: &a {key: value}\nb: {<<: *a}"
            Yaml(configuration = configuration)
                .parseToYamlNode(document)
                .yamlMap.entries.size shouldBe 2
            shouldThrow<MalformedYamlException> {
                Yaml(configuration = configuration.copy(maxNestingDepth = 2)).parseToYamlNode(document)
            }
        }
        for (document in listOf("a:\n  b:\n    c: value", "-\n  -\n    - value")) {
            test("enforces the limit for block collections in '$document'") {
                Yaml(configuration = YamlConfiguration(maxNestingDepth = 3)).parseToYamlNode(document)
                shouldThrow<MalformedYamlException> { Yaml(configuration = YamlConfiguration(maxNestingDepth = 2)).parseToYamlNode(document) }
            }
        }
    })

private fun nestedDocument(
    depth: Int,
    shape: String,
): String =
    buildString {
        for (index in 0 until depth) append(if (shape == "sequence" || (shape == "mixed" && index % 2 == 0)) "[" else "{key: ")
        append("value")
        for (index in depth - 1 downTo 0) append(if (shape == "sequence" || (shape == "mixed" && index % 2 == 0)) "]" else "}")
    }

@Serializable
private data class DepthRecursiveModel(
    val child: DepthRecursiveModel? = null,
)

@Serializable
private data class DepthRecursiveList(
    val children: List<DepthRecursiveList?>,
)

@Serializable
private data class DepthNodeModel(
    val node: YamlNode,
)
