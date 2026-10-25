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
import okio.Buffer

class YamlCollectionTagReadingTest :
    FlatFunSpec({
        for ((tag, kind) in listOf("seq" to "sequence", "map" to "mapping")) {
            for (value in listOf("hello", "'hello'", "null", "", if (tag == "seq") "{}" else "[]")) {
                for (shape in listOf("root", "list", "map")) {
                    for (api in listOf("node", "typed string", "typed source")) {
                        test("rejects !!$tag $value in $shape via $api") {
                            val source = "!!$tag $value"
                            val document =
                                when (shape) {
                                    "root" -> source
                                    "list" -> "- $source"
                                    else -> "key: $source"
                                }
                            val location =
                                when (shape) {
                                    "root" -> Location(1, 1)
                                    "list" -> Location(1, 3)
                                    else -> Location(1, 6)
                                }
                            val path =
                                when (shape) {
                                    "root" -> YamlPath.root
                                    "list" -> YamlPath.root.withListEntry(0, location)
                                    else -> YamlPath.root.withMapElementKey("key", Location(1, 1)).withMapElementValue(location)
                                }
                            val error =
                                shouldThrow<MalformedYamlException> {
                                    when (api) {
                                        "node" -> {
                                            Yaml.default.parseToYamlNode(document)
                                        }

                                        "typed string" -> {
                                            when (shape) {
                                                "root" -> Yaml.default.decodeFromString<String>(document)
                                                "list" -> Yaml.default.decodeFromString<List<String>>(document)
                                                else -> Yaml.default.decodeFromString<Map<String, String>>(document)
                                            }
                                        }

                                        else -> {
                                            val sourceBuffer = Buffer().writeUtf8(document)
                                            when (shape) {
                                                "root" -> Yaml.default.decodeFromSource<String>(sourceBuffer)
                                                "list" -> Yaml.default.decodeFromSource<List<String>>(sourceBuffer)
                                                else -> Yaml.default.decodeFromSource<Map<String, String>>(sourceBuffer)
                                            }
                                        }
                                    }
                                }
                            error.message shouldBe "Tag '!!$tag' requires a $kind value."
                            error.path shouldBe path.withError(location)
                            error.location shouldBe location
                        }
                    }
                }
            }
            for ((document, location) in listOf(
                "!<tag:yaml.org,2002:$tag> hello" to Location(1, 1),
                "%TAG !c! tag:yaml.org,2002:\n---\n!c!$tag hello" to Location(3, 1),
                "%YAML 1.2\n---\n!!$tag hello" to Location(3, 1),
            )) {
                test("validates expanded tag and its actual root location in $document") {
                    val error = shouldThrow<MalformedYamlException> { Yaml.default.parseToYamlNode(document) }
                    error.message shouldBe "Tag '!!$tag' requires a $kind value."
                    error.path shouldBe YamlPath.root.withError(location)
                }
            }
            test("rejects !!$tag on a scalar mapping key") {
                val error = shouldThrow<MalformedYamlException> { Yaml.default.parseToYamlNode("!!$tag key: value") }
                error.message shouldBe "Tag '!!$tag' requires a $kind value."
                error.location shouldBe Location(1, 1)
            }
            test("legacy scalar compatibility does not allow mismatched !!$tag") {
                val yaml = Yaml(configuration = YamlConfiguration(readCompatibility = YamlReadCompatibility.LegacyV0_110()))
                shouldThrow<MalformedYamlException> { yaml.parseToYamlNode("!!$tag hello") }
            }
            test("validates !!$tag even in an ignored object property") {
                val yaml = Yaml(configuration = YamlConfiguration(strictMode = false))
                shouldThrow<MalformedYamlException> { yaml.decodeFromString<CollectionTagProperty>("value: ok\nignored: !!$tag hello") }
            }
            test("local !$tag remains a custom tag") {
                val node = Yaml.default.parseToYamlNode("!$tag hello").shouldBeInstanceOf<YamlTaggedNode>()
                node.tag shouldBe "!$tag"
                node.innerNode.shouldBeInstanceOf<YamlScalar>().content shouldBe "hello"
                Yaml.default.decodeFromString<String>("!$tag hello") shouldBe "hello"
            }
        }
        for (source in listOf("!!seq []", "!!seq [one, two]", "!!seq\n- one\n- two")) {
            test("preserves a valid sequence tag and typed list for $source") {
                val node = Yaml.default.parseToYamlNode(source).shouldBeInstanceOf<YamlTaggedNode>()
                node.tag shouldBe "tag:yaml.org,2002:seq"
                val expected = if (source.endsWith("[]")) emptyList() else listOf("one", "two")
                node.innerNode
                    .shouldBeInstanceOf<YamlList>()
                    .items
                    .map { it.yamlScalar.content } shouldBe expected
                Yaml.default.decodeFromString<List<String>>(source) shouldBe expected
            }
        }
        for (source in listOf("!!map {}", "!!map {key: value}", "!!map\nkey: value")) {
            test("preserves a valid mapping tag and typed map for $source") {
                val node = Yaml.default.parseToYamlNode(source).shouldBeInstanceOf<YamlTaggedNode>()
                node.tag shouldBe "tag:yaml.org,2002:map"
                node.innerNode.shouldBeInstanceOf<YamlMap>()
                val expected = if (source.endsWith("{}")) emptyMap() else mapOf("key" to "value")
                Yaml.default.decodeFromString<Map<String, String>>(source) shouldBe expected
            }
        }
        test("reads nested standard collection tags") {
            Yaml.default.decodeFromString<List<Map<String, List<Int>>>>("!!seq [!!map {items: !!seq [1, 2]}]") shouldBe
                listOf(mapOf("items" to listOf(1, 2)))
        }
        test("reports the nested mismatched collection rather than an outer wrapper") {
            val error = shouldThrow<MalformedYamlException> { Yaml.default.parseToYamlNode("!!seq [!!map hello]") }
            val location = Location(1, 8)
            error.path shouldBe YamlPath.root.withListEntry(0, location).withError(location)
        }
        test("validates collection kind before reading its children") {
            val error = shouldThrow<MalformedYamlException> { Yaml.default.parseToYamlNode("!!seq {key: [}") }
            error.message shouldBe "Tag '!!seq' requires a sequence value."
        }
        test("hand-built tagged nodes remain available without reader validation") {
            val node = YamlTaggedNode("tag:yaml.org,2002:seq", YamlScalar("hello", YamlPath.root))
            node.innerNode.yamlScalar.content shouldBe "hello"
            Yaml.default.decodeFromYamlNode<String>(node) shouldBe "hello"
        }
    })

@Serializable
private data class CollectionTagProperty(
    val value: String,
)
