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
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encodeToString
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure
import okio.Buffer

@OptIn(ExperimentalSerializationApi::class)
class YamlNamingCollisionTest :
    FlatFunSpec({
        val cases =
            listOf(
                NamingCollisionCase("snake", YamlNamingStrategy.SnakeCase, "fooBar", "foo_bar", "foo_bar"),
                NamingCollisionCase("kebab", YamlNamingStrategy.KebabCase, "fooBar", "foo-bar", "foo-bar"),
                NamingCollisionCase("pascal", YamlNamingStrategy.PascalCase, "foo_bar", "foo-bar", "FooBar"),
                NamingCollisionCase("camel", YamlNamingStrategy.CamelCase, "FooBar", "fooBar", "fooBar"),
                NamingCollisionCase("custom", YamlNamingStrategy { "same" }, "first", "second", "same"),
            )
        for (case in cases) {
            val yaml = Yaml(configuration = YamlConfiguration(yamlNamingStrategy = case.strategy))
            test("rejects ${case.name} collisions before output or property serialization") {
                val serializer = NamingCollisionSerializer(case.first, case.second)
                val sink = Buffer()
                val error = shouldThrow<SerializationException> { yaml.encodeToSink(serializer, "value", sink) }
                error.message.shouldContain(case.first)
                error.message.shouldContain(case.second)
                error.message.shouldContain(case.key)
                serializer.valueCalls shouldBe 0
                sink.size shouldBe 0L
            }
            for (document in listOf("${case.key}: value", "{}")) {
                test("rejects ${case.name} collisions before property deserialization for '$document'") {
                    val serializer = NamingCollisionSerializer(case.first, case.second)
                    val error = shouldThrow<SerializationException> { yaml.decodeFromString(serializer, document) }
                    error.message.shouldContain(case.first)
                    error.message.shouldContain(case.second)
                    error.message.shouldContain(case.key)
                    serializer.valueCalls shouldBe 0
                }
            }
        }

        test("validates optional serial names even when default properties would be omitted") {
            val yaml = Yaml(configuration = YamlConfiguration(yamlNamingStrategy = YamlNamingStrategy.SnakeCase, encodeDefaults = false))
            val error = shouldThrow<SerializationException> { yaml.encodeToString(SerialNamedCollision()) }
            error.message.shouldContain("fooBar")
            error.message.shouldContain("foo_bar")
            shouldThrow<SerializationException> { yaml.decodeFromString<SerialNamedCollision>("{}") }
        }

        for (strategy in listOf(null, YamlNamingStrategy.SnakeCase, YamlNamingStrategy.KebabCase, YamlNamingStrategy.PascalCase, YamlNamingStrategy.CamelCase, YamlNamingStrategy { "prefix_$it" })) {
            test("round trips nested non-colliding properties with $strategy") {
                val yaml = Yaml(configuration = YamlConfiguration(yamlNamingStrategy = strategy))
                val value = NamingUnique("before", listOf(NamingLeaf("one"), NamingLeaf("two")), "after")
                yaml.decodeFromString<NamingUnique>(yaml.encodeToString(value)) shouldBe value
            }
        }

        test("uses validated names without calling the strategy again while encoding values") {
            val calls = mutableListOf<String>()
            val yaml =
                Yaml(
                    configuration =
                        YamlConfiguration(
                            yamlNamingStrategy =
                                YamlNamingStrategy {
                                    calls.add(it)
                                    "prefix_$it"
                                },
                        ),
                )
            yaml.encodeToString(NamingUnique("before", listOf(NamingLeaf("one"), NamingLeaf("two")), "after")) shouldBe
                "prefix_firstValue: \"before\"\nprefix_nestedValue:\n- prefix_leafValue: \"one\"\n- prefix_leafValue: \"two\"\nprefix_lastValue: \"after\"\n"
            calls shouldBe listOf("firstValue", "nestedValue", "lastValue", "leafValue")
        }
    })

private data class NamingCollisionCase(
    val name: String,
    val strategy: YamlNamingStrategy,
    val first: String,
    val second: String,
    val key: String,
)

private class NamingCollisionSerializer(
    first: String,
    second: String,
) : KSerializer<String> {
    override val descriptor =
        buildClassSerialDescriptor("Collision") {
            element<String>(first)
            element<String>(second)
        }
    var valueCalls = 0

    override fun serialize(
        encoder: Encoder,
        value: String,
    ) {
        encoder.encodeStructure(descriptor) {
            for (index in 0..1) {
                valueCalls++
                encodeStringElement(descriptor, index, value)
            }
        }
    }

    override fun deserialize(decoder: Decoder): String {
        decoder.decodeStructure(descriptor) {
            while (true) {
                val index = decodeElementIndex(descriptor)
                if (index < 0) break
                valueCalls++
                decodeStringElement(descriptor, index)
            }
        }
        return "value"
    }
}

@Serializable
private data class SerialNamedCollision(
    val fooBar: String = "a",
    @SerialName("foo_bar") val second: String = "b",
)

@Serializable
private data class NamingUnique(
    val firstValue: String,
    val nestedValue: List<NamingLeaf>,
    val lastValue: String,
)

@Serializable
private data class NamingLeaf(
    val leafValue: String,
)
