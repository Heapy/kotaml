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

class YamlMergeIndexContractTest :
    FlatFunSpec({
        val yaml = Yaml(configuration = YamlConfiguration(anchorsAndAliases = AnchorsAndAliases.Permitted()))
        val forms = listOf("key", "'key'", "\"key\"", "!!str key", "! key")
        for (sourceKey in forms) {
            for (localFirst in listOf(true, false)) {
                test("local key wins over $sourceKey with localFirst=$localFirst") {
                    val entries = listOf("key: local", "<<: {$sourceKey: source, inherited: value}")
                    val node = yaml.parseToYamlNode((if (localFirst) entries else entries.reversed()).joinToString("\n")).yamlMap
                    node.getScalar("key")?.content shouldBe "local"
                    node.entries.keys.map { it.content } shouldBe listOf("key", "inherited")
                    node.getKey("key")?.plain shouldBe true
                    node.getKey("key")?.path?.endLocation shouldBe Location(if (localFirst) 1 else 2, 1)
                }
            }
            test("first source wins and retains key metadata for $sourceKey") {
                val input = "- &base {$sourceKey: first, inherited: value}\n- <<: [*base, {key: second, extra: value}]\n  local: value"
                val nodes = yaml.parseToYamlNode(input).yamlList.items
                val sourceKeyNode = nodes[0].yamlMap.getKey("key")!!
                val result = nodes[1].yamlMap
                result.getScalar("key")?.content shouldBe "first"
                result.entries.keys.map { it.content } shouldBe listOf("local", "key", "inherited", "extra")
                val expectedPath =
                    YamlPath.root
                        .withListEntry(1, Location(2, 3))
                        .withMerge(Location(2, 7))
                        .withListEntry(0, Location(2, 8))
                        .withAliasReference("base", Location(2, 8))
                        .withAliasDefinition("base", Location(1, 3))
                        .withMapElementKey("key", sourceKeyNode.path.endLocation)
                result.getKey("key") shouldBe sourceKeyNode.withPath(expectedPath)
            }
        }
        test("textually different numeric and boolean keys stay distinct") {
            val node = yaml.parseToYamlNode("<<: [{001: zeroes, 1: decimal, TRUE: upper}, {0x1: hex, true: lower}]").yamlMap
            node.entries.map { it.key.content to it.value.yamlScalar.content } shouldBe
                listOf("001" to "zeroes", "1" to "decimal", "TRUE" to "upper", "0x1" to "hex", "true" to "lower")
        }
        test("duplicate local keys still fail when merged source has the same key") {
            val error = shouldThrow<DuplicateKeyException> { yaml.parseToYamlNode("key: first\n'key': second\n<<: {key: source}") }
            error.originalLocation shouldBe Location(1, 1)
            error.duplicateLocation shouldBe Location(2, 1)
        }
        test("duplicate keys in an anchored source still fail before merging") {
            val error = shouldThrow<DuplicateKeyException> { yaml.parseToYamlNode("base: &base {key: first, 'key': second}\nresult: {<<: *base}") }
            error.originalLocation shouldBe Location(1, 14)
            error.duplicateLocation shouldBe Location(1, 26)
        }
        test("quoted merge text from a source stays a literal key") {
            val node = yaml.parseToYamlNode("<<: [{'<<': literal}, {'<<': ignored}]").yamlMap
            node.getScalar("<<")?.content shouldBe "literal"
            node.getKey("<<")?.plain shouldBe false
        }
    })
