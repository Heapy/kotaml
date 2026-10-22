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

class YamlEmptyMapKeyReadingTest :
    FlatFunSpec({
        listOf(
            Triple("block", "? \n: 1", Location(1, 2)),
            Triple("flow", "{? : 1}", Location(1, 3)),
        ).forEach { (style, input, location) ->
            test("rejects an empty plain $style key in strict mode") {
                val exception = shouldThrow<MalformedYamlException> { Yaml.default.parseToYamlNode(input) }

                exception.message shouldBe "Property name must not be null. (To use an empty string as a property name, write \"\".)"
                exception.path shouldBe YamlPath.root.withError(location)
            }

            test("reads and migrates an empty plain $style key with compatibility") {
                val issues = mutableListOf<YamlReadCompatibilityIssue>()
                val yaml = Yaml(configuration = YamlConfiguration(readCompatibility = YamlReadCompatibility.LegacyV0_110(issues::add)))
                val expectedIssue =
                    YamlReadCompatibilityIssue(
                        YamlReadCompatibilityReason.LegacyNullKey,
                        YamlPath.root.withMapElementKey("", location),
                        "",
                        null,
                        "\"\"",
                    )

                val value = yaml.decodeFromString<Map<String, Int>>(input)
                value shouldBe mapOf("" to 1)
                issues shouldBe listOf(expectedIssue)
                Yaml.default.decodeFromString<Map<String, Int>>("? ${expectedIssue.replacement}\n: 1") shouldBe value

                val output = yaml.encodeToString(value)
                Yaml.default.decodeFromString<Map<String, Int>>(output) shouldBe value
                yaml.decodeFromString<Map<String, Int>>(output) shouldBe value
                issues shouldBe listOf(expectedIssue)

                issues.clear()
                val node = yaml.parseToYamlNode(input).yamlMap
                node.entries.keys
                    .single()
                    .content shouldBe ""
                node.entries.keys
                    .single()
                    .plain shouldBe true
                issues shouldBe listOf(expectedIssue)
                Yaml.default.decodeFromString<Map<String, Int>>(yaml.encodeToString(YamlNode.serializer(), node)) shouldBe value
            }
        }

        test("reports the path and location of an empty key in a nested mapping") {
            val input = "outer:\n  ?\n  : 1"
            val parentPath = YamlPath.root.withMapElementKey("outer", Location(1, 1)).withMapElementValue(Location(2, 3))
            val location = Location(2, 4)
            val exception = shouldThrow<MalformedYamlException> { Yaml.default.parseToYamlNode(input) }
            exception.message shouldBe "Property name must not be null. (To use an empty string as a property name, write \"\".)"
            exception.path shouldBe parentPath.withError(location)

            val issues = mutableListOf<YamlReadCompatibilityIssue>()
            val yaml = Yaml(configuration = YamlConfiguration(readCompatibility = YamlReadCompatibility.LegacyV0_110(issues::add)))
            yaml.decodeFromString<Map<String, Map<String, Int>>>(input) shouldBe mapOf("outer" to mapOf("" to 1))
            issues.single().path shouldBe parentPath.withMapElementKey("", location)
            issues.single().location shouldBe location
            issues.single().reason shouldBe YamlReadCompatibilityReason.LegacyNullKey
        }

        test("reports the path and location of an empty key inside a sequence") {
            val input = "- ?\n  : 1"
            val parentPath = YamlPath.root.withListEntry(0, Location(1, 3))
            val location = Location(1, 4)
            val exception = shouldThrow<MalformedYamlException> { Yaml.default.parseToYamlNode(input) }
            exception.message shouldBe "Property name must not be null. (To use an empty string as a property name, write \"\".)"
            exception.path shouldBe parentPath.withError(location)

            val issues = mutableListOf<YamlReadCompatibilityIssue>()
            val yaml = Yaml(configuration = YamlConfiguration(readCompatibility = YamlReadCompatibility.LegacyV0_110(issues::add)))
            yaml.decodeFromString<List<Map<String, Int>>>(input) shouldBe listOf(mapOf("" to 1))
            issues.single().path shouldBe parentPath.withMapElementKey("", location)
            issues.single().location shouldBe location
            issues.single().reason shouldBe YamlReadCompatibilityReason.LegacyNullKey
        }

        val modes =
            listOf(
                "strict" to YamlReadCompatibility.Strict,
                "legacy" to YamlReadCompatibility.LegacyV0_110 { error("Unexpected compatibility event: $it") },
            )
        for ((mode, compatibility) in modes) {
            val yaml = Yaml(configuration = YamlConfiguration(readCompatibility = compatibility))

            for (key in listOf("\"\"", "''", "!!str", "!", "!<tag:yaml.org,2002:str>")) {
                test("reads the explicit empty string key '$key' without compatibility in $mode mode") {
                    val input = "? $key\n: 1"
                    yaml.decodeFromString<Map<String, Int>>(input) shouldBe mapOf("" to 1)
                    yaml
                        .parseToYamlNode(input)
                        .yamlMap.entries.keys
                        .single()
                        .plain shouldBe false
                }
            }

            test("keeps empty mapping and sequence values null in $mode mode") {
                yaml.decodeFromString<Map<String, String?>>("value:") shouldBe mapOf("value" to null)
                yaml.decodeFromString<List<String?>>("-") shouldBe listOf(null)
            }
        }
    })
