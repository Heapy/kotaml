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
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

class YamlMergeKeyReadingTest :
    FlatFunSpec({
        val literalKeys = listOf("'<<'", "\"<<\"", "!!str <<", "!<tag:yaml.org,2002:str> <<", "! <<")
        val yaml = Yaml.default
        for (key in literalKeys) {
            for ((layout, input) in listOf("block" to "$key: 1", "flow" to "{$key: 1}")) {
                test("reads and round trips $key as a literal $layout key") {
                    val expected = mapOf("<<" to 1)
                    yaml.decodeFromString<Map<String, Int>>(input) shouldBe expected
                    val node = yaml.parseToYamlNode(input).yamlMap
                    node.entries.keys
                        .single()
                        .plain shouldBe false
                    val value = node.entries.values.single()
                    value.path.toHumanReadableString() shouldBe "<<"
                    value.path.segments.any { it is YamlPathSegment.Merge } shouldBe false

                    val output = yaml.encodeToString(YamlNode.serializer(), node)
                    yaml.decodeFromString<Map<String, Int>>(output) shouldBe expected
                    yaml.parseToYamlNode(output).equivalentContentTo(node) shouldBe true
                    yaml.decodeFromString<Map<String, Int>>(yaml.encodeToString(expected)) shouldBe expected
                }
            }

            test("preserves a map value under $key") {
                val input = "$key: {nested: 1}"
                val expected = mapOf("<<" to mapOf("nested" to 1))
                yaml.decodeFromString<Map<String, Map<String, Int>>>(input) shouldBe expected
                val node = yaml.parseToYamlNode(input)
                val output = yaml.encodeToString(YamlNode.serializer(), node)
                yaml.decodeFromString<Map<String, Map<String, Int>>>(output) shouldBe expected
            }

            for (literalFirst in listOf(true, false)) {
                test("keeps $key beside a plain merge with literalFirst=$literalFirst") {
                    val entries = listOf("$key: 1", "<<: {inherited: 2}")
                    val input = (if (literalFirst) entries else entries.reversed()).joinToString("\n")
                    yaml.decodeFromString<Map<String, Int>>(input) shouldBe mapOf("<<" to 1, "inherited" to 2)
                }
            }
        }

        test("retains validation and the merge path for a plain merge key") {
            val exception = shouldThrow<MalformedYamlException> { yaml.parseToYamlNode("<<: 1") }
            exception.message shouldBe "Cannot merge a scalar value into a map."
            exception.path shouldBe YamlPath.root.withMerge(Location(1, 5))
        }

        test("retains plain alias merges and explicit entry precedence") {
            val yamlWithAliases =
                Yaml(configuration = YamlConfiguration(anchorsAndAliases = AnchorsAndAliases.Permitted()))
            val input = "- &base {inherited: 1, overridden: 2}\n- <<: *base\n  overridden: 3"
            yamlWithAliases.decodeFromString<List<Map<String, Int>>>(input) shouldBe
                listOf(mapOf("inherited" to 1, "overridden" to 2), mapOf("inherited" to 1, "overridden" to 3))
        }

        test("continues to reject unsupported explicit merge tags") {
            for (key in listOf("!!merge <<", "!!merge '<<'", "!<tag:yaml.org,2002:merge> <<")) {
                val exception = shouldThrow<MalformedYamlException> { Yaml.default.parseToYamlNode("$key: {nested: 1}") }
                exception.message shouldBe "Only !!str and ! tags are supported on property names."
            }
        }
    })
