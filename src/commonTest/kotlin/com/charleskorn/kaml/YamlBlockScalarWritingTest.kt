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
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okio.Buffer

class YamlBlockScalarWritingTest :
    FlatFunSpec({
        for (style in listOf(MultiLineStringStyle.Literal, MultiLineStringStyle.Folded)) {
            val yaml = Yaml(configuration = YamlConfiguration(multiLineStringStyle = style))

            for (trailingLineFeeds in 0..3) {
                val value = "first\nlast" + "\n".repeat(trailingLineFeeds)

                test("round trips a root $style scalar with $trailingLineFeeds trailing line feeds through string and sink") {
                    val sink = Buffer()
                    yaml.encodeToSink(String.serializer(), value, sink)
                    val sinkText = sink.readUtf8()
                    val stringText = yaml.encodeToString(String.serializer(), value)

                    stringText shouldBe sinkText
                    yaml.decodeFromString(String.serializer(), sinkText) shouldBe value
                    yaml.decodeFromString(String.serializer(), stringText) shouldBe value
                }

                test("round trips a final $style property with $trailingLineFeeds trailing line feeds through string and sink") {
                    val input = BlockScalarProperty(value)
                    val sink = Buffer()
                    yaml.encodeToSink(BlockScalarProperty.serializer(), input, sink)
                    val sinkText = sink.readUtf8()
                    val stringText = yaml.encodeToString(BlockScalarProperty.serializer(), input)

                    stringText shouldBe sinkText
                    yaml.decodeFromString(BlockScalarProperty.serializer(), sinkText) shouldBe input
                    yaml.decodeFromString(BlockScalarProperty.serializer(), stringText) shouldBe input
                }
            }

            for ((description, value) in mapOf(
                "one line feed only" to "\n",
                "multiple line feeds only" to "\n\n\n",
                "trailing spaces" to "first\nlast  ",
                "a trailing whitespace-only line" to "first\nlast\n  ",
            )) {
                test("preserves $description in a $style scalar") {
                    val sink = Buffer()
                    yaml.encodeToSink(String.serializer(), value, sink)
                    val output = yaml.encodeToString(String.serializer(), value)

                    output shouldBe sink.readUtf8()
                    yaml.decodeFromString<String>(output) shouldBe value
                }
            }

            for (sequenceStyle in SequenceStyle.entries) {
                test("preserves the final $style scalar in a $sequenceStyle sequence") {
                    val sequenceYaml = Yaml(configuration = yaml.configuration.copy(sequenceStyle = sequenceStyle))
                    val input = listOf("first", "last\n\n\n")
                    val output = sequenceYaml.encodeToString(ListSerializer(String.serializer()), input)
                    val sink = Buffer()
                    sequenceYaml.encodeToSink(ListSerializer(String.serializer()), input, sink)

                    output shouldBe sink.readUtf8()
                    sequenceYaml.decodeFromString<List<String>>(output) shouldBe input
                }
            }

            test("preserves the structural final line feed after a scalar following a $style block") {
                val input = listOf("first\n\n\n", "last")
                val sink = Buffer()
                yaml.encodeToSink(ListSerializer(String.serializer()), input, sink)
                val output = yaml.encodeToString(ListSerializer(String.serializer()), input)

                output shouldBe sink.readUtf8()
                yaml.decodeFromString<List<String>>(output) shouldBe input
            }

            test("preserves the final line feed after an empty collection following a $style block") {
                val serializer = ListSerializer(ListSerializer(String.serializer()))
                val input = listOf(listOf("first\n\n\n"), emptyList())
                val sink = Buffer()
                yaml.encodeToSink(serializer, input, sink)
                val output = yaml.encodeToString(serializer, input)

                output shouldBe sink.readUtf8()
                yaml.decodeFromString(serializer, output) shouldBe input
            }
        }

        test("preserves trailing line feeds when property annotations request block styles") {
            val input = AnnotatedBlockScalars("literal\n\n\n", "folded\n\n\n")
            val output = Yaml.default.encodeToString(input)

            Yaml.default.decodeFromString<AnnotatedBlockScalars>(output) shouldBe input
        }
    })

@Serializable
private data class BlockScalarProperty(
    val value: String,
)

@Serializable
private data class AnnotatedBlockScalars(
    @YamlMultiLineStringStyle(MultiLineStringStyle.Literal) val literal: String,
    @YamlMultiLineStringStyle(MultiLineStringStyle.Folded) val folded: String,
)
