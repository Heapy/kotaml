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

import com.charleskorn.kaml.testobjects.TestSealedStructure
import io.kotest.matchers.shouldBe
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class YamlPropertyNameWritingTest :
    FlatFunSpec({
        context("serializing null-like property names") {
            SingleLineStringStyle.entries.forEach { style ->
                test("round trips null-like serial names with $style strings") {
                    val yaml = Yaml(configuration = YamlConfiguration(singleLineStringStyle = style))
                    val value = NullLikePropertyNames(1, 2, 3, 4, 5, 6)

                    val text = yaml.encodeToString(NullLikePropertyNames.serializer(), value)

                    yaml.decodeFromString(NullLikePropertyNames.serializer(), text) shouldBe value
                    text shouldBe
                        """
                        "null": 1
                        "Null": 2
                        "NULL": 3
                        "~": 4
                        ? ""
                        : 5
                        ordinary: 6
                        """.trimIndent()
                }
            }

            listOf("null", "Null", "NULL", "~", "").forEach { name ->
                val encodedKey = if (name.isEmpty()) "? \"\"\n" else "\"$name\""
                test("round trips '$name' produced by a naming strategy") {
                    val yaml =
                        Yaml(
                            configuration =
                                YamlConfiguration(
                                    yamlNamingStrategy = YamlNamingStrategy { name },
                                ),
                        )
                    val value = NamedProperty(1)

                    val text = yaml.encodeToString(NamedProperty.serializer(), value)

                    yaml.decodeFromString(NamedProperty.serializer(), text) shouldBe value
                    text shouldBe "$encodedKey: 1"
                }

                test("round trips '$name' as the polymorphism property name") {
                    val yaml =
                        Yaml(
                            configuration =
                                YamlConfiguration(
                                    polymorphismStyle = PolymorphismStyle.Property,
                                    polymorphismPropertyName = name,
                                ),
                        )
                    val value = TestSealedStructure.SimpleSealedInt(1)

                    val text = yaml.encodeToString(TestSealedStructure.serializer(), value)

                    yaml.decodeFromString(TestSealedStructure.serializer(), text) shouldBe value
                    text shouldBe "$encodedKey: \"sealedInt\"\nvalue: 1"
                }
            }
        }
    })

@Serializable
private data class NullLikePropertyNames(
    @SerialName("null") val lowercase: Int,
    @SerialName("Null") val titlecase: Int,
    @SerialName("NULL") val uppercase: Int,
    @SerialName("~") val tilde: Int,
    @SerialName("") val empty: Int,
    val ordinary: Int,
)

@Serializable
private data class NamedProperty(
    val value: Int,
)
