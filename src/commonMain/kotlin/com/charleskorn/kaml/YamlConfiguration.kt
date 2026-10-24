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

import kotlinx.serialization.ExperimentalSerializationApi

/**
 * Controls YAML reading and writing for a [Yaml] instance.
 *
 * @param encodeDefaults whether to write properties equal to their declared defaults.
 * @param strictMode whether unknown object properties cause an error or are ignored.
 * @param extensionDefinitionPrefix root mapping keys with this prefix define extensions: their values
 * must have anchors and are omitted from the decoded mapping. `null` disables extensions.
 * @param polymorphismStyle how subtype discriminators are represented; see [PolymorphismStyle].
 * @param polymorphismPropertyName discriminator key used by [PolymorphismStyle.Property].
 * @param encodingIndentationSize indentation width in spaces.
 * @param breakScalarsAt preferred line width for encoded scalars.
 * @param sequenceStyle sequence layout; see [SequenceStyle] for examples.
 * @param singleLineStringStyle default style for strings, characters, and enum names; overridable
 * with [YamlSingleLineStringStyle].
 * @param multiLineStringStyle default style for strings containing line breaks; overridable
 * with [YamlMultiLineStringStyle].
 * @param ambiguousQuoteStyle quotes used by [SingleLineStringStyle.PlainExceptAmbiguous] and for null-like strings.
 * @param sequenceBlockIndent indentation of sequence indicators in block layout, in spaces.
 * @param anchorsAndAliases whether anchors and aliases are accepted, and the alias expansion limit.
 * @param yamlNamingStrategy transforms object property names for both reading and writing; `null` leaves them unchanged.
 * @param codePointLimit maximum input size in Unicode code points; `null` uses the parser's default limit.
 * @param decodeEnumCaseInsensitive whether to try case-insensitive enum matching after an exact match fails.
 * @param readCompatibility scalar migration policy; see [YamlReadCompatibility].
 * @param maxNestingDepth maximum nested mappings/sequences while reading, including alias expansion and merge syntax.
 * Defaults to 50; zero permits only scalar/null documents. Raising it requires more runtime stack.
 */
@OptIn(ExperimentalVersionOverloading::class)
public data class YamlConfiguration(
    internal val encodeDefaults: Boolean = true,
    internal val strictMode: Boolean = true,
    internal val extensionDefinitionPrefix: String? = null,
    internal val polymorphismStyle: PolymorphismStyle = PolymorphismStyle.Tag,
    internal val polymorphismPropertyName: String = "type",
    internal val encodingIndentationSize: Int = 2,
    internal val breakScalarsAt: Int = 80,
    internal val sequenceStyle: SequenceStyle = SequenceStyle.Block,
    internal val singleLineStringStyle: SingleLineStringStyle = SingleLineStringStyle.DoubleQuoted,
    internal val multiLineStringStyle: MultiLineStringStyle = singleLineStringStyle.multiLineStringStyle,
    internal val ambiguousQuoteStyle: AmbiguousQuoteStyle = AmbiguousQuoteStyle.DoubleQuoted,
    internal val sequenceBlockIndent: Int = 0,
    internal val anchorsAndAliases: AnchorsAndAliases = AnchorsAndAliases.Forbidden,
    internal val yamlNamingStrategy: YamlNamingStrategy? = null,
    internal val codePointLimit: Int? = null,
    @ExperimentalSerializationApi
    internal val decodeEnumCaseInsensitive: Boolean = false,
    @IntroducedAt("0.111.0")
    internal val readCompatibility: YamlReadCompatibility = YamlReadCompatibility.Strict,
    @IntroducedAt("0.113.0")
    internal val maxNestingDepth: Int = 50,
) {
    init {
        require(maxNestingDepth >= 0) { "maxNestingDepth must not be negative." }
    }
}

/**
 * Controls how ordinary polymorphic serializers represent a subtype discriminator in YAML.
 *
 * [Tag] attaches a YAML type tag; [Property] uses the configured discriminator property in a mapping.
 * [None] omits the discriminator when writing polymorphic mappings, so reading them back requires a
 * concrete serializer or content-based selection with [YamlContentPolymorphicSerializer].
 */
public enum class PolymorphismStyle {
    Tag,
    Property,
    None,
}

/** Chooses block or flow layout for encoded sequences; either layout is accepted when reading. */
public enum class SequenceStyle {
    /**
     * The block form, eg
     * ```yaml
     * - 1
     * - 2
     * - 3
     * ```
     */
    Block,

    /**
     * The flow form, eg
     * ```yaml
     * [1, 2, 3]
     * ```
     */
    Flow,
}

/**
 * Selects the output style for strings containing line breaks.
 *
 * [Literal] and [Folded] request block scalars (`|` and `>`). [YamlMultiLineStringStyle] overrides
 * this default per property. The emitter may adjust the style to represent the content safely.
 */
public enum class MultiLineStringStyle {
    Literal,
    Folded,
    DoubleQuoted,
    SingleQuoted,
    Plain,
}

/**
 * Selects output quoting for single-line strings, characters, and enum names.
 *
 * [YamlSingleLineStringStyle] overrides this default per property. Plain styles may still require
 * quotes to preserve the value; see [Plain] and [PlainExceptAmbiguous].
 */
public enum class SingleLineStringStyle {
    DoubleQuoted,
    SingleQuoted,

    /** Uses plain style where possible, quoting null-like text so it remains readable as a string. */
    Plain,

    /**
     * Quotes strings that could resolve to another type, using [AmbiguousQuoteStyle].
     * For example, `True`, `0xAB`, and `1.2` are quoted; `1.2.3` and `abc` remain plain.
     */
    PlainExceptAmbiguous,
    ;

    public val multiLineStringStyle: MultiLineStringStyle
        get() =
            when (this) {
                DoubleQuoted -> MultiLineStringStyle.DoubleQuoted
                SingleQuoted -> MultiLineStringStyle.SingleQuoted
                Plain -> MultiLineStringStyle.Plain
                PlainExceptAmbiguous -> MultiLineStringStyle.Plain
            }
}

/**
 * Chooses quotes for strings that cannot safely use a requested plain style.
 *
 * This applies to null-like text under either plain single-line style, and to other ambiguous
 * values under [SingleLineStringStyle.PlainExceptAmbiguous].
 */
public enum class AmbiguousQuoteStyle {
    DoubleQuoted,
    SingleQuoted,
}

/**
 * Controls anchor acceptance and alias expansion while reading YAML.
 *
 * Aliases are expanded into node content; this policy does not enable writing anchors or aliases.
 */
public sealed class AnchorsAndAliases {
    internal abstract val maxAliasCount: UInt?

    /** Rejects both anchor definitions and alias references while reading. */
    public data object Forbidden : AnchorsAndAliases() {
        override val maxAliasCount: UInt = 0u
    }

    /**
     * Allows anchors and aliases within a per-document expansion budget.
     *
     * @param maxAliasCount maximum accumulated alias weight, defaulting to 100. An alias contributes
     * one plus the weight of aliases within its referenced value, so nested reuse can cost more than
     * the number of alias tokens. `null` removes the limit; zero rejects anchors and aliases entirely.
     */
    public data class Permitted(
        override val maxAliasCount: UInt? = 100u,
    ) : AnchorsAndAliases()
}
