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
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor

/** Effective names for one object descriptor, validated before its properties are processed. */
@OptIn(ExperimentalSerializationApi::class)
internal class YamlPropertyNames(
    descriptor: SerialDescriptor,
    strategy: YamlNamingStrategy?,
) {
    val names: List<String>
    val indicesByName: Map<String, Int>

    init {
        val indices = linkedMapOf<String, Int>()
        names =
            List(descriptor.elementsCount) { index ->
                val sourceName = descriptor.getElementName(index)
                val name = strategy?.serialNameForYaml(sourceName) ?: sourceName
                val previousIndex = indices.put(name, index)
                if (previousIndex != null) {
                    throw SerializationException(
                        "Properties '${descriptor.getElementName(previousIndex)}' and '$sourceName' in '${descriptor.serialName}' " +
                            "both map to YAML key '$name'. Change @SerialName or the YAML naming strategy.",
                    )
                }
                name
            }
        indicesByName = indices
    }
}
