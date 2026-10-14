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
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

class YamlStringTagReadingTest :
    FlatFunSpec({
        val stringTags = listOf("!!str", "!<tag:yaml.org,2002:str>", "!")
        val modes =
            listOf(
                "strict" to YamlReadCompatibility.Strict,
                "legacy" to YamlReadCompatibility.LegacyV0_110 { error("Explicit string tags must not use compatibility: $it") },
            )
        for ((mode, compatibility) in modes) {
            val yaml = Yaml(configuration = YamlConfiguration(readCompatibility = compatibility))
            for (tag in stringTags) {
                for (text in listOf("null", "Null", "NULL", "~", "")) {
                    test("reads '$tag $text' as a string in $mode mode") {
                        val input = "$tag $text"
                        yaml.decodeFromString<String>(input) shouldBe text
                        yaml.decodeFromString<String?>(input) shouldBe text
                        val node = yaml.parseToYamlNode(input).shouldBeInstanceOf<YamlTaggedNode>()
                        node.tag shouldBe if (tag == "!") "!" else "tag:yaml.org,2002:str"
                        node.innerNode.shouldBeInstanceOf<YamlScalar>().content shouldBe text
                        node.innerNode.yamlScalar.plain shouldBe false
                    }
                }

                test("reads '$tag' keys as strings in $mode mode") {
                    for (text in listOf("null", "Null", "NULL", "~", "", "0x11")) {
                        val input = "$tag $text: 1"
                        yaml.decodeFromString<Map<String, Int>>(input) shouldBe mapOf(text to 1)
                        val node = yaml.parseToYamlNode(input).yamlMap
                        node.entries.keys
                            .single()
                            .plain shouldBe false
                        val output = yaml.encodeToString(YamlNode.serializer(), node)
                        Yaml.default.decodeFromString<Map<String, Int>>(output) shouldBe mapOf(text to 1)
                    }
                }
            }
        }

        for (style in SingleLineStringStyle.entries) {
            test("preserves explicitly tagged string text when reserializing with $style") {
                val yaml = Yaml(configuration = YamlConfiguration(singleLineStringStyle = style))
                for (text in listOf("NULL", "Null", "null", "~", "", "True", "0x11", "+07", ".INF", "1e400")) {
                    val node = yaml.parseToYamlNode("!!str $text")
                    val output = yaml.encodeToString(YamlNode.serializer(), node)
                    val reparsed = yaml.parseToYamlNode(output).yamlTaggedNode
                    reparsed.tag shouldBe "tag:yaml.org,2002:str"
                    reparsed.innerNode.yamlScalar.content shouldBe text
                    reparsed.innerNode.yamlScalar.plain shouldBe false
                    yaml.decodeFromString<String>(output) shouldBe text
                }
            }
        }

        test("uses the expanded tag URI after a TAG directive") {
            val input = "%TAG !s! tag:yaml.org,2002:\n---\nvalue: !s!str NULL"
            Yaml.default.decodeFromString<TaggedStringProperty>(input) shouldBe TaggedStringProperty("NULL")
        }

        test("preserves an explicitly tagged string through anchors and aliases") {
            val yaml = Yaml(configuration = YamlConfiguration(anchorsAndAliases = AnchorsAndAliases.Permitted()))
            yaml.decodeFromString<List<String?>>("- &value !!str NULL\n- *value") shouldBe listOf("NULL", "NULL")
        }

        test("distinguishes plain nulls from explicitly tagged strings in nullable object properties") {
            Yaml.default.decodeFromString<TaggedStringProperty>("value: NULL") shouldBe TaggedStringProperty(null)
            Yaml.default.decodeFromString<TaggedStringProperty>("value: !!str NULL") shouldBe TaggedStringProperty("NULL")
            Yaml.default.decodeFromString<TaggedStringProperty>("value: !!str") shouldBe TaggedStringProperty("")
        }

        test("preserves existing custom-tagged null values and rejects other tagged map keys") {
            Yaml.default
                .parseToYamlNode("!thing")
                .yamlTaggedNode.innerNode
                .shouldBeInstanceOf<YamlNull>()
            Yaml.default
                .parseToYamlNode("!!null NULL")
                .yamlTaggedNode.innerNode
                .shouldBeInstanceOf<YamlNull>()
            for (input in listOf("!thing NULL: 1", "!!int 1: 2", "!!null NULL: 1", "? !thing\n: 1")) {
                val exception = shouldThrow<MalformedYamlException> { Yaml.default.parseToYamlNode(input) }
                exception.message shouldBe "Only !!str and ! tags are supported on property names."
            }
        }
    })

@Serializable
private data class TaggedStringProperty(
    val value: String?,
)
