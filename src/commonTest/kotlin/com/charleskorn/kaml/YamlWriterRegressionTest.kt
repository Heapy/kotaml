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

import io.kotest.matchers.shouldBe
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

class YamlWriterRegressionTest :
    FlatFunSpec({
        test("folded wrapping preserves more-indented lines as values and map keys") {
            for (width in listOf(12, 80)) {
                val yaml = Yaml(configuration = YamlConfiguration(multiLineStringStyle = MultiLineStringStyle.Folded, breakScalarsAt = width))
                for (prefix in listOf("", "first\n")) {
                    val value = prefix + " " + "a".repeat(width) + " tail\n"
                    yaml.decodeFromString<String>(yaml.encodeToString(value)) shouldBe value
                    val model = WriterRegressionRecord(value, 1, mapOf(value to listOf(value)))
                    yaml.decodeFromString<WriterRegressionRecord>(yaml.encodeToString(model)) shouldBe model
                }
            }
        }
        test("preserves merge-like keys and scalar line breaks in every writer style") {
            for (single in SingleLineStringStyle.entries) {
                for (multi in MultiLineStringStyle.entries) {
                    val yaml = Yaml(configuration = YamlConfiguration(singleLineStringStyle = single, multiLineStringStyle = multi))
                    for (value in listOf("<<", "\r", "\u0085", "\u2028", "\u2029", "a\u0085b\n", "key:\nvalue")) {
                        yaml.decodeFromString<String>(yaml.encodeToString(value)) shouldBe value
                        val map = mapOf(value to value)
                        yaml.decodeFromString<Map<String, String>>(yaml.encodeToString(map)) shouldBe map
                    }
                }
            }
        }
    })

@Serializable
private data class WriterRegressionRecord(
    val displayName: String,
    val revision: Long,
    val attributes: Map<String, List<String?>>,
    val enabled: Boolean = true,
)
