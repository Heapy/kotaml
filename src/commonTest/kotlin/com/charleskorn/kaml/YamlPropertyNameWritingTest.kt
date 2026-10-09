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
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

class YamlPropertyNameWritingTest :
    FlatFunSpec({
        context("serializing merge-like property names") {
            SingleLineStringStyle.entries.forEach { style ->
                val yaml = Yaml(configuration = YamlConfiguration(singleLineStringStyle = style))

                test("round trips a merge-like serial name with $style strings") {
                    val value = MergeNamedProperty(1, 2)

                    val text = yaml.encodeToString(value)

                    text shouldBe "\"<<\": 1\nordinary: 2"
                    yaml.decodeFromString<MergeNamedProperty<Int>>(text) shouldBe value
                }

                test("round trips nested merge-like serial names with $style strings") {
                    val value = MergeNamedProperty(MergeNamedProperty(1, 2), 3)

                    val text = yaml.encodeToString(value)

                    text shouldBe "\"<<\":\n  \"<<\": 1\n  ordinary: 2\nordinary: 3"
                    yaml.decodeFromString<MergeNamedProperty<MergeNamedProperty<Int>>>(text) shouldBe value
                }

                val yamlWithNamingStrategy =
                    Yaml(
                        configuration =
                            YamlConfiguration(
                                singleLineStringStyle = style,
                                yamlNamingStrategy = YamlNamingStrategy { name -> if (name == "renamed") "<<" else name },
                            ),
                    )

                test("round trips a merge-like name produced by a naming strategy with $style strings") {
                    val value = StrategyNamedProperty(1, 2)

                    val text = yamlWithNamingStrategy.encodeToString(value)

                    text shouldBe "\"<<\": 1\nordinary: 2"
                    yamlWithNamingStrategy.decodeFromString<StrategyNamedProperty<Int>>(text) shouldBe value
                }

                test("round trips nested merge-like names produced by a naming strategy with $style strings") {
                    val value = StrategyNamedProperty(StrategyNamedProperty(1, 2), 3)

                    val text = yamlWithNamingStrategy.encodeToString(value)

                    text shouldBe "\"<<\":\n  \"<<\": 1\n  ordinary: 2\nordinary: 3"
                    yamlWithNamingStrategy.decodeFromString<StrategyNamedProperty<StrategyNamedProperty<Int>>>(text) shouldBe value
                }
            }
        }

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
                        """.trimIndent() + "\n"
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
                    text shouldBe "$encodedKey: 1\n"
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
                    text shouldBe "$encodedKey: \"sealedInt\"\nvalue: 1\n"
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

@Serializable
private data class MergeNamedProperty<T>(
    @SerialName("<<") val value: T,
    val ordinary: Int,
)

@Serializable
private data class StrategyNamedProperty<T>(
    @SerialName("renamed") val value: T,
    val ordinary: Int,
)
