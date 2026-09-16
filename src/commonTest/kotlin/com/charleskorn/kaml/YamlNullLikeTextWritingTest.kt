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

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

class YamlNullLikeTextWritingTest :
    FlatFunSpec({
        val spellings =
            mapOf(
                NullLikeKind.Lowercase to "null",
                NullLikeKind.Null to "Null",
                NullLikeKind.NULL to "NULL",
                NullLikeKind.Tilde to "~",
                NullLikeKind.Empty to "",
            )

        for (spelling in spellings.values) {
            test("distinguishes the plain null '$spelling' from the same text written as a string") {
                Yaml.default
                    .parseToYamlNode("value: $spelling")
                    .yamlMap
                    .get<YamlNode>("value")
                    .shouldBeInstanceOf<YamlNull>()

                for (style in listOf(SingleLineStringStyle.Plain, SingleLineStringStyle.PlainExceptAmbiguous)) {
                    val yaml = Yaml(configuration = YamlConfiguration(singleLineStringStyle = style))
                    val value = mapOf("value" to spelling)
                    val output = yaml.encodeToString(value)

                    Yaml.default.decodeFromString<Map<String, String?>>(output) shouldBe value
                }
            }
        }

        SingleLineStringStyle.entries.forEach { style ->
            AmbiguousQuoteStyle.entries.forEach { quoteStyle ->
                val yaml = Yaml(configuration = YamlConfiguration(singleLineStringStyle = style, ambiguousQuoteStyle = quoteStyle))
                val quote =
                    when (style) {
                        SingleLineStringStyle.SingleQuoted -> "'"
                        SingleLineStringStyle.DoubleQuoted -> "\""
                        else -> if (quoteStyle == AmbiguousQuoteStyle.SingleQuoted) "'" else "\""
                    }

                test("round trips null-like enum values and enum map keys with $style and $quoteStyle") {
                    for ((kind, spelling) in spellings) {
                        val value = EnumProperty(kind)
                        val output = yaml.encodeToString(value)
                        output shouldBe "kind: $quote$spelling$quote"
                        Yaml.default.decodeFromString<EnumProperty>(output) shouldBe value

                        val nullable = NullableEnumProperty(kind)
                        Yaml.default.decodeFromString<NullableEnumProperty>(yaml.encodeToString(nullable)) shouldBe nullable

                        val map = mapOf(kind to 1)
                        val mapOutput = yaml.encodeToString(map)
                        Yaml.default.decodeFromString<Map<NullLikeKind, Int>>(mapOutput) shouldBe map
                        mapOutput shouldBe if (spelling.isEmpty()) "? $quote$quote\n: 1" else "$quote$spelling$quote: 1"
                    }
                }

                test("round trips null-like string values and string map keys with $style and $quoteStyle") {
                    for (spelling in spellings.values) {
                        val map = mapOf(spelling to spelling)
                        val output = yaml.encodeToString(map)
                        Yaml.default.decodeFromString<Map<String, String>>(output) shouldBe map
                        Yaml.default.decodeFromString<Map<String, String?>>(output) shouldBe map
                        output shouldBe if (spelling.isEmpty()) "? $quote$quote\n: $quote$quote" else "$quote$spelling$quote: $quote$spelling$quote"

                        val scalarOutput = yaml.encodeToString(spelling)
                        Yaml.default.decodeFromString<String>(scalarOutput) shouldBe spelling
                        Yaml.default.decodeFromString<String?>(scalarOutput) shouldBe spelling
                    }
                }

                test("round trips a null-like character as a value and a map key with $style and $quoteStyle") {
                    val output = yaml.encodeToString(mapOf('~' to '~'))
                    output shouldBe "$quote~$quote: $quote~$quote"
                    Yaml.default.decodeFromString<Map<Char, Char>>(output) shouldBe mapOf('~' to '~')
                }

                test("round trips null-like YamlNode text with $style and $quoteStyle") {
                    val node = Yaml.default.parseToYamlNode("\"NULL\": \"Null\"")
                    val output = yaml.encodeToString(YamlNode.serializer(), node)
                    output shouldBe "${quote}NULL$quote: ${quote}Null$quote"
                    Yaml.default.parseToYamlNode(output).equivalentContentTo(node) shouldBe true
                }
            }
        }

        test("keeps real nulls distinct from null-like enum and string values") {
            val yaml = Yaml(configuration = YamlConfiguration(singleLineStringStyle = SingleLineStringStyle.Plain))
            val value = NullableEnumProperty(null)
            yaml.encodeToString(value) shouldBe "kind: null"
            Yaml.default.decodeFromString<NullableEnumProperty>(yaml.encodeToString(value)) shouldBe value
            val strings = listOf("NULL", null, "Null", "null", "~", "")
            Yaml.default.decodeFromString<List<String?>>(yaml.encodeToString(strings)) shouldBe strings
        }

        test("uses PlainExceptAmbiguous quoting for numeric and boolean enum names and characters") {
            val yaml = Yaml(configuration = YamlConfiguration(singleLineStringStyle = SingleLineStringStyle.PlainExceptAmbiguous, ambiguousQuoteStyle = AmbiguousQuoteStyle.SingleQuoted))
            yaml.encodeToString(OtherKind.Number) shouldBe "'123'"
            yaml.encodeToString(OtherKind.Boolean) shouldBe "'true'"
            yaml.encodeToString(OtherKind.Ordinary) shouldBe "Ordinary"
            yaml.encodeToString('1') shouldBe "'1'"
            yaml.encodeToString('A') shouldBe "A"
        }
    })

@Serializable
private enum class NullLikeKind {
    @SerialName("null")
    Lowercase,
    Null,
    NULL,

    @SerialName("~")
    Tilde,

    @SerialName("")
    Empty,
}

@Serializable
private enum class OtherKind {
    @SerialName("123")
    Number,

    @SerialName("true")
    Boolean,
    Ordinary,
}

@Serializable
private data class EnumProperty(
    val kind: NullLikeKind,
)

@Serializable
private data class NullableEnumProperty(
    val kind: NullLikeKind?,
)
