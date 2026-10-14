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

package com.charleskorn.kaml.json

import com.charleskorn.kaml.AnchorsAndAliases
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlException
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNull
import com.charleskorn.kaml.YamlPath
import com.charleskorn.kaml.YamlScalar
import com.charleskorn.kaml.YamlTaggedNode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral

@OptIn(ExperimentalSerializationApi::class)
class YamlToJsonTest :
    FunSpec({
        fun convert(
            yaml: String,
            nonFiniteNumbers: NonFiniteNumbers = NonFiniteNumbers.THROW,
        ): String = Json.encodeToString(JsonElement.serializer(), Yaml.default.parseToYamlNode(yaml).toJsonElement(nonFiniteNumbers))

        test("converts a null node to JSON null") {
            YamlNull(YamlPath.root).toJsonElement() shouldBe JsonNull
        }

        listOf("null", "Null", "NULL", "~", "").forEach { content ->
            test("resolves the hand-built plain scalar '$content' as null, like the parser") {
                val scalar = YamlScalar(content, YamlPath.root)
                scalar.toJsonElement() shouldBe JsonNull
                val manual = YamlMap(mapOf(YamlScalar("value", YamlPath.root) to scalar), YamlPath.root)
                val parsed = Yaml.default.parseToYamlNode("value: $content")
                manual.toJsonElement() shouldBe parsed.toJsonElement()
            }

            test("keeps quoted null text '$content' as a string") {
                YamlScalar(content, YamlPath.root, plain = false).toJsonElement() shouldBe JsonPrimitive(content)
                convert("\"$content\"") shouldBe "\"$content\""
                convert("'$content'") shouldBe "\"$content\""
            }

            test("honors explicit types on null text '$content'") {
                val scalar = YamlScalar(content, YamlPath.root)
                YamlTaggedNode("tag:yaml.org,2002:str", scalar).toJsonElement() shouldBe JsonPrimitive(content)
                convert("!!str \"$content\"") shouldBe "\"$content\""
                listOf("int", "bool", "float").forEach { tag ->
                    shouldThrow<YamlException> {
                        YamlTaggedNode("tag:yaml.org,2002:$tag", scalar).toJsonElement()
                    }
                }
            }
        }

        listOf("nUlL", " null", "null ", " ", "~~").forEach { content ->
            test("keeps non-null plain text '$content' as a string") {
                YamlScalar(content, YamlPath.root).toJsonElement() shouldBe JsonPrimitive(content)
            }
        }

        test("resolves null values in a hand-built list but keeps map keys as strings") {
            val values = listOf("null", "Null", "NULL", "~", "")
            val entries =
                values.associate { content ->
                    val scalar = YamlScalar(content, YamlPath.root)
                    scalar to YamlList(listOf(scalar), YamlPath.root)
                }
            YamlMap(entries, YamlPath.root).toJsonElement() shouldBe
                JsonObject(values.associateWith { JsonArray(listOf(JsonNull)) })
        }

        // Plain scalars resolve with the YAML 1.2 core schema. The text on the right is the
        // encoded JSON, so it also proves the result is valid JSON.
        mapOf(
            "true" to "true",
            "True" to "true",
            "false" to "false",
            "123" to "123",
            "-123" to "-123",
            "007" to "7",
            "+7" to "7",
            "0x1F" to "31",
            "0o11" to "9",
            "-0x11" to "\"-0x11\"",
            "+0x11" to "\"+0x11\"",
            "-0o11" to "\"-0o11\"",
            "+0o7" to "\"+0o7\"",
            "1.5" to "1.5",
            "1e5" to "1e5",
            "0.25" to "0.25",
            "+1.5" to "1.5",
            ".5" to "0.5",
            "-.5" to "-0.5",
            "1." to "1",
            "007.5" to "7.5",
            "hello" to """"hello"""",
            "yes" to """"yes"""",
            "NO" to """"NO"""",
            "2026-08-30" to """"2026-08-30"""",
            "1_000" to """"1_000"""",
        ).forEach { (yaml, expected) ->
            test("converts the plain scalar $yaml to $expected") {
                convert(yaml) shouldBe expected
            }
        }

        // A quoted scalar without an explicit type tag stays a string.
        listOf("true", "123", "1.5", "0x11", ".inf").forEach { content ->
            test("""keeps the quoted scalar "$content" a JSON string""") {
                convert(""""$content"""") shouldBe """"$content""""
            }
        }

        test("keeps the exact text of a float JSON can carry") {
            convert("1.10") shouldBe "1.10"
        }

        test("keeps every digit of an integer too large for a long") {
            convert("123456789012345678901234") shouldBe "123456789012345678901234"
        }

        test("keeps a hexadecimal integer too large for a long as a string") {
            convert("0xFFFFFFFFFFFFFFFFFF") shouldBe """"0xFFFFFFFFFFFFFFFFFF""""
        }

        listOf(".inf", "-.inf", ".nan").forEach { content ->
            test("throws on $content by default") {
                val exception = shouldThrow<NonFiniteNumberException> { convert(content) }
                exception.content shouldBe content
            }

            test("keeps $content as a string when asked to") {
                convert(content, NonFiniteNumbers.AS_STRING) shouldBe """"$content""""
            }

            test("replaces $content with null when asked to") {
                convert(content, NonFiniteNumbers.AS_NULL) shouldBe "null"
            }
        }

        // An exponent JSON cannot hold is kept as written, the same as a huge integer, rather than
        // being rounded to infinity by a Double round trip.
        mapOf(
            "1e999" to "1e999",
            "+1e999" to "1e999",
            ".5e3" to "0.5e3",
            "1.e5" to "1e5",
        ).forEach { (yaml, expected) ->
            test("converts $yaml to $expected without going through a Double") {
                convert(yaml) shouldBe expected
            }
        }

        test("forces a string with the !!str tag") {
            convert("!!str 123") shouldBe """"123""""
        }

        test("forces a string with the !!str tag on an already quoted scalar") {
            convert("""!!str "123"""") shouldBe """"123""""
        }

        test("forces null with the !!null tag") {
            convert("!!null x") shouldBe "null"
        }

        // Explicit types apply to both plain and quoted scalars.
        mapOf(
            "!!int 0x11" to "17",
            "!!int 0o11" to "9",
            "!!int 123" to "123",
            "!!int +007" to "7",
            "!!int 123456789012345678901234" to "123456789012345678901234",
            "!!bool true" to "true",
            "!!bool FALSE" to "false",
            "!!float 1.5" to "1.5",
            "!!float 1.50" to "1.50",
            "!!float 123" to "123",
            "!!float .5" to "0.5",
            "!!float 1." to "1",
            "!!float 1.00E+003" to "1.00E+003",
        ).forEach { (yaml, expected) ->
            val (tag, content) = yaml.split(' ', limit = 2)
            listOf(content, "\"$content\"", "'$content'").forEach { scalar ->
                test("converts $tag $scalar to $expected") {
                    convert("$tag $scalar") shouldBe expected
                }
            }
        }

        listOf(
            "!!int hello",
            "!!int 1.5",
            "!!int true",
            "!!int .inf",
            "!!int 0x٣",
            "!!bool 123",
            "!!bool yes",
            "!!float hello",
            "!!float true",
            "!!float 0x11",
        ).forEach { yaml ->
            val (tag, content) = yaml.split(' ', limit = 2)
            listOf(content, "\"$content\"", "'$content'").forEach { scalar ->
                test("rejects $tag $scalar with the value path") {
                    val exception = shouldThrow<YamlException> { convert("value: $tag $scalar") }
                    exception.path.toHumanReadableString() shouldBe "value"
                    exception.message shouldBe "Value '$content' is not valid for tag '$tag'."
                }
            }
        }

        listOf("!!int", "!!bool", "!!float").forEach { tag ->
            listOf("[]", "{}", "null", "\"\"").forEach { value ->
                test("rejects $tag on $value") {
                    val exception = shouldThrow<YamlException> { convert("value: $tag $value") }
                    exception.path.toHumanReadableString() shouldBe "value"
                }
            }
        }

        listOf(".inf", "-.inf", ".nan").forEach { content ->
            listOf(content, "\"$content\"", "'$content'").forEach { scalar ->
                test("applies all non-finite policies to !!float $scalar") {
                    val yaml = "value: !!float $scalar"
                    val exception = shouldThrow<NonFiniteNumberException> { convert(yaml) }
                    exception.content shouldBe content
                    exception.path.toHumanReadableString() shouldBe "value"
                    convert(yaml, NonFiniteNumbers.AS_STRING) shouldBe """{"value":"$content"}"""
                    convert(yaml, NonFiniteNumbers.AS_NULL) shouldBe """{"value":null}"""
                }
            }
        }

        test("drops a tag it does not know and converts the inner node") {
            convert("!custom 123") shouldBe "123"
            YamlTaggedNode("!custom", YamlScalar("123", YamlPath.root)).toJsonElement() shouldBe JsonPrimitive(123L)
        }

        test("resolves a hand-built scalar the same way as a parsed one") {
            // The plain flag defaults to true on the constructor.
            YamlScalar("123", YamlPath.root).toJsonElement() shouldBe JsonUnquotedLiteral("123")
        }

        test("never type-resolves map keys") {
            val yaml =
                """
                true: a
                123: b
                1.10: c
                "456": d
                null-ish: e
                """.trimIndent()

            convert(yaml) shouldBe """{"true":"a","123":"b","1.10":"c","456":"d","null-ish":"e"}"""
        }

        test("converts a whole document") {
            val yaml =
                """
                name: "123"
                count: 3
                ratio: 1.10
                enabled: true
                nothing: null
                items:
                  - a
                  - 2
                  - nested:
                      key: value
                """.trimIndent()

            convert(yaml) shouldBe
                """{"name":"123","count":3,"ratio":1.10,"enabled":true,"nothing":null,"items":["a",2,{"nested":{"key":"value"}}]}"""
        }

        test("resolves anchors and merges before converting") {
            val yaml =
                """
                base: &base
                  port: 80
                  host: localhost
                server:
                  <<: *base
                  port: 443
                """.trimIndent()

            val yamlWithAnchors = Yaml(configuration = YamlConfiguration(anchorsAndAliases = AnchorsAndAliases.Permitted()))
            val json = yamlWithAnchors.parseToYamlNode(yaml).toJsonElement()

            Json.encodeToString(JsonElement.serializer(), json) shouldBe
                """{"base":{"port":80,"host":"localhost"},"server":{"port":443,"host":"localhost"}}"""
        }
    })
