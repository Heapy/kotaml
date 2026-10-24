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
import com.charleskorn.kaml.internal.readWithCompatibility
import com.charleskorn.kaml.internal.toLegacyDoubleOrNull
import com.charleskorn.kaml.internal.toLegacyFloatOrNull
import com.charleskorn.kaml.internal.toLegacyIntegerOrNull
import kotlinx.serialization.Serializable

/**
 * A value in a YAML document tree, with a [path] describing its position and source location.
 *
 * Use [Yaml.parseToYamlNode] to inspect YAML without a Kotlin model, or construct nodes for encoding
 * with [Yaml]. The supplied serializers require the [Yaml] format; they do not serialize path metadata.
 * Typed accessors such as [yamlScalar] and [yamlMap] throw [IncorrectTypeException] for other node types.
 *
 * [equivalentContentTo] compares content without source paths, whereas ordinary equality includes
 * paths. [withPath] creates a relocated tree, updating descendant paths as well. [contentToString]
 * is a diagnostic representation; use [Yaml.encodeToString] to produce a YAML document.
 */
@Serializable(with = YamlNodeSerializer::class)
public sealed class YamlNode(
    public open val path: YamlPath,
) {
    public val location: Location
        get() = path.endLocation

    public abstract fun equivalentContentTo(other: YamlNode): Boolean

    public abstract fun contentToString(): String

    public abstract fun withPath(newPath: YamlPath): YamlNode

    // The child's path must start with this node's path; the prefix is replaced without validation.
    protected fun replacePathOnChild(
        child: YamlNode,
        newParentPath: YamlPath,
    ): YamlPath = YamlPath(newParentPath.segments + child.path.segments.drop(path.segments.size))
}

/**
 * A YAML scalar value, held as its unconverted text.
 *
 * [content] retains the scalar value, without surrounding YAML quotes. Conversion methods interpret
 * that text regardless of [plain] and throw [YamlScalarFormatException] for invalid input. See [toLong]
 * for integer syntax, [toDouble] for floating-point syntax, and [toBoolean] for boolean spellings.
 *
 * Equality includes [content] and [path] but ignores [plain]. [equivalentContentTo] compares only
 * the text, so differently spelled numbers such as `7` and `007` remain distinct.
 */
