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

import com.charleskorn.kaml.internal.ScalarKind
import com.charleskorn.kaml.internal.classifyScalar
import com.charleskorn.kaml.internal.isNullLiteral
import it.krzeminski.snakeyaml.engine.kmp.api.DumpSettings
import it.krzeminski.snakeyaml.engine.kmp.api.StreamDataWriter
import it.krzeminski.snakeyaml.engine.kmp.comments.CommentType
import it.krzeminski.snakeyaml.engine.kmp.common.FlowStyle
import it.krzeminski.snakeyaml.engine.kmp.common.ScalarStyle
import it.krzeminski.snakeyaml.engine.kmp.emitter.Emitter
import it.krzeminski.snakeyaml.engine.kmp.events.CommentEvent
import it.krzeminski.snakeyaml.engine.kmp.events.DocumentEndEvent
import it.krzeminski.snakeyaml.engine.kmp.events.DocumentStartEvent
import it.krzeminski.snakeyaml.engine.kmp.events.ImplicitTuple
import it.krzeminski.snakeyaml.engine.kmp.events.MappingEndEvent
import it.krzeminski.snakeyaml.engine.kmp.events.MappingStartEvent
import it.krzeminski.snakeyaml.engine.kmp.events.ScalarEvent
import it.krzeminski.snakeyaml.engine.kmp.events.SequenceEndEvent
import it.krzeminski.snakeyaml.engine.kmp.events.SequenceStartEvent
import it.krzeminski.snakeyaml.engine.kmp.events.StreamEndEvent
import it.krzeminski.snakeyaml.engine.kmp.events.StreamStartEvent
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.modules.SerializersModule

