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
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.decodeFromString

class YamlScalarWritingTest :
    FlatFunSpec({
        context("serializing YAML scalar nodes") {
            listOf("123", "true", "1.5", "0x11", ".inf").forEach { content ->
                test("keeps non-plain scalar '$content' as a string") {
                    val scalar = YamlScalar(content, YamlPath.root, plain = false)

                    Yaml.default.encodeToString(YamlScalar.serializer(), scalar) shouldBe "\"$content\""
                }
            }

            test("keeps a non-plain number-like map key as a string") {
                val map =
                    YamlMap(
                        mapOf(
                            YamlScalar("123", YamlPath.root, plain = false) to
                                YamlScalar("value", YamlPath.root, plain = false),
                        ),
                        YamlPath.root,
                    )

                Yaml.default.encodeToString(YamlMap.serializer(), map) shouldBe "\"123\": \"value\""
            }

            listOf("1f", "1d", "1F", "1D", "0x1p3", "Infinity", "NaN", "٣", "３", "1_000").forEach { content ->
                test("keeps non-Core plain scalar '$content' as a string") {
                    val scalar = YamlScalar(content, YamlPath.root, plain = true)

                    Yaml.default.encodeToString(YamlScalar.serializer(), scalar) shouldBe "\"$content\""
                }
            }

            listOf("true", "-5", "0x2", "0o11", "5.34").forEach { content ->
                test("preserves Core plain scalar '$content'") {
                    val scalar = YamlScalar(content, YamlPath.root, plain = true)

                    Yaml.default.encodeToString(YamlScalar.serializer(), scalar) shouldBe content
                }
            }

            listOf(
                "99999999999999999999",
                "-99999999999999999999",
                "0xFFFFFFFFFFFFFFFFFF",
                "0o7777777777777777777777777",
                "1e400",
                "-1e400",
                "1e-400",
            ).forEach { content ->
                test("keeps the text of Core scalar '$content', which Long and Double cannot hold") {
                    val scalar = YamlScalar(content, YamlPath.root, plain = true)

                    Yaml.default.encodeToString(YamlScalar.serializer(), scalar) shouldBe content
                }
            }

            mapOf(
                "123" to "123",
                "\"123\"" to "\"123\"",
                "123: value" to "123: \"value\"",
                "\"123\": value" to "\"123\": \"value\"",
            ).forEach { (input, expected) ->
                test("preserves scalar resolution when parsing and serializing '$input'") {
                    val parsed = Yaml.default.parseToYamlNode(input)

                    Yaml.default.encodeToString(YamlNode.serializer(), parsed) shouldBe expected
                }
            }

            test("respects an explicitly configured plain string style for a non-plain scalar") {
                val yaml =
                    Yaml(
                        configuration =
                            YamlConfiguration(
                                singleLineStringStyle = SingleLineStringStyle.Plain,
                            ),
                    )
                val scalar = YamlScalar("123", YamlPath.root, plain = false)

                yaml.encodeToString(YamlScalar.serializer(), scalar) shouldBe "123"
            }

            for (quoteStyle in AmbiguousQuoteStyle.entries) {
                test("quotes syntax and nulls for already classified string nodes with $quoteStyle") {
                    val yaml = Yaml(configuration = YamlConfiguration(singleLineStringStyle = SingleLineStringStyle.PlainExceptAmbiguous, ambiguousQuoteStyle = quoteStyle))
                    val quote = if (quoteStyle == AmbiguousQuoteStyle.SingleQuoted) "'" else "\""

                    for ((content, expected) in mapOf(
                        "NULL" to "${quote}NULL$quote",
                        "ordinary" to "ordinary",
                        "1f" to "1f",
                        "# heading" to "$quote# heading$quote",
                        "-" to "$quote-$quote",
                        "" to "$quote$quote",
                    )) {
                        val node = YamlMap(mapOf(YamlScalar("value", YamlPath.root) to YamlScalar(content, YamlPath.root)), YamlPath.root)

                        val output = yaml.encodeToString(YamlNode.serializer(), node)

                        output shouldBe "value: $expected"
                        yaml.decodeFromString<Map<String, String?>>(output) shouldBe mapOf("value" to content)
                    }
                }
            }
        }

        context("serializing strings with PlainExceptAmbiguous") {
            val yaml =
                Yaml(
                    configuration =
                        YamlConfiguration(
                            singleLineStringStyle = SingleLineStringStyle.PlainExceptAmbiguous,
                        ),
                )

            listOf("123", "true", "1.2", "0x11", ".inf", "null").forEach { content ->
                test("quotes ambiguous string '$content'") {
                    yaml.encodeToString(String.serializer(), content) shouldBe "\"$content\""
                }
            }

            listOf("1f", "0x1p3", "٣", "３").forEach { content ->
                test("leaves non-Core string '$content' plain") {
                    yaml.encodeToString(String.serializer(), content) shouldBe content
                }
            }

            mapOf(
                "" to "--- \"\"",
                "# heading" to "\"# heading\"",
                "~" to "\"~\"",
                "-" to "\"-\"",
            ).forEach { (content, expected) ->
                test("quotes syntactically dangerous string '$content'") {
                    yaml.encodeToString(String.serializer(), content) shouldBe expected
                }
            }
        }

        context("serializing floating point values under the Core Schema") {
            mapOf(
                Double.POSITIVE_INFINITY to ".inf",
                Double.NEGATIVE_INFINITY to "-.inf",
                Double.NaN to ".nan",
            ).forEach { (value, expected) ->
                test("writes the double $expected") {
                    Yaml.default.encodeToString(Double.serializer(), value) shouldBe expected
                }
            }

            mapOf(
                Float.POSITIVE_INFINITY to ".inf",
                Float.NEGATIVE_INFINITY to "-.inf",
                Float.NaN to ".nan",
            ).forEach { (value, expected) ->
                test("writes the float $expected") {
                    Yaml.default.encodeToString(Float.serializer(), value) shouldBe expected
                }
            }

            listOf(
                1.0,
                100.0,
                -1.5,
                1e20,
                1e21,
                1e-7,
                Double.MIN_VALUE,
                Double.MAX_VALUE,
                Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY,
            ).forEach { value ->
                test("round trips the double $value") {
                    val text = Yaml.default.encodeToString(Double.serializer(), value)

                    Yaml.default.decodeFromString(Double.serializer(), text) shouldBe value
                }
            }

            test("round trips a not-a-number double") {
                val text = Yaml.default.encodeToString(Double.serializer(), Double.NaN)

                Yaml.default.decodeFromString(Double.serializer(), text).isNaN() shouldBe true
            }

            test("keeps the sign of negative zero") {
                val text = Yaml.default.encodeToString(Double.serializer(), -0.0)
                val decoded = Yaml.default.decodeFromString(Double.serializer(), text)

                (1.0 / decoded) shouldBe Double.NEGATIVE_INFINITY
            }

            mapOf(
                "v: .inf" to "\"v\": .inf",
                "v: -.inf" to "\"v\": -.inf",
                "v: .nan" to "\"v\": .nan",
            ).forEach { (input, expected) ->
                test("keeps '$input' a float when the node is re-serialized") {
                    val node = Yaml.default.parseToYamlNode(input)

                    Yaml.default.encodeToString(YamlNode.serializer(), node) shouldBe expected
                }
            }
        }
    })