@Serializable(with = YamlScalarSerializer::class)
public data class YamlScalar(
    val content: String,
    override val path: YamlPath,
    /**
     * Whether node encoding may implicitly resolve the text as a YAML scalar type.
     *
     * `false` routes the content through the string encoder. Parsing quoted scalars or explicit string
     * tags sets this to `false`. The original quoting is not retained; [SingleLineStringStyle] and
     * property-level [YamlSingleLineStringStyle] overrides control output style.
     * For example, non-plain `0012` keeps its text: the default writer quotes it, while
     * [SingleLineStringStyle.Plain] emits `0012` without quotes, allowing numeric resolution
     * on the next parse. [SingleLineStringStyle.PlainExceptAmbiguous] quotes ambiguous strings
     * but may leave ordinary text plain.
     */
    val plain: Boolean = true,
) : YamlNode(path) {
    override fun equivalentContentTo(other: YamlNode): Boolean = other is YamlScalar && this.content == other.content

    override fun contentToString(): String = "'$content'"

    /** Uses the integer syntax described by [toLong]; values outside the [Byte] range throw [YamlScalarFormatException]. */
    public fun toByte(): Byte = convertToIntegerLikeValue(String::toByteOrNull, "byte")

    /** Uses the integer syntax described by [toLong]; values outside the [Short] range throw [YamlScalarFormatException]. */
    public fun toShort(): Short = convertToIntegerLikeValue(String::toShortOrNull, "short")

    /** Uses the integer syntax described by [toLong]; values outside the [Int] range throw [YamlScalarFormatException]. */
    public fun toInt(): Int = convertToIntegerLikeValue(String::toIntOrNull, "integer")

    /**
     * Parses a decimal integer, a hexadecimal integer prefixed with `0x`, or an octal integer prefixed
     * with `0o`. Leading zeros are allowed; `+` and `-` signs are allowed only for decimal integers.
     * Whitespace, non-ASCII digits, and binary `0b` notation are not accepted.
     *
     * @throws YamlScalarFormatException if the text is invalid or the value is outside the [Long] range.
     * @see YamlReadCompatibility
     */
    public fun toLong(): Long = convertToIntegerLikeValue(String::toLongOrNull, "long")

    /** Converts to [Byte] using the fallback and reporting rules of [YamlReadCompatibility]. */
    public fun toByte(compatibility: YamlReadCompatibility): Byte = readWithCompatibility(compatibility, YamlReadCompatibilityReason.LegacyInteger, "Byte", { toByte() }) { content.toLegacyIntegerOrNull(String::toByteOrNull) }

    /** Converts to [Short] using the fallback and reporting rules of [YamlReadCompatibility]. */
    public fun toShort(compatibility: YamlReadCompatibility): Short = readWithCompatibility(compatibility, YamlReadCompatibilityReason.LegacyInteger, "Short", { toShort() }) { content.toLegacyIntegerOrNull(String::toShortOrNull) }

    /** Converts to [Int] using the fallback and reporting rules of [YamlReadCompatibility]. */
    public fun toInt(compatibility: YamlReadCompatibility): Int = readWithCompatibility(compatibility, YamlReadCompatibilityReason.LegacyInteger, "Int", { toInt() }) { content.toLegacyIntegerOrNull(String::toIntOrNull) }

    /** Converts to [Long] using the fallback and reporting rules of [YamlReadCompatibility]. */
    public fun toLong(compatibility: YamlReadCompatibility): Long = readWithCompatibility(compatibility, YamlReadCompatibilityReason.LegacyInteger, "Long", { toLong() }) { content.toLegacyIntegerOrNull(String::toLongOrNull) }

    /** Converts to [Float] using the fallback and reporting rules of [YamlReadCompatibility]. */
    public fun toFloat(compatibility: YamlReadCompatibility): Float = readWithCompatibility(compatibility, YamlReadCompatibilityReason.LegacyFloatingPoint, "Float", { toFloat() }) { content.toLegacyFloatOrNull() }

    /** Converts to [Double] using the fallback and reporting rules of [YamlReadCompatibility]. */
    public fun toDouble(compatibility: YamlReadCompatibility): Double = readWithCompatibility(compatibility, YamlReadCompatibilityReason.LegacyFloatingPoint, "Double", { toDouble() }) { content.toLegacyDoubleOrNull() }

    private fun <T : Any> convertToIntegerLikeValue(
        converter: (String, Int) -> T?,
        description: String,
    ): T =
        convertToIntegerLikeValueOrNull(converter)
            ?: throw YamlScalarFormatException("Value '$content' is not a valid $description value.", path, content)

    private fun <T : Any> convertToIntegerLikeValueOrNull(converter: (String, Int) -> T?): T? =
        when (classifyScalar(content)) {
            ScalarKind.INT_RADIX -> {
                if (content.startsWith("0x")) converter(content.substring(2), 16) else converter(content.substring(2), 8)
            }

            ScalarKind.INT_DECIMAL -> {
                converter(content, 10)
            }

            else -> {
                null
            }
        }

    /** Uses the syntax described by [toDouble], rounded to [Float] precision; overflow can produce infinity. */
    public fun toFloat(): Float =
        toFloatOrNull()
            ?: throw YamlScalarFormatException("Value '$content' is not a valid floating point value.", path, content)

    internal fun toFloatOrNull(): Float? =
        when (classifyScalar(content)) {
            ScalarKind.INF_POSITIVE -> {
                Float.POSITIVE_INFINITY
            }

            ScalarKind.INF_NEGATIVE -> {
                Float.NEGATIVE_INFINITY
            }

            ScalarKind.NAN -> {
                Float.NaN
            }

            ScalarKind.FLOAT_DECIMAL, ScalarKind.INT_DECIMAL -> {
                parseValidatedDecimalOrNull(String::toFloat)
            }

            else -> {
                null
            }
        }

    /**
     * Parses decimal notation, including integers and exponents, or YAML infinity and NaN literals.
     *
     * Infinity accepts `.inf`, `.Inf`, or `.INF`, optionally prefixed with `+` or `-`.
     * NaN accepts `.nan`, `.NaN`, or `.NAN` without a sign. Hexadecimal and octal integers,
     * `Infinity`, surrounding whitespace, and non-ASCII digits are not accepted.
     * The result is rounded to [Double] precision; overflow can produce infinity.
     *
     * @throws YamlScalarFormatException if the text is not a supported number.
     * @see YamlReadCompatibility
     */
    public fun toDouble(): Double =
        toDoubleOrNull()
            ?: throw YamlScalarFormatException("Value '$content' is not a valid floating point value.", path, content)

    internal fun toDoubleOrNull(): Double? =
        when (classifyScalar(content)) {
            ScalarKind.INF_POSITIVE -> {
                Double.POSITIVE_INFINITY
            }

            ScalarKind.INF_NEGATIVE -> {
                Double.NEGATIVE_INFINITY
            }

            ScalarKind.NAN -> {
                Double.NaN
            }

            ScalarKind.FLOAT_DECIMAL, ScalarKind.INT_DECIMAL -> {
                parseValidatedDecimalOrNull(String::toDouble)
            }

            else -> {
                null
            }
        }

    // The nullable JVM parsers repeat syntax validation already performed by classifyScalar.
    private inline fun <T> parseValidatedDecimalOrNull(converter: (String) -> T): T? =
        try {
            converter(content)
        } catch (_: NumberFormatException) {
            null
        }

    /**
     * Accepts `true`, `True`, `TRUE`, `false`, `False`, and `FALSE`.
     * Other spellings, including YAML 1.1 forms such as `yes` and `on`, throw [YamlScalarFormatException].
     */
    public fun toBoolean(): Boolean =
        toBooleanOrNull()
            ?: throw YamlScalarFormatException(
                "Value '$content' is not a valid boolean, permitted choices are: true or false",
                path,
                content,
            )

    internal fun toBooleanOrNull(): Boolean? =
        when (classifyScalar(content)) {
            ScalarKind.TRUE -> true
            ScalarKind.FALSE -> false
            else -> null
        }

    /**
     * Returns the single UTF-16 code unit in [content].
     * Empty text or text longer than one code unit, including a surrogate pair such as `😀`,
     * throws [YamlScalarFormatException].
     */
    public fun toChar(): Char = toCharOrNull() ?: throw YamlScalarFormatException("Value '$content' is not a valid character value.", path, content)

    internal fun toCharOrNull(): Char? = content.singleOrNull()

    override fun withPath(newPath: YamlPath): YamlScalar = this.copy(path = newPath)

    override fun equals(other: Any?): Boolean = other is YamlScalar && content == other.content && path == other.path

    override fun hashCode(): Int = 31 * content.hashCode() + path.hashCode()

    override fun toString(): String = "scalar @ $path : $content"
}

