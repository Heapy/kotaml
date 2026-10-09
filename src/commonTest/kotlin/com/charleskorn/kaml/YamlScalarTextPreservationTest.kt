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
import kotlinx.serialization.decodeFromString

class YamlScalarTextPreservationTest :
    FlatFunSpec({
        val texts =
            listOf(
                "true",
                "True",
                "TRUE",
                "false",
                "False",
                "FALSE",
                "0",
                "-0",
                "+0",
                "7",
                "007",
                "+7",
                "-7",
                "+007",
                "-007",
                "9223372036854775807",
                "-9223372036854775808",
                "9223372036854775808",
                "-9223372036854775809",
                "99999999999999999999",
                "0x7",
                "0x00fF",
                "0xFFFFFFFFFFFFFFFFFF",
                "0o007",
                "0o7777777777777777777777777",
                "0.0",
                "-0.0",
                "+0.0",
                "7.",
                ".5",
                "-.5",
                "+.5",
                "007.00",
                "1.0000000000000001",
                "1.234567890123456789",
                "1e3",
                "1E+003",
                "1e-400",
                "1e400",
                "5.0e-324",
                ".inf",
                ".Inf",
                ".INF",
                "+.inf",
                "+.Inf",
                "+.INF",
                "-.inf",
                "-.Inf",
                "-.INF",
                ".nan",
                ".NaN",
                ".NAN",
            )
        for (content in texts) {
            val documents =
                listOf(
                    Triple("root", content, content),
                    Triple("sequence item", "- $content", "- $content"),
                    Triple("mapping value", "\"value\": $content", "\"value\": $content"),
                    Triple("mapping key", "$content: \"value\"", "$content: \"value\""),
                    Triple("tagged scalar", "!<scalar> $content", "!<scalar> '$content'"),
                )
            for ((context, input, expected) in documents) {
                test("preserves '$content' as a $context when rewriting a parsed document") {
                    val node = Yaml.default.parseToYamlNode(input)

                    val output = Yaml.default.encodeToString(YamlNode.serializer(), node)

                    output shouldBe expected + "\n"
                    Yaml.default.parseToYamlNode(output).equivalentContentTo(node) shouldBe true
                }
            }
        }

        test("preserves both entries when rewriting '7: a' and '007: b'") {
            val node = Yaml.default.parseToYamlNode("7: a\n007: b")

            val output = Yaml.default.encodeToString(YamlNode.serializer(), node)

            Yaml.default.decodeFromString<Map<String, String>>(output) shouldBe mapOf("7" to "a", "007" to "b")
            output shouldBe "7: \"a\"\n007: \"b\"\n"
        }

        for (keys in listOf(
            listOf("7", "007", "+7", "0x7", "0o7"),
            listOf("0", "-0", "+0"),
            listOf("true", "True", "TRUE"),
            listOf("false", "False", "FALSE"),
            listOf("1.0", "1.0000000000000001", "1e0", "1E+000"),
            listOf(".inf", ".Inf", ".INF", "+.inf"),
            listOf(".nan", ".NaN", ".NAN"),
        )) {
            test("keeps distinct keys $keys when rewriting a parsed mapping") {
                val expected = keys.withIndex().associate { (index, key) -> key to index }
                val input = expected.entries.joinToString("\n") { (key, value) -> "$key: $value" }
                val node = Yaml.default.parseToYamlNode(input)

                val output = Yaml.default.encodeToString(YamlNode.serializer(), node)

                Yaml.default.decodeFromString<Map<String, Int>>(output) shouldBe expected
                output shouldBe input + "\n"
            }
        }
    })
