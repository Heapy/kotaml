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

package com.charleskorn.kaml.fuzz

import com.charleskorn.kaml.MultiLineStringStyle
import com.charleskorn.kaml.SequenceStyle
import com.charleskorn.kaml.SingleLineStringStyle
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlNamingStrategy
import com.code_intelligence.jazzer.junit.FuzzTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Timeout

class YamlWriterFuzzTest {
    @Timeout(10)
    @FuzzTest
    fun roundTrip(input: ByteArray) {
        if (input.isEmpty() || input.size > 4096) return
        // Stable corpus format: one options byte, then UTF-8 text. Bits 0..1: single-line
        // style; 2..4: multiline style modulo 5; bit 5: flow; bit 6: omit defaults.
        val options = input[0].toInt() and 0xff
        val value = input.decodeToString(startIndex = 1)
        val yaml =
            Yaml(
                configuration =
                    YamlConfiguration(
                        singleLineStringStyle = SingleLineStringStyle.entries[options and 3],
                        multiLineStringStyle = MultiLineStringStyle.entries[(options shr 2 and 7) % MultiLineStringStyle.entries.size],
                        sequenceStyle = if (options and 32 == 0) SequenceStyle.Block else SequenceStyle.Flow,
                        encodeDefaults = options and 64 == 0,
                        yamlNamingStrategy = YamlNamingStrategy.SnakeCase,
                    ),
            )
        val scalar = yaml.encodeToString(value)
        check(yaml.decodeFromString<String>(scalar) == value) { "Scalar round trip failed for ${input.toHexString()}" }
        val model = FuzzRecord(value, mapOf(value to listOf(null, value, "null")))
        val document = yaml.encodeToString(model)
        check(yaml.decodeFromString<FuzzRecord>(document) == model) { "Model round trip failed for ${input.toHexString()}: $document" }
    }
}

@Serializable
private data class FuzzRecord(
    val displayName: String,
    val attributes: Map<String, List<String?>>,
    val enabled: Boolean = true,
)