/**
 * A YAML null with no retained source spelling.
 * An empty string is represented separately by [YamlScalar].
 */
@Serializable(with = YamlNullSerializer::class)
public data class YamlNull(
    override val path: YamlPath,
) : YamlNode(path) {
    override fun equivalentContentTo(other: YamlNode): Boolean = other is YamlNull

    override fun contentToString(): String = "null"

    override fun withPath(newPath: YamlPath): YamlNull = YamlNull(newPath)

    override fun toString(): String = "null @ $path"
}

/**
 * An ordered YAML sequence.
 *
 * [items] is retained without copying and should not be mutated. Child paths must extend this
 * node's [path] for [withPath] to relocate them correctly.
 */
@Serializable(with = YamlListSerializer::class)
public data class YamlList(
    val items: List<YamlNode>,
    override val path: YamlPath,
) : YamlNode(path) {
    override fun equivalentContentTo(other: YamlNode): Boolean {
        if (other !is YamlList) {
            return false
        }

        if (this.items.size != other.items.size) {
            return false
        }

        return this.items.zip(other.items).all { (mine, theirs) -> mine.equivalentContentTo(theirs) }
    }

    public operator fun get(index: Int): YamlNode = items[index]

    override fun contentToString(): String = "[" + items.joinToString(", ") { it.contentToString() } + "]"

    override fun withPath(newPath: YamlPath): YamlList {
        val updatedItems = items.map { it.withPath(replacePathOnChild(it, newPath)) }

        return YamlList(updatedItems, newPath)
    }

    override fun toString(): String {
        val builder = StringBuilder()

        builder.appendLine("list @ $path (size: ${items.size})")

        items.forEachIndexed { index, item ->
            builder.appendLine("- item $index:")

            item.toString().lines().forEach { line ->
                builder.append("  ")
                builder.appendLine(line)
            }
        }

        return builder.trimEnd().toString()
    }
}

/**
 * A YAML mapping whose scalar keys must have distinct text, regardless of path or style.
 * Construction rejects repeated text with [DuplicateKeyException]; content equivalence ignores entry order.
 *
 * [entries] is retained without copying, and its iteration order determines encoding order. Do not
 * mutate it after construction. Key and value paths must extend this node's [path] for [withPath].
 */
