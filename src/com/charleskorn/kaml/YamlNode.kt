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

@Serializable(with = YamlNodeSerializer::class)
public sealed class YamlNode(
    public open val path: YamlPath,
) {
    public val location: Location
        get() = path.endLocation

    public abstract fun equivalentContentTo(other: YamlNode): Boolean

    public abstract fun contentToString(): String

    public abstract fun withPath(newPath: YamlPath): YamlNode

    protected fun replacePathOnChild(
        child: YamlNode,
        newParentPath: YamlPath,
    ): YamlPath = YamlPath(newParentPath.segments + child.path.segments.drop(path.segments.size))
}

@Serializable(with = YamlScalarSerializer::class)
public data class YamlScalar(
    val content: String,
    override val path: YamlPath,
    /**
     * Whether this scalar uses YAML plain-style semantics.
     *
     * Parsers set this from the scalar's source style, with explicit string tags forcing `false`.
     * For manually constructed nodes, `true`
     * allows implicit resolution under the YAML 1.2 Core Schema, while `false` always routes the
     * content through the string encoder. This is not a resolved Kotlin type and does not require
     * serialization to preserve the original quoting; output style is controlled by
     * [SingleLineStringStyle].
     */
    val plain: Boolean = true,
) : YamlNode(path) {
    override fun equivalentContentTo(other: YamlNode): Boolean = other is YamlScalar && this.content == other.content

    override fun contentToString(): String = "'$content'"

    public fun toByte(): Byte = convertToIntegerLikeValue(String::toByteOrNull, "byte")

    public fun toShort(): Short = convertToIntegerLikeValue(String::toShortOrNull, "short")

    public fun toInt(): Int = convertToIntegerLikeValue(String::toIntOrNull, "integer")

    public fun toLong(): Long = convertToIntegerLikeValue(String::toLongOrNull, "long")

    /** Explicit migration support; [YamlReadCompatibility.LegacyV0_110] will be removed after the 0.111.0 upgrade period. */
    public fun toByte(compatibility: YamlReadCompatibility): Byte = readWithCompatibility(compatibility, YamlReadCompatibilityReason.LegacyInteger, "Byte", { toByte() }) { content.toLegacyIntegerOrNull(String::toByteOrNull) }

    /** Explicit migration support; [YamlReadCompatibility.LegacyV0_110] will be removed after the 0.111.0 upgrade period. */
    public fun toShort(compatibility: YamlReadCompatibility): Short = readWithCompatibility(compatibility, YamlReadCompatibilityReason.LegacyInteger, "Short", { toShort() }) { content.toLegacyIntegerOrNull(String::toShortOrNull) }

    /** Explicit migration support; [YamlReadCompatibility.LegacyV0_110] will be removed after the 0.111.0 upgrade period. */
    public fun toInt(compatibility: YamlReadCompatibility): Int = readWithCompatibility(compatibility, YamlReadCompatibilityReason.LegacyInteger, "Int", { toInt() }) { content.toLegacyIntegerOrNull(String::toIntOrNull) }

    /** Explicit migration support; [YamlReadCompatibility.LegacyV0_110] will be removed after the 0.111.0 upgrade period. */
    public fun toLong(compatibility: YamlReadCompatibility): Long = readWithCompatibility(compatibility, YamlReadCompatibilityReason.LegacyInteger, "Long", { toLong() }) { content.toLegacyIntegerOrNull(String::toLongOrNull) }

    /** Explicit migration support; [YamlReadCompatibility.LegacyV0_110] will be removed after the 0.111.0 upgrade period. */
    public fun toFloat(compatibility: YamlReadCompatibility): Float = readWithCompatibility(compatibility, YamlReadCompatibilityReason.LegacyFloatingPoint, "Float", { toFloat() }) { content.toLegacyFloatOrNull() }

    /** Explicit migration support; [YamlReadCompatibility.LegacyV0_110] will be removed after the 0.111.0 upgrade period. */
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

    public fun toChar(): Char = toCharOrNull() ?: throw YamlScalarFormatException("Value '$content' is not a valid character value.", path, content)

    internal fun toCharOrNull(): Char? = content.singleOrNull()

    override fun withPath(newPath: YamlPath): YamlScalar = this.copy(path = newPath)

    // equals/hashCode intentionally exclude plain: the scalar style is a presentation detail and must not affect content equality.
    override fun equals(other: Any?): Boolean = other is YamlScalar && content == other.content && path == other.path

    override fun hashCode(): Int = 31 * content.hashCode() + path.hashCode()

    override fun toString(): String = "scalar @ $path : $content"
}

@Serializable(with = YamlNullSerializer::class)
public data class YamlNull(
    override val path: YamlPath,
) : YamlNode(path) {
    override fun equivalentContentTo(other: YamlNode): Boolean = other is YamlNull

    override fun contentToString(): String = "null"

    override fun withPath(newPath: YamlPath): YamlNull = YamlNull(newPath)

    override fun toString(): String = "null @ $path"
}

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

@Serializable(with = YamlMapSerializer::class)
public data class YamlMap(
    val entries: Map<YamlScalar, YamlNode>,
    override val path: YamlPath,
) : YamlNode(path) {
    init {
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
     * Returns the value corresponding to the given key and the given type,
     * or null if such a key is not present in the map,
     * or throws [IncorrectTypeException] if the value is not the given type.
     */
    public inline operator fun <reified T : YamlNode> get(key: String): T? {
        val node =
            entries.entries
                .firstOrNull { it.key.content == key }
                ?.value ?: return null // no such key in the map
        // if the value is not the given type,
        // throws IncorrectTypeException with a clear message instead of ClassCastException.
        return node as? T ?: throw IncorrectTypeException(
            "Expected element to be ${T::class.simpleName} but is ${node::class.simpleName}",
            node.path,
        )
    }

    public fun getScalar(key: String): YamlScalar? =
        when (val node = get<YamlNode>(key)) {
            null -> null
            is YamlScalar -> node
            else -> throw IncorrectTypeException("Value for '$key' is not a scalar.", node.path)
        }

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
