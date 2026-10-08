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

package com.charleskorn.kaml.json

import com.charleskorn.kaml.YamlException
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlNull
import com.charleskorn.kaml.YamlPath
import com.charleskorn.kaml.YamlScalar
import com.charleskorn.kaml.YamlTaggedNode
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral

/** What to do with `.inf`, `-.inf` and `.nan`, which JSON has no notation for. */
public enum class NonFiniteNumbers {
    /** Throw [NonFiniteNumberException]. The default, because the other choices change the value. */
    THROW,

    /** Keep the original text as a JSON string, for example `".inf"`. */
    AS_STRING,

    /** Replace the value with JSON `null`. */
    AS_NULL,
}

/** Thrown when a YAML document holds a number that JSON cannot express. */
public class NonFiniteNumberException(
    public val content: String,
    path: YamlPath,
) : YamlException(
        "Value '$content' has no JSON representation. Pass a different NonFiniteNumbers policy to convert it anyway.",
        path,
    )

/**
 * Converts this YAML node tree into the equivalent kotlinx.serialization JSON node tree.
 *
 * ## Structure
 *
 * [YamlNull] becomes [JsonNull], [YamlList] becomes a [JsonArray], and [YamlMap] becomes a
 * [JsonObject] keyed by the text of each key. Map keys are always plain text and are already free
 * of duplicates, because the YAML reader rejects list, map, null and tagged keys, and [YamlMap]
 * rejects two keys with the same text.
 *
 * ## Scalars
 *
 * A quoted scalar (`plain == false`) without an explicit type tag becomes a JSON string, so `name: "123"` stays the
 * string `"123"`. A plain scalar is resolved with the YAML 1.2 core schema: `true`, `123`, `0x1F`,
 * `1.5` and `1e5` become JSON booleans and numbers. Plain `null`, `Null`, `NULL`, `~` and empty
 * content become [JsonNull], including in manually constructed scalars. Quoted versions stay strings.
 * Anything else becomes a string.
 *
 * Numbers keep their original text wherever JSON allows it, so `1.10` stays `1.10` and an integer
 * too large for a [Long] keeps every digit. Forms JSON does not accept are rewritten on the text,
 * never through [Double]: a leading `+` and leading zeros are dropped, `.5` gains its integer part
 * and `1.` loses its trailing point, and hexadecimal and octal integers are converted to decimal.
 * A hexadecimal or octal value too large for a [Long] cannot be rewritten and stays a JSON string.
 *
 * Rewriting on the text keeps the result identical on every target: `Double.toString` is not, so
 * `1.` would otherwise become `1.0` on the JVM and `1` on Kotlin/JS.
 *
 * `.inf`, `-.inf` and `.nan` have no JSON notation. [nonFiniteNumbers] decides what happens to them.
 *
 * ## Tags
 *
 * `!!str` forces a scalar to a JSON string and `!!null` forces it to JSON null. `!!int`, `!!bool`
 * and `!!float` resolve a scalar as the specified type even when quoted: `!!int "123"` becomes
 * the number 123. Their content must match that type under the YAML 1.2 core schema; incompatible
 * content or a non-scalar node throws [YamlException] with the value's path. `!!float` also accepts
 * decimal integers and uses [nonFiniteNumbers] for infinities and NaN.
 * Every other tag is dropped and the inner node is converted on its own, because JSON has no tags.
 *
 * ## Notes
 *
 * The `plain` flag defaults to `true` on the [YamlScalar] constructor, so a tree you build by hand
 * resolves types the same way a parsed one does: `YamlScalar("123", path)` becomes the number 123.
 *
 * @throws NonFiniteNumberException when the tree holds `.inf` or `.nan` and [nonFiniteNumbers] is
 * [NonFiniteNumbers.THROW].
 * @throws YamlException when a node is incompatible with its explicit `!!int`, `!!bool` or `!!float` tag.
 */
@ExperimentalSerializationApi
public fun YamlNode.toJsonElement(nonFiniteNumbers: NonFiniteNumbers = NonFiniteNumbers.THROW): JsonElement =
    when (this) {
        is YamlNull -> {
            JsonNull
        }

        is YamlScalar -> {
            toJsonPrimitive(nonFiniteNumbers)
        }

        is YamlList -> {
            JsonArray(items.map { it.toJsonElement(nonFiniteNumbers) })
        }

        is YamlMap -> {
            JsonObject(
                entries.entries.associate { (key, value) -> key.content to value.toJsonElement(nonFiniteNumbers) },
            )
        }

        is YamlTaggedNode -> {
            taggedToJsonElement(nonFiniteNumbers)
        }
    }