@Serializable(with = YamlMapSerializer::class)
public data class YamlMap(
    val entries: Map<YamlScalar, YamlNode>,
    override val path: YamlPath,
) : YamlNode(path) {
    init {
        // Report the later source occurrence as the duplicate, regardless of the map's iteration order.
        val keys =
            entries.keys.sortedWith { a, b ->
                val lineComparison = a.location.line.compareTo(b.location.line)

                if (lineComparison != 0) {
                    lineComparison
                } else {
                    a.location.column.compareTo(b.location.column)
                }
            }

        val encounteredKeys = mutableMapOf<String, YamlScalar>()
        keys.forEach { key ->
            val duplicate = encounteredKeys[key.content]

            if (duplicate != null) {
                throw DuplicateKeyException(duplicate.path, key.path, key.contentToString())
            }

            encounteredKeys[key.content] = key
        }
    }

    override fun equivalentContentTo(other: YamlNode): Boolean {
        if (other !is YamlMap) {
            return false
        }

        if (this.entries.size != other.entries.size) {
            return false
        }

        return this.entries.all { (thisKey, thisValue) ->
            other.entries.any { it.key.equivalentContentTo(thisKey) && it.value.equivalentContentTo(thisValue) }
        }
    }

    override fun contentToString(): String = "{" + entries.map { (key, value) -> "${key.contentToString()}: ${value.contentToString()}" }.joinToString(", ") + "}"

    /**
     * Looks up [key] by its text, ignoring the key node's path and scalar style.
     * Returns null only when the key is absent; a value of the wrong node type throws [IncorrectTypeException].
     *
     * A YAML null, such as `port: ~` or `port:`, is a [YamlNull] node. Requesting it as [YamlScalar]
     * throws rather than returning null. Use `get<YamlNode>(key)` to distinguish an absent key
     * (Kotlin null) from a present YAML null ([YamlNull]).
     */
    public inline operator fun <reified T : YamlNode> get(key: String): T? {
        val node =
            entries.entries
                .firstOrNull { it.key.content == key }
                ?.value ?: return null
        return node as? T ?: throw IncorrectTypeException(
            "Expected element to be ${T::class.simpleName} but is ${node::class.simpleName}",
            node.path,
        )
    }

    /**
     * Returns a scalar by key text, or null only if the key is absent.
     * Any other node type, including [YamlNull], throws [IncorrectTypeException]. Use `get<YamlNode>(key)`
     * to handle a present YAML null separately.
     */
    public fun getScalar(key: String): YamlScalar? =
        when (val node = get<YamlNode>(key)) {
            null -> null
            is YamlScalar -> node
            else -> throw IncorrectTypeException("Value for '$key' is not a scalar.", node.path)
        }

    /** Returns the key node matching [key]'s text for source diagnostics, or null if absent. */
    public fun getKey(key: String): YamlScalar? = entries.keys.singleOrNull { it.content == key }

    override fun withPath(newPath: YamlPath): YamlMap {
        val updatedEntries =
            entries
                .mapKeys { (k, _) -> k.withPath(replacePathOnChild(k, newPath)) }
                .mapValues { (_, v) -> v.withPath(replacePathOnChild(v, newPath)) }

        return YamlMap(updatedEntries, newPath)
    }

    override fun toString(): String {
        val builder = StringBuilder()

        builder.appendLine("map @ $path (size: ${entries.size})")

        entries.forEach { (key, value) ->
            builder.appendLine("- key:")

            key.toString().lines().forEach { line ->
                builder.append("    ")
                builder.appendLine(line)
            }

            builder.appendLine("  value:")

            value.toString().lines().forEach { line ->
                builder.append("    ")
                builder.appendLine(line)
            }
        }

        return builder.trimEnd().toString()
    }
}

/**
 * A tag attached to [innerNode], sharing its path and location.
 *
 * [tag] uses the resolved form, such as `tag:yaml.org,2002:str` for `!!str`, or a custom polymorphic
 * type name. Content equivalence compares both the tag and inner content.
 */
@Serializable(with = YamlTaggedNodeSerializer::class)
public data class YamlTaggedNode(
    val tag: String,
    val innerNode: YamlNode,
) : YamlNode(innerNode.path) {
    override fun equivalentContentTo(other: YamlNode): Boolean {
        if (other !is YamlTaggedNode) {
            return false
        }

        if (tag != other.tag) {
            return false
        }

        return innerNode.equivalentContentTo(other.innerNode)
    }

    override fun contentToString(): String = "!$tag ${innerNode.contentToString()}"

    override fun withPath(newPath: YamlPath): YamlNode = this.copy(innerNode = innerNode.withPath(newPath))

    override fun toString(): String = "tagged '$tag': $innerNode"
}

public val YamlNode.yamlScalar: YamlScalar
    get() = this as? YamlScalar ?: error(this, "YamlScalar")

public val YamlNode.yamlNull: YamlNull
    get() = this as? YamlNull ?: error(this, "YamlNull")

public val YamlNode.yamlList: YamlList
    get() = this as? YamlList ?: error(this, "YamlList")

public val YamlNode.yamlMap: YamlMap
    get() = this as? YamlMap ?: error(this, "YamlMap")

public val YamlNode.yamlTaggedNode: YamlTaggedNode
    get() = this as? YamlTaggedNode ?: error(this, "YamlTaggedNode")

private fun error(
    node: YamlNode,
    expectedType: String,
): Nothing = throw IncorrectTypeException("Expected element to be $expectedType but is ${node::class.simpleName}", node.path)
