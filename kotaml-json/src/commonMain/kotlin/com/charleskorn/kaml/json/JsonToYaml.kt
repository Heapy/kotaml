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

import com.charleskorn.kaml.Location
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlNull
import com.charleskorn.kaml.YamlPath
import com.charleskorn.kaml.YamlScalar
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Converts this JSON node tree into the equivalent YAML node tree.
 *
 * This direction always succeeds. JSON is a subset of YAML, and both models store a scalar the
 * same way: as text plus a flag saying whether it was quoted. A JSON string becomes a scalar with
 * `plain == false`, so it stays a string, and a JSON number or boolean becomes a plain scalar
 * carrying its exact original text. `1.10` stays `1.10` and a number too large for a [Long] keeps
 * every digit.
 *
 * [JsonNull] becomes [YamlNull], a [JsonArray] becomes a [YamlList], and a [JsonObject] becomes a
 * [YamlMap] whose keys are quoted scalars. JSON object keys are unique, so the duplicate key check
 * in [YamlMap] can never fire.
 *
 * Converting back with [toJsonElement] returns an equal tree, unless the JSON was built by hand
 * out of text that is not valid JSON. `JsonUnquotedLiteral("hello")` is not a JSON number, so it
 * comes back as the string `"hello"`.
 *
 * Every node is given the location line 1, column 1, because there is no source document behind
 * it. The paths identify a position in the tree, not in any text, so an error reported against one
 * of these nodes names the right property but points at no useful line.
 *
 * @param path where this tree sits. Pass a different path to graft the result into a larger tree.
 */
@ExperimentalSerializationApi
public fun JsonElement.toYamlNode(path: YamlPath = YamlPath.root): YamlNode =
    when (this) {
        // JsonNull is itself a JsonPrimitive, so it has to be matched first.
        is JsonNull -> {
            YamlNull(path)
        }

        is JsonPrimitive -> {
            YamlScalar(content, path, plain = !isString)
        }

        is JsonArray -> {
            YamlList(mapIndexed { index, item -> item.toYamlNode(path.withListEntry(index, SYNTHETIC_LOCATION)) }, path)
        }

        is JsonObject -> {
            YamlMap(
                entries.associate { (key, value) ->
                    val keyPath = path.withMapElementKey(key, SYNTHETIC_LOCATION)

                    YamlScalar(key, keyPath, plain = false) to value.toYamlNode(keyPath.withMapElementValue(SYNTHETIC_LOCATION))
                },
                path,
            )
        }
    }

private val SYNTHETIC_LOCATION = Location(1, 1)