@OptIn(ExperimentalSerializationApi::class, ExperimentalStdlibApi::class)
internal class YamlOutput(
    writer: StreamDataWriter,
    override val serializersModule: SerializersModule,
    private val configuration: YamlConfiguration,
) : AbstractEncoder(),
    AutoCloseable {
    private val settings =
        DumpSettings(
            dumpComments = true,
            // SnakeYAML validates that this value must be at least 1
            indent = configuration.encodingIndentationSize,
            // SnakeYAML helps to validate that this value must be non-negative
            indicatorIndent = configuration.sequenceBlockIndent,
            // No special reason why true is conditional. Designed to be consistent with 0.46.0 of kaml
            indentWithIndicator = configuration.sequenceBlockIndent > 0,
            // Unclear if this value is validated
            width = configuration.breakScalarsAt,
        )

    private val emitter = Emitter(settings, writer)
    private var shouldReadTypeName = false
    private var currentTypeName: String? = null

    init {
        emitter.emit(StreamStartEvent())
        emitter.emit(DocumentStartEvent(false, null, emptyMap()))
    }

    override fun shouldEncodeElementDefault(
        descriptor: SerialDescriptor,
        index: Int,
    ): Boolean = configuration.encodeDefaults

    override fun encodeNull() = emitPlainScalar("null")

    override fun encodeBoolean(value: Boolean) = emitPlainScalar(value.toString())

    override fun encodeByte(value: Byte) = emitPlainScalar(value.toString())

    override fun encodeChar(value: Char) = emitStringScalar(value.toString())

    override fun encodeDouble(value: Double) = emitPlainScalar(value.toYamlText())

    override fun encodeFloat(value: Float) = emitPlainScalar(value.toYamlText())

    override fun encodeInt(value: Int) = emitPlainScalar(value.toString())

    override fun encodeLong(value: Long) = emitPlainScalar(value.toString())

    override fun encodeShort(value: Short) = emitPlainScalar(value.toString())

    private var forcedSingleLineScalarStyle: SingleLineStringStyle? = null
    private var forcedMultiLineScalarStyle: MultiLineStringStyle? = null

    override fun encodeString(value: String) = encodeString(value, scalarKind = null)

    internal fun encodeString(
        value: String,
        scalarKind: ScalarKind?,
    ) {
        if (shouldReadTypeName) {
            currentTypeName = value
            shouldReadTypeName = false
        } else {
            val multiLineScalarStyle = forcedMultiLineScalarStyle ?.scalarStyle ?: configuration.multiLineStringStyle.scalarStyle
            when {
                value.contains('\n')
                -> emitScalar(value, multiLineScalarStyle)

                else
                -> emitStringScalar(value, scalarKind)
            }
        }
    }

    override fun encodeEnum(
        enumDescriptor: SerialDescriptor,
        index: Int,
    ) = emitStringScalar(enumDescriptor.getElementName(index))

    private fun emitStringScalar(
        value: String,
        scalarKind: ScalarKind? = null,
    ) {
        val style = forcedSingleLineScalarStyle ?: configuration.singleLineStringStyle
        val scalarStyle = style.scalarStyle
        // Plain null spellings lose their text in the reader, including enum values and map keys.
        val quoteNull = scalarStyle == ScalarStyle.PLAIN && value.isNullLiteral()
        val quoteAmbiguous = !quoteNull && style == SingleLineStringStyle.PlainExceptAmbiguous && value.isAmbiguous(scalarKind)
        emitScalar(value, if (quoteNull || quoteAmbiguous) configuration.ambiguousQuoteStyle.scalarStyle else scalarStyle)
    }

    private fun emitPlainScalar(value: String) = emitScalar(value, ScalarStyle.PLAIN)

    private fun emitPropertyName(value: String) = if (value.isNullLiteral() || value == "<<") emitQuotedScalar(value, ScalarStyle.DOUBLE_QUOTED) else emitPlainScalar(value)

    /** Writes [value] unchanged, for text that already is the scalar the document should carry. */
    internal fun encodeVerbatimScalar(value: String) = emitPlainScalar(value)

    private fun emitQuotedScalar(
        value: String,
        scalarStyle: ScalarStyle,
    ) = emitScalar(value, scalarStyle)

    private inline fun <reified R> SerialDescriptor.getAnnotation(index: Int): R? =
        getElementAnnotations(index)
            .filterIsInstance<R>()
            .firstOrNull()

    override fun encodeElement(
        descriptor: SerialDescriptor,
        index: Int,
    ): Boolean {
        encodeComment(descriptor, index)

        if (descriptor.kind is StructureKind.CLASS) {
            val elementName = descriptor.getElementName(index)
            val serializedName = configuration.yamlNamingStrategy?.serialNameForYaml(elementName) ?: elementName
            emitPropertyName(serializedName)
        }

        // If this field was annotated we overrule the used ScalarStyle with the annotation
        forcedSingleLineScalarStyle = descriptor.getAnnotation<YamlSingleLineStringStyle>(index)?.singleLineStringStyle
        forcedMultiLineScalarStyle = descriptor.getAnnotation<YamlMultiLineStringStyle>(index)?.multiLineStringStyle

        return super.encodeElement(descriptor, index)
    }

    override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder {
        when (descriptor.kind) {
            is PolymorphicKind -> {
                shouldReadTypeName = true
            }

            StructureKind.LIST -> {
                emitter.emit(SequenceStartEvent(null, null, true, configuration.sequenceStyle.flowStyle))
            }

            StructureKind.MAP, StructureKind.CLASS, StructureKind.OBJECT -> {
                val typeName = getAndClearTypeName()

                when (configuration.polymorphismStyle) {
                    PolymorphismStyle.Tag -> {
                        val implicit = typeName == null
                        emitter.emit(MappingStartEvent(null, typeName, implicit, FlowStyle.BLOCK))
                    }

                    PolymorphismStyle.Property -> {
                        emitter.emit(MappingStartEvent(null, null, true, FlowStyle.BLOCK))

                        if (typeName != null) {
                            emitPropertyName(configuration.polymorphismPropertyName)
                            emitQuotedScalar(typeName, SingleLineStringStyle.DoubleQuoted.scalarStyle)
                        }
                    }

                    PolymorphismStyle.None -> {
                        emitter.emit(MappingStartEvent(null, null, true, FlowStyle.BLOCK))
                    }
                }
            }

            else -> {
                // Nothing to do.
            }
        }

        return super.beginStructure(descriptor)
    }

    override fun endStructure(descriptor: SerialDescriptor) {
        when (descriptor.kind) {
            StructureKind.LIST -> {
                emitter.emit(SequenceEndEvent())
            }

            StructureKind.MAP, StructureKind.CLASS, StructureKind.OBJECT -> {
                emitter.emit(MappingEndEvent())
            }

            else -> {
                // Nothing to do.
            }
        }
    }

    override fun close() {
        emitter.emit(DocumentEndEvent(false))
        emitter.emit(StreamEndEvent())
    }

    private fun encodeComment(
        descriptor: SerialDescriptor,
        index: Int,
    ) {
        val commentAnno = descriptor.getAnnotation<YamlComment>(index) ?: return

        for (line in commentAnno.lines) {
            emitter.emit(CommentEvent(CommentType.BLOCK, " $line", null, null))
        }
    }

    private fun emitScalar(
        value: String,
        style: ScalarStyle,
    ) {
        val tag = getAndClearTypeName()

        if (tag != null && configuration.polymorphismStyle != PolymorphismStyle.Tag) {
            throw IllegalStateException(
                "Cannot serialize a polymorphic value that is not a YAML object when using ${PolymorphismStyle::class.simpleName}.${configuration.polymorphismStyle}.",
            )
        }

        val implicit = if (tag != null) ALL_EXPLICIT else ALL_IMPLICIT
        emitter.emit(ScalarEvent(null, tag, implicit, value, style))
    }

    private fun getAndClearTypeName(): String? {
        val typeName = currentTypeName
        currentTypeName = null
        return typeName
    }

    // The Core Schema spells these .inf and .nan; Double.toString spells them Infinity and NaN,
    // which resolve back as strings.
    private fun Double.toYamlText(): String =
        when {
            isNaN() -> ".nan"
            this == Double.POSITIVE_INFINITY -> ".inf"
            this == Double.NEGATIVE_INFINITY -> "-.inf"
            else -> toString().toFloatText(isNegativeZero = this == 0.0 && 1.0 / this < 0.0)
        }

    private fun Float.toYamlText(): String =
        if (isFinite()) {
            // Converting finite floats to Double before formatting would expose extra decimal digits.
            toString().toFloatText(isNegativeZero = this == 0.0f && 1.0f / this < 0.0f)
        } else {
            toDouble().toYamlText()
        }

    // Kotlin/JS renders an integral double as "1" and negative zero as "0"; neither resolves to a
    // float under the Core Schema.
    private fun String.toFloatText(isNegativeZero: Boolean): String {
        val signed = if (isNegativeZero && !startsWith('-')) "-$this" else this

        return if (signed.any { it == '.' || it == 'e' || it == 'E' }) signed else "$signed.0"
    }

    private fun String.isAmbiguous(scalarKind: ScalarKind?): Boolean =
        startsWith('#') ||
            this == "-" ||
            (scalarKind ?: classifyScalar(this)) != ScalarKind.STRING

    private val SequenceStyle.flowStyle: FlowStyle
        get() =
            when (this) {
                SequenceStyle.Block -> FlowStyle.BLOCK
                SequenceStyle.Flow -> FlowStyle.FLOW
            }

    private val MultiLineStringStyle.scalarStyle: ScalarStyle
        get() =
            when (this) {
                MultiLineStringStyle.DoubleQuoted -> ScalarStyle.DOUBLE_QUOTED
                MultiLineStringStyle.SingleQuoted -> ScalarStyle.SINGLE_QUOTED
                MultiLineStringStyle.Literal -> ScalarStyle.LITERAL
                MultiLineStringStyle.Folded -> ScalarStyle.FOLDED
                MultiLineStringStyle.Plain -> ScalarStyle.PLAIN
            }

    private val SingleLineStringStyle.scalarStyle: ScalarStyle
        get() =
            when (this) {
                SingleLineStringStyle.DoubleQuoted -> ScalarStyle.DOUBLE_QUOTED
                SingleLineStringStyle.SingleQuoted -> ScalarStyle.SINGLE_QUOTED
                SingleLineStringStyle.Plain -> ScalarStyle.PLAIN
                SingleLineStringStyle.PlainExceptAmbiguous -> ScalarStyle.PLAIN
            }

    private val AmbiguousQuoteStyle.scalarStyle: ScalarStyle
        get() =
            when (this) {
                AmbiguousQuoteStyle.DoubleQuoted -> ScalarStyle.DOUBLE_QUOTED
                AmbiguousQuoteStyle.SingleQuoted -> ScalarStyle.SINGLE_QUOTED
            }

    companion object {
        private val ALL_IMPLICIT = ImplicitTuple(true, true)
        private val ALL_EXPLICIT = ImplicitTuple(false, false)
    }
}
