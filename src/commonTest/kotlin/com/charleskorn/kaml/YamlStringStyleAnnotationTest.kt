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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

class YamlStringStyleAnnotationTest :
    FlatFunSpec({
        for (quoteStyle in AmbiguousQuoteStyle.entries) {
            val quote = if (quoteStyle == AmbiguousQuoteStyle.SingleQuoted) "'" else "\""
            val enumValues =
                mapOf(
                    "123" to AnnotatedStyleKind.Number,
                    "True" to AnnotatedStyleKind.Boolean,
                    "ordinary" to AnnotatedStyleKind.Ordinary,
                    "NULL" to AnnotatedStyleKind.NULL,
                )
            val cases =
                listOf(
                    Triple("123", "123", "${quote}123$quote"),
                    Triple("True", "True", "${quote}True$quote"),
                    Triple("ordinary", "ordinary", "ordinary"),
                    Triple("NULL", "${quote}NULL$quote", "${quote}NULL$quote"),
                )
            for ((text, plain, ambiguous) in cases) {
                val expected =
                    mapOf(
                        SingleLineStringStyle.SingleQuoted to "'$text'",
                        SingleLineStringStyle.DoubleQuoted to "\"$text\"",
                        SingleLineStringStyle.Plain to plain,
                        SingleLineStringStyle.PlainExceptAmbiguous to ambiguous,
                    )
                for (configuredStyle in SingleLineStringStyle.entries) {
                    val yaml =
                        Yaml(
                            configuration =
                                YamlConfiguration(
                                    singleLineStringStyle = configuredStyle,
                                    ambiguousQuoteStyle = quoteStyle,
                                ),
                        )
                    test("annotations override $configuredStyle for '$text' with $quoteStyle ambiguous quoting") {
                        yaml.shouldRoundTripAnnotatedStyles(text, configuredStyle, expected)
                    }

                    test("enum annotations override $configuredStyle for '$text' with $quoteStyle ambiguous quoting") {
                        val kind = enumValues.getValue(text)
                        yaml.shouldRoundTripAnnotatedStyles(kind, configuredStyle, expected)
                        yaml.shouldRoundTripAnnotatedStyles<AnnotatedStyleKind?>(kind, configuredStyle, expected)
                    }
                }
            }

            val characters =
                listOf(
                    Triple('A', "A", "A"),
                    Triple('1', "1", "${quote}1$quote"),
                    Triple('~', "$quote~$quote", "$quote~$quote"),
                )
            for ((character, plain, ambiguous) in characters) {
                val expected =
                    mapOf(
                        SingleLineStringStyle.SingleQuoted to "'$character'",
                        SingleLineStringStyle.DoubleQuoted to "\"$character\"",
                        SingleLineStringStyle.Plain to plain,
                        SingleLineStringStyle.PlainExceptAmbiguous to ambiguous,
                    )
                for (configuredStyle in SingleLineStringStyle.entries) {
                    test("Char annotations override $configuredStyle for '$character' with $quoteStyle ambiguous quoting") {
                        val yaml =
                            Yaml(
                                configuration =
                                    YamlConfiguration(
                                        singleLineStringStyle = configuredStyle,
                                        ambiguousQuoteStyle = quoteStyle,
                                    ),
                            )
                        yaml.shouldRoundTripAnnotatedStyles(character, configuredStyle, expected)
                        yaml.shouldRoundTripAnnotatedStyles<Char?>(character, configuredStyle, expected)
                    }
                }
            }
        }

        test("keeps annotated nullable Char and enum properties null") {
            val expected = SingleLineStringStyle.entries.associateWith { "null" }
            Yaml.default.shouldRoundTripAnnotatedStyles<Char?>(null, SingleLineStringStyle.DoubleQuoted, expected)
            Yaml.default.shouldRoundTripAnnotatedStyles<AnnotatedStyleKind?>(null, SingleLineStringStyle.DoubleQuoted, expected)
        }
    })

private inline fun <reified T> Yaml.shouldRoundTripAnnotatedStyles(
    scalar: T,
    configuredStyle: SingleLineStringStyle,
    expected: Map<SingleLineStringStyle, String>,
) {
    val value =
        AnnotatedScalarStyles(
            before = scalar,
            singleQuoted = scalar,
            doubleQuoted = scalar,
            plain = scalar,
            ambiguous = scalar,
            after = scalar,
        )
    val output = encodeToString(value)

    output shouldBe
        """
        before: ${expected.getValue(configuredStyle)}
        singleQuoted: ${expected.getValue(SingleLineStringStyle.SingleQuoted)}
        doubleQuoted: ${expected.getValue(SingleLineStringStyle.DoubleQuoted)}
        plain: ${expected.getValue(SingleLineStringStyle.Plain)}
        ambiguous: ${expected.getValue(SingleLineStringStyle.PlainExceptAmbiguous)}
        after: ${expected.getValue(configuredStyle)}
        """.trimIndent() + "\n"
    decodeFromString<AnnotatedScalarStyles<T>>(output) shouldBe value
}

@Serializable
private data class AnnotatedScalarStyles<T>(
    val before: T,
    @YamlSingleLineStringStyle(SingleLineStringStyle.SingleQuoted)
    val singleQuoted: T,
    @YamlSingleLineStringStyle(SingleLineStringStyle.DoubleQuoted)
    val doubleQuoted: T,
    @YamlSingleLineStringStyle(SingleLineStringStyle.Plain)
    val plain: T,
    @YamlSingleLineStringStyle(SingleLineStringStyle.PlainExceptAmbiguous)
    val ambiguous: T,
    val after: T,
)

@Serializable
private enum class AnnotatedStyleKind {
    @SerialName("123")
    Number,

    @SerialName("True")
    Boolean,

    @SerialName("ordinary")
    Ordinary,
    NULL,
}
