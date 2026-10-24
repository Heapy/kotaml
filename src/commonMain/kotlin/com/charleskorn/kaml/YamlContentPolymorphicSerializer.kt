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

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialInfo
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.serializerOrNull
import kotlin.reflect.KClass

/**
 * Selects a subtype deserializer by inspecting YAML content instead of requiring a type discriminator.
 *
 * Implement [selectDeserializer] to choose a subtype from a mapping's keys or another feature of
 * its content. Deserialization requires the [Yaml] format.
 *
 * Encoding looks up the runtime subtype in the encoder's serializers module, then falls back to
 * the subtype's own serializer. It writes the subtype directly without adding a discriminator;
 * the resulting content must be sufficient for [selectDeserializer] to recognize it when read back.
 *
 * For example, attach the serializer to a base type with `@Serializable(with = ...)`, then select
 * a subtype by its mapping keys:
 *
 * ```kotlin
 * @Serializable(with = MessageSerializer::class)
 * sealed interface Message
 *
 * @Serializable
 * data class TextMessage(val text: String) : Message
 *
 * @Serializable
 * data class ImageMessage(val url: String) : Message
 *
 * object MessageSerializer : YamlContentPolymorphicSerializer<Message>(Message::class) {
 *     override fun selectDeserializer(node: YamlNode): DeserializationStrategy<Message> {
 *         val map = node.yamlMap
 *         return when {
 *             map.getKey("text") != null -> TextMessage.serializer()
 *             map.getKey("url") != null -> ImageMessage.serializer()
 *             else -> throw YamlException("Expected a text or url property", node.path)
 *         }
 *     }
 * }
 *
 * val message = Yaml.default.decodeFromString(Message.serializer(), "text: hello")
 * ```
 *
 * The serializer can also be passed directly to [Yaml.decodeFromString] when annotating the base
 * type is not possible.
 *
 * @param baseClass base type used to look up registered polymorphic serializers during encoding.
 */
@OptIn(ExperimentalSerializationApi::class)
public abstract class YamlContentPolymorphicSerializer<T : Any>(
    private val baseClass: KClass<T>,
) : KSerializer<T> {
    @OptIn(InternalSerializationApi::class)
    override val descriptor: SerialDescriptor =
        buildSerialDescriptor(
            "${YamlContentPolymorphicSerializer::class.simpleName}<${baseClass.simpleName}>",
            PolymorphicKind.SEALED,
        ) {
            annotations += Marker()
        }

    /** Marks descriptors whose subtype selection must be deferred until the YAML node is available. */
    @SerialInfo
    internal annotation class Marker

    @OptIn(InternalSerializationApi::class)
    override fun serialize(
        encoder: Encoder,
        value: T,
    ) {
        val actualSerializer =
            encoder.serializersModule.getPolymorphic(baseClass, value)
                ?: value::class.serializerOrNull()
                ?: throwSubtypeNotRegistered(value::class, baseClass)
        @Suppress("UNCHECKED_CAST")
        (actualSerializer as KSerializer<T>).serialize(encoder, value)
    }

    override fun deserialize(decoder: Decoder): T = decoder.decodeSerializableValue(selectDeserializer((decoder as YamlInput).node))

    /**
     * Chooses the concrete subtype's deserializer for [node].
     *
     * The chosen strategy reads the same node, including any keys inspected to make this choice.
     * Do not return this serializer or another strategy that delegates back to it for the same node:
     * that would repeat selection recursively. For unrecognized content, throw [YamlException] with
     * `node.path` so the error identifies the offending value.
     */
    public abstract fun selectDeserializer(node: YamlNode): DeserializationStrategy<T>

    private fun throwSubtypeNotRegistered(
        subClass: KClass<*>,
        baseClass: KClass<*>,
    ): Nothing {
        val subClassName = subClass.simpleName ?: "$subClass"
        throw SerializationException(
            """
            Class '$subClassName' is not registered for polymorphic serialization in the scope of '${baseClass.simpleName}'.
            Mark the base class as 'sealed' or register the serializer explicitly.
            """.trimIndent(),
        )
    }
}