@ExperimentalSerializationApi
private fun YamlTaggedNode.taggedToJsonElement(nonFiniteNumbers: NonFiniteNumbers): JsonElement =
    when {
        tag == STRING_TAG && innerNode is YamlScalar -> JsonPrimitive((innerNode as YamlScalar).content)
        tag == NULL_TAG -> JsonNull
        tag == INTEGER_TAG || tag == BOOLEAN_TAG || tag == FLOAT_TAG -> typedScalarToJsonElement(nonFiniteNumbers)
        else -> innerNode.toJsonElement(nonFiniteNumbers)
    }

@ExperimentalSerializationApi
private fun YamlTaggedNode.typedScalarToJsonElement(nonFiniteNumbers: NonFiniteNumbers): JsonElement {
    val shortTag = "!!" + tag.substringAfterLast(':')
    val scalar = innerNode as? YamlScalar ?: throw YamlException("Tag '$shortTag' requires a scalar value.", path)
    val kind = classifyScalar(scalar.content)
    val valid =
        when (tag) {
            INTEGER_TAG -> {
                kind == ScalarKind.INT_DECIMAL || kind == ScalarKind.INT_RADIX
            }

            BOOLEAN_TAG -> {
                kind == ScalarKind.TRUE || kind == ScalarKind.FALSE
            }

            FLOAT_TAG -> {
                kind == ScalarKind.INT_DECIMAL || kind == ScalarKind.FLOAT_DECIMAL ||
                    kind == ScalarKind.INF_POSITIVE || kind == ScalarKind.INF_NEGATIVE || kind == ScalarKind.NAN
            }

            else -> {
                false
            }
        }
    if (!valid) throw YamlException("Value '${scalar.content}' is not valid for tag '$shortTag'.", scalar.path)

    return scalar.resolvedToJsonPrimitive(kind, nonFiniteNumbers)
}

@ExperimentalSerializationApi
private fun YamlScalar.toJsonPrimitive(nonFiniteNumbers: NonFiniteNumbers): JsonElement {
    if (!plain) return JsonPrimitive(content)
    if (content.isNullLiteral()) return JsonNull

    return resolvedToJsonPrimitive(classifyScalar(content), nonFiniteNumbers)
}

@ExperimentalSerializationApi
private fun YamlScalar.resolvedToJsonPrimitive(
    kind: ScalarKind,
    nonFiniteNumbers: NonFiniteNumbers,
): JsonElement =
    when (kind) {
        ScalarKind.TRUE -> JsonPrimitive(true)
        ScalarKind.FALSE -> JsonPrimitive(false)
        ScalarKind.INT_DECIMAL -> JsonUnquotedLiteral(canonicaliseDecimalInteger(content))
        ScalarKind.INT_RADIX -> parseRadixInteger(content)?.let { JsonPrimitive(it) } ?: JsonPrimitive(content)
        ScalarKind.FLOAT_DECIMAL -> JsonUnquotedLiteral(canonicaliseDecimalFloat(content))
        ScalarKind.INF_POSITIVE, ScalarKind.INF_NEGATIVE, ScalarKind.NAN -> applyPolicy(nonFiniteNumbers)
        ScalarKind.STRING -> JsonPrimitive(content)
    }

private fun YamlScalar.applyPolicy(nonFiniteNumbers: NonFiniteNumbers): JsonElement =
    when (nonFiniteNumbers) {
        NonFiniteNumbers.THROW -> throw NonFiniteNumberException(content, path)
        NonFiniteNumbers.AS_STRING -> JsonPrimitive(content)
        NonFiniteNumbers.AS_NULL -> JsonNull
    }

private const val STRING_TAG = "tag:yaml.org,2002:str"
private const val NULL_TAG = "tag:yaml.org,2002:null"
private const val INTEGER_TAG = "tag:yaml.org,2002:int"
private const val BOOLEAN_TAG = "tag:yaml.org,2002:bool"
private const val FLOAT_TAG = "tag:yaml.org,2002:float"
