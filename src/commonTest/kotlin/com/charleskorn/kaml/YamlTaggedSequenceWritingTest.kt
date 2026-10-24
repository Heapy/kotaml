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

class YamlTaggedSequenceWritingTest :
    FlatFunSpec({
        for (style in SequenceStyle.entries) {
            val yaml = Yaml(configuration = YamlConfiguration(sequenceStyle = style))
            for (sequence in listOf("[]", "[first, second]")) {
                val documents =
                    listOf(
                        "!<sequence> $sequence",
                        "[!<sequence> $sequence, sibling]",
                        "tagged: !<sequence> $sequence\nsibling: value",
                    )
                for (document in documents) {
                    test("keeps the sequence tag on its node for $style and '$document'") {
                        val expected = yaml.parseToYamlNode(document)
                        val output = yaml.encodeToString(YamlNode.serializer(), expected)
                        val actual = yaml.parseToYamlNode(output)

                        actual.equivalentContentTo(expected) shouldBe true
                    }
                }
            }

            test("keeps nested sequence tags separate with $style") {
                val expected = yaml.parseToYamlNode("!<outer> [!<inner> [value], sibling]")
                val actual = yaml.parseToYamlNode(yaml.encodeToString(YamlNode.serializer(), expected))

                actual.equivalentContentTo(expected) shouldBe true
            }
        }

        for (style in listOf(PolymorphismStyle.Property, PolymorphismStyle.None)) {
            for (sequence in listOf("[]", "[value]")) {
                test("rejects a tagged sequence in $style mode for $sequence") {
                    val node = Yaml.default.parseToYamlNode("!<sequence> $sequence")
                    val yaml = Yaml(configuration = YamlConfiguration(polymorphismStyle = style))

                    shouldThrow<IllegalStateException> {
                        yaml.encodeToString(YamlNode.serializer(), node)
                    }
                }
            }
        }
    })
