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

import com.charleskorn.kaml.internal.isNullLiteral
import it.krzeminski.snakeyaml.engine.kmp.common.Anchor
import it.krzeminski.snakeyaml.engine.kmp.events.AliasEvent
import it.krzeminski.snakeyaml.engine.kmp.events.CollectionStartEvent
import it.krzeminski.snakeyaml.engine.kmp.events.Event
import it.krzeminski.snakeyaml.engine.kmp.events.MappingStartEvent
import it.krzeminski.snakeyaml.engine.kmp.events.NodeEvent
import it.krzeminski.snakeyaml.engine.kmp.events.ScalarEvent
import it.krzeminski.snakeyaml.engine.kmp.events.SequenceStartEvent

/**
 * Builds one node tree, resolving nulls, aliases, extensions, and mapping merges.
 *
 * Anchor definitions and alias counts belong to one document. Explicit entries take precedence
 * over merged ones; earlier merged mappings take precedence over later ones. Root extension
 * definitions supply anchors but are omitted from the returned mapping.
 *
 * [read] consumes the root; the caller must still check the parser's document and stream endings.
 */
internal class YamlNodeReader(
    private val parser: YamlParser,
    private val extensionDefinitionPrefix: String? = null,
    private val maxAliasCount: UInt? = 0u,
    private val readCompatibility: YamlReadCompatibility = YamlReadCompatibility.Strict,
    private val maxNestingDepth: Int = 50,
) {
    private val aliases = mutableMapOf<Anchor, WeightedNode>()
    private var aliasCount = 0u
    private var nestingDepth = 0

    fun read(): YamlNode = readNode(YamlPath.root).node

    private fun readNode(path: YamlPath): WeightedNode = readNodeAndAnchor(path).first

    private fun readNodeAndAnchor(path: YamlPath): Pair<WeightedNode, Anchor?> {
        val event = parser.consumeEvent(path)
        validateCollectionTag(event, path)
        val result = readFromEvent(event, path)
        val (node, weight, depth) = result

        if (event is NodeEvent) {
            if (event !is AliasEvent) {
                event.anchor?.let {
                    if (maxAliasCount == 0u) {
                        throw ForbiddenAnchorOrAliasException("Parsing anchors and aliases is disabled.", path)
                    }

                    val anchor = node.withPath(YamlPath.forAliasDefinition(it.value, event.location))
                    aliases[it] = WeightedNode(anchor, weight, depth)
                }
            }

            return result to event.anchor
        }

        return result to null
    }

    private fun readFromEvent(
        event: Event,
        path: YamlPath,
    ): WeightedNode =
        when (event) {
            is ScalarEvent -> WeightedNode(readScalarOrNull(event, path).maybeToTaggedNode(event.tag), weight = 0u)
            is SequenceStartEvent -> readCollection(event, path) { readSequence(path) }
            is MappingStartEvent -> readCollection(event, path) { readMapping(path) }
            is AliasEvent -> readAlias(event, path)
            else -> throw MalformedYamlException("Unexpected ${event.eventId}", path.withError(event.location))
        }

    private fun validateCollectionTag(
        event: Event,
        path: YamlPath,
    ) {
        val tag =
            when (event) {
                is ScalarEvent -> event.tag
                is SequenceStartEvent -> event.tag
                is MappingStartEvent -> event.tag
                else -> return
            }
        val requiredKind =
            when {
                tag == SEQUENCE_TAG && event !is SequenceStartEvent -> "sequence"
                tag == MAPPING_TAG && event !is MappingStartEvent -> "mapping"
                else -> return
            }
        val shortTag = "!!" + tag.substringAfterLast(':')
        throw MalformedYamlException("Tag '$shortTag' requires a $requiredKind value.", path.withError(event.location))
    }

    private inline fun readCollection(
        event: CollectionStartEvent,
        path: YamlPath,
        read: () -> WeightedNode,
    ): WeightedNode {
        checkDepth(1, path, event)
        nestingDepth++
        try {
            return read().let { it.copy(node = it.node.maybeToTaggedNode(event.tag)) }
        } finally {
            nestingDepth--
        }
    }

    private fun checkDepth(
        additionalDepth: Int,
        path: YamlPath,
        event: Event,
    ) {
        if (additionalDepth > maxNestingDepth - nestingDepth) {
            throw MalformedYamlException("Maximum YAML nesting depth of $maxNestingDepth exceeded.", path.withError(event.location))
        }
    }

    private fun readScalarOrNull(
        event: ScalarEvent,
        path: YamlPath,
    ): YamlNode {
        // Explicit string tags take precedence over implicit null resolution and migration rules.
        if (event.isExplicitString) {
            return YamlScalar(event.value, path, plain = false)
        }

        if (event.plain && event.value in LEGACY_NULL_STRINGS && readCompatibility is YamlReadCompatibility.LegacyV0_110) {
            readCompatibility.report(YamlReadCompatibilityReason.LegacyNullValue, path, event.value, null, "\"${event.value}\"")
            return YamlScalar(event.value, path, plain = true)
        }

        if (event.value.isNullLiteral() && event.plain) {
            return YamlNull(path)
        } else {
            return YamlScalar(event.value, path, plain = event.plain)
        }
    }

    private fun readSequence(path: YamlPath): WeightedNode {
        val items = mutableListOf<YamlNode>()
        var sequenceWeight = 0u
        var depth = 1

        while (true) {
            val event = parser.peekEvent(path)

            when (event.eventId) {
                Event.ID.SequenceEnd -> {
                    parser.consumeEventOfType(Event.ID.SequenceEnd, path)
                    return WeightedNode(YamlList(items, path), sequenceWeight, depth)
                }

                else -> {
                    val (node, weight, childDepth) = readNode(path.withListEntry(items.size, event.location))
                    depth = maxOf(depth, childDepth + 1)
                    sequenceWeight += weight
                    items += node
                }
            }
        }
    }

    private fun readMapping(path: YamlPath): WeightedNode {
        val items = mutableMapOf<YamlScalar, YamlNode>()
        var mapWeight = 0u
        var depth = 1

        while (true) {
            val event = parser.peekEvent(path)

            when (event.eventId) {
                Event.ID.MappingEnd -> {
                    parser.consumeEventOfType(Event.ID.MappingEnd, path)
                    return WeightedNode(YamlMap(doMerges(items), path), mapWeight, depth)
                }

                else -> {
                    val keyLocation = parser.peekEvent(path).location
                    val keyEvent = readMapKey(path)
                    val key = keyEvent.value
                    val keyNode = YamlScalar(key, path.withMapElementKey(key, keyLocation), plain = keyEvent.plain && !keyEvent.isExplicitString)

                    val valueLocation = parser.peekEvent(keyNode.path).location
                    val valuePath = if (isMerge(keyNode)) path.withMerge(valueLocation) else keyNode.path.withMapElementValue(valueLocation)
                    val (weightedNode, anchor) = readNodeAndAnchor(valuePath)
                    mapWeight += weightedNode.weight
                    depth = maxOf(depth, weightedNode.depth + 1)

                    if (path == YamlPath.root && extensionDefinitionPrefix != null && key.startsWith(extensionDefinitionPrefix)) {
                        if (anchor == null) {
                            throw NoAnchorForExtensionException(key, extensionDefinitionPrefix, path.withError(event.location))
                        }
                    } else {
                        items += (keyNode to weightedNode.node)
                    }
                }
            }
        }
    }

    private fun readMapKey(path: YamlPath): ScalarEvent {
        val event = parser.peekEvent(path)
        validateCollectionTag(event, path)

        when (event.eventId) {
            Event.ID.Scalar -> {
                parser.consumeEventOfType(Event.ID.Scalar, path)
                val scalarEvent = event as ScalarEvent
                val isNullKey = scalarEvent.value.isNullLiteral() && scalarEvent.plain && !scalarEvent.isExplicitString

                if (scalarEvent.tag != null && !scalarEvent.isExplicitString) {
                    throw nonScalarMapKeyException(path, event)
                }

                if (isNullKey) {
                    val isLegacyNullKey = scalarEvent.value.isEmpty() || scalarEvent.value in LEGACY_NULL_STRINGS
                    if (!isLegacyNullKey || readCompatibility !is YamlReadCompatibility.LegacyV0_110) {
                        throw nonScalarMapKeyException(path, event)
                    }

                    readCompatibility.report(
                        YamlReadCompatibilityReason.LegacyNullKey,
                        path.withMapElementKey(scalarEvent.value, event.location),
                        scalarEvent.value,
                        null,
                        "\"${scalarEvent.value}\"",
                    )
                }

                return scalarEvent
            }

            else -> {
                throw nonScalarMapKeyException(path, event)
            }
        }
    }

    private fun nonScalarMapKeyException(
        path: YamlPath,
        event: Event,
    ) = MalformedYamlException(
        when {
            event !is ScalarEvent -> "Property name must be a scalar value."
            event.tag != null -> "Only !!str and ! tags are supported on property names."
            event.value.isEmpty() -> "Property name must not be null. (To use an empty string as a property name, write \"\".)"
            else -> "Property name must not be null. (To use '${event.value}' as a property name, enclose it in quotes.)"
        },
        path.withError(event.location),
    )

    private fun YamlNode.maybeToTaggedNode(tag: String?): YamlNode = tag?.let { YamlTaggedNode(it, this) } ?: this

    private val ScalarEvent.isExplicitString: Boolean
        get() = tag == STRING_TAG || tag == "!"

    private fun doMerges(items: Map<YamlScalar, YamlNode>): Map<YamlScalar, YamlNode> {
        val mergeEntries = items.entries.filter { (key, _) -> isMerge(key) }

        when (mergeEntries.count()) {
            0 -> {
                return items
            }

            1 -> {
                when (val mappingsToMerge = mergeEntries.single().value) {
                    is YamlList -> return doMerges(items, mappingsToMerge.items)
                    else -> return doMerges(items, listOf(mappingsToMerge))
                }
            }

            else -> {
                throw MalformedYamlException(
                    "Cannot perform multiple '<<' merges into a map. Instead, combine all merges into a single '<<' entry.",
                    mergeEntries.second().key.path,
                )
            }
        }
    }

    // Quoted "<<" is an ordinary key; only a plain << requests a mapping merge.
    private fun isMerge(key: YamlNode): Boolean = key is YamlScalar && key.plain && key.content == "<<"

    private fun doMerges(
        original: Map<YamlScalar, YamlNode>,
        others: List<YamlNode>,
    ): Map<YamlScalar, YamlNode> {
        val merged = mutableMapOf<YamlScalar, YamlNode>()

        original
            .filterNot { (key, _) -> isMerge(key) }
            .forEach { (key, value) -> merged.put(key, value) }

        others
            .forEach { other ->
                when (other) {
                    is YamlNull -> {
                        throw MalformedYamlException("Cannot merge a null value into a map.", other.path)
                    }

                    is YamlScalar -> {
                        throw MalformedYamlException("Cannot merge a scalar value into a map.", other.path)
                    }

                    is YamlList -> {
                        throw MalformedYamlException("Cannot merge a list value into a map.", other.path)
                    }

                    is YamlTaggedNode -> {
                        throw MalformedYamlException("Cannot merge a tagged value into a map.", other.path)
                    }

                    is YamlMap -> {
                        other.entries.forEach { (key, value) ->
                            val existingEntry = merged.entries.singleOrNull { it.key.equivalentContentTo(key) }

                            if (existingEntry == null) {
                                merged.put(key, value)
                            }
                        }
                    }
                }
            }

        return merged
    }

    private fun readAlias(
        event: AliasEvent,
        path: YamlPath,
    ): WeightedNode {
        if (maxAliasCount == 0u) {
            throw ForbiddenAnchorOrAliasException("Parsing anchors and aliases is disabled. $ALIAS_LITERAL_HINT", path)
        }

        val anchor = event.alias

        val (resolvedNode, resolvedNodeWeight, resolvedDepth) =
            aliases.getOrElse(anchor) {
                throw UnknownAnchorException(anchor.value, path.withError(event.location))
            }

        checkDepth(resolvedDepth, path, event)
        val resultWeight = resolvedNodeWeight + 1u
        aliasCount += resultWeight

        if ((maxAliasCount != null) && (aliasCount > maxAliasCount)) {
            throw ForbiddenAnchorOrAliasException(
                "Maximum number of aliases has been reached.",
                path,
            )
        }

        return WeightedNode(
            node =
                resolvedNode.withPath(
                    path
                        .withAliasReference(anchor.value, event.location)
                        .withAliasDefinition(anchor.value, resolvedNode.location),
                ),
            weight = resultWeight,
            depth = resolvedDepth,
        )
    }

    private fun <T> Iterable<T>.second(): T = this.drop(1).first()

    private val Event.location: Location
        get() = Location(startMark!!.line + 1, startMark!!.column + 1)
}

/** Couples resolved content with its nested alias weight so repeated references count toward the budget. */
private data class WeightedNode(
    val node: YamlNode,
    val weight: UInt,
    val depth: Int = 0,
)

private val LEGACY_NULL_STRINGS = setOf("Null", "NULL")

private const val STRING_TAG = "tag:yaml.org,2002:str"

private const val SEQUENCE_TAG = "tag:yaml.org,2002:seq"
private const val MAPPING_TAG = "tag:yaml.org,2002:map"
