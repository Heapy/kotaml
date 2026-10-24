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
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import okio.Buffer

class YamlDiscriminatorCollisionTest :
    FlatFunSpec({
        val cases =
            listOf(
                DiscriminatorCase("direct", "type", null, DiscriminatorModel.Direct(), "type"),
                DiscriminatorCase("renamed", "type", null, DiscriminatorModel.Renamed(), "type"),
                DiscriminatorCase("custom", "kind", null, DiscriminatorModel.Custom(), "kind"),
                DiscriminatorCase("naming", "type_name", YamlNamingStrategy.SnakeCase, DiscriminatorModel.Naming(), "typeName"),
                DiscriminatorCase("strategy", "type", YamlNamingStrategy { if (it == "typeName") "type" else it }, DiscriminatorModel.Naming(), "typeName"),
            )
        for (case in cases) {
            val yaml = Yaml(configuration = YamlConfiguration(polymorphismStyle = PolymorphismStyle.Property, polymorphismPropertyName = case.discriminator, yamlNamingStrategy = case.strategy))
            for (encodeDefaults in listOf(true, false)) {
                test("rejects ${case.name} discriminator collision before writing values with defaults=$encodeDefaults") {
                    DiscriminatorValueSerializer.calls = 0
                    val configured = Yaml(configuration = yaml.configuration.copy(encodeDefaults = encodeDefaults))
                    val sink = Buffer()
                    val error = shouldThrow<SerializationException> { configured.encodeToSink(DiscriminatorModel.serializer(), case.value, sink) }
                    error.message.shouldContain("discriminator")
                    error.message.shouldContain(case.discriminator)
                    error.message.shouldContain(case.property)
                    DiscriminatorValueSerializer.calls shouldBe 0
                    sink.size shouldBe 0L
                }
            }
            test("rejects ${case.name} discriminator collision before decoding subtype properties") {
                DiscriminatorValueSerializer.calls = 0
                val type = if (case.name == "strategy") "naming" else case.name
                val error = shouldThrow<SerializationException> { yaml.decodeFromString<DiscriminatorModel>("${case.discriminator}: $type\nprobe: ignored") }
                error.message.shouldContain("discriminator")
                error.message.shouldContain(case.discriminator)
                error.message.shouldContain(case.property)
                DiscriminatorValueSerializer.calls shouldBe 0
            }
            test("keeps ${case.name} model valid with tag polymorphism") {
                val tagged = Yaml(configuration = yaml.configuration.copy(polymorphismStyle = PolymorphismStyle.Tag))
                tagged.decodeFromString<DiscriminatorModel>(tagged.encodeToString(DiscriminatorModel.serializer(), case.value)) shouldBe case.value
            }
            test("round trips non-colliding subtype with ${case.name} configuration and nested values") {
                val value = DiscriminatorContainer(DiscriminatorModel.Safe("first"), listOf(DiscriminatorModel.Safe("nested")), "last")
                yaml.decodeFromString<DiscriminatorContainer>(yaml.encodeToString(value)) shouldBe value
            }
        }
        test("does not reserve discriminator for ordinary non-polymorphic objects") {
            val yaml = Yaml(configuration = YamlConfiguration(polymorphismStyle = PolymorphismStyle.Property))
            val value = DiscriminatorModel.Direct("actual")
            yaml.decodeFromString<DiscriminatorModel.Direct>(yaml.encodeToString(value)) shouldBe value
        }
    })

private data class DiscriminatorCase(
    val name: String,
    val discriminator: String,
    val strategy: YamlNamingStrategy?,
    val value: DiscriminatorModel,
    val property: String,
)

@Serializable
private sealed class DiscriminatorModel {
    @Serializable
    @SerialName("direct")
    data class Direct(
        val type: String = "data",
        @Serializable(with = DiscriminatorValueSerializer::class) val probe: String = "probe",
    ) : DiscriminatorModel()

    @Serializable
    @SerialName("renamed")
    data class Renamed(
        @SerialName("type") val actualName: String = "data",
        @Serializable(with = DiscriminatorValueSerializer::class) val probe: String = "probe",
    ) : DiscriminatorModel()

    @Serializable
    @SerialName("custom")
    data class Custom(
        val kind: String = "data",
        @Serializable(with = DiscriminatorValueSerializer::class) val probe: String = "probe",
    ) : DiscriminatorModel()

    @Serializable
    @SerialName("naming")
    data class Naming(
        val typeName: String = "data",
        @Serializable(with = DiscriminatorValueSerializer::class) val probe: String = "probe",
    ) : DiscriminatorModel()

    @Serializable
    @SerialName("safe")
    data class Safe(
        val data: String,
    ) : DiscriminatorModel()
}

private object DiscriminatorValueSerializer : KSerializer<String> {
    override val descriptor = String.serializer().descriptor
    var calls = 0

    override fun serialize(
        encoder: Encoder,
        value: String,
    ) {
        calls++
        encoder.encodeString(value)
    }

    override fun deserialize(decoder: Decoder): String {
        calls++
        return decoder.decodeString()
    }
}

@Serializable
private data class DiscriminatorContainer(
    val first: DiscriminatorModel,
    val nested: List<DiscriminatorModel>,
    val after: String,
)
