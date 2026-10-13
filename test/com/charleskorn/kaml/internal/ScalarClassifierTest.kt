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

package com.charleskorn.kaml.internal

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.random.Random

/**
 * The classifier is hand-written for speed, so these tests check it against an independent
 * implementation built from regular expressions that spell out the YAML 1.2 Core Schema directly.
 */
class ScalarClassifierTest :
    FunSpec({
        val booleanCases =
            mapOf(
                "true" to ScalarKind.TRUE,
                "True" to ScalarKind.TRUE,
                "TRUE" to ScalarKind.TRUE,
                "false" to ScalarKind.FALSE,
                "False" to ScalarKind.FALSE,
                "FALSE" to ScalarKind.FALSE,
            )

        run {
            mapOf(
                "TrUe" to ScalarKind.STRING,
                "yes" to ScalarKind.STRING,
                "no" to ScalarKind.STRING,
                "on" to ScalarKind.STRING,
                "NO" to ScalarKind.STRING,
                "0" to ScalarKind.INT_DECIMAL,
                "-0" to ScalarKind.INT_DECIMAL,
                "123" to ScalarKind.INT_DECIMAL,
                "-123" to ScalarKind.INT_DECIMAL,
                "123456789012345678901234" to ScalarKind.INT_DECIMAL,
                "+7" to ScalarKind.INT_DECIMAL,
                "007" to ScalarKind.INT_DECIMAL,
                "-007" to ScalarKind.INT_DECIMAL,
                "0x1F" to ScalarKind.INT_RADIX,
                "0o11" to ScalarKind.INT_RADIX,
                "0x" to ScalarKind.STRING,
                "0x1G" to ScalarKind.STRING,
                "0o8" to ScalarKind.STRING,
                "0X11" to ScalarKind.STRING,
                // the Core Schema permits a sign only on the decimal integer production
                "-0x11" to ScalarKind.STRING,
                "+0x11" to ScalarKind.STRING,
                "-0o11" to ScalarKind.STRING,
                "+0o7" to ScalarKind.STRING,
                "1.5" to ScalarKind.FLOAT_DECIMAL,
                "1.10" to ScalarKind.FLOAT_DECIMAL,
                "-1.5e3" to ScalarKind.FLOAT_DECIMAL,
                "1e5" to ScalarKind.FLOAT_DECIMAL,
                "1E+5" to ScalarKind.FLOAT_DECIMAL,
                "0.25" to ScalarKind.FLOAT_DECIMAL,
                "+1.5" to ScalarKind.FLOAT_DECIMAL,
                ".5" to ScalarKind.FLOAT_DECIMAL,
                "-.5" to ScalarKind.FLOAT_DECIMAL,
                "1." to ScalarKind.FLOAT_DECIMAL,
                "007.5" to ScalarKind.FLOAT_DECIMAL,
                "1.e3" to ScalarKind.FLOAT_DECIMAL,
                "1.e+3" to ScalarKind.FLOAT_DECIMAL,
                "1.e-3" to ScalarKind.FLOAT_DECIMAL,
                ".5e2" to ScalarKind.FLOAT_DECIMAL,
                "-.5E+2" to ScalarKind.FLOAT_DECIMAL,
                "+007.E-2" to ScalarKind.FLOAT_DECIMAL,
                ".inf" to ScalarKind.INF_POSITIVE,
                ".Inf" to ScalarKind.INF_POSITIVE,
                "+.inf" to ScalarKind.INF_POSITIVE,
                "-.inf" to ScalarKind.INF_NEGATIVE,
                ".nan" to ScalarKind.NAN,
                ".NaN" to ScalarKind.NAN,
                "-.nan" to ScalarKind.STRING,
                ".nan5" to ScalarKind.STRING,
                ".infinity" to ScalarKind.STRING,
                "" to ScalarKind.STRING,
                "-" to ScalarKind.STRING,
                "+" to ScalarKind.STRING,
                "." to ScalarKind.STRING,
                "+." to ScalarKind.STRING,
                "-." to ScalarKind.STRING,
                "1.2.3" to ScalarKind.STRING,
                "1,5" to ScalarKind.STRING,
                "1_000" to ScalarKind.STRING,
                "1.5e" to ScalarKind.STRING,
                "1e+" to ScalarKind.STRING,
                "1.e-" to ScalarKind.STRING,
                "+e3" to ScalarKind.STRING,
                "0x1p3" to ScalarKind.STRING,
                ".e5" to ScalarKind.STRING,
                "1st" to ScalarKind.STRING,
                "nginx" to ScalarKind.STRING,
                "2026-08-30" to ScalarKind.STRING,
                // the Kotlin standard library accepts these, the YAML Core Schema does not
                "٣" to ScalarKind.STRING,
                "0x٣" to ScalarKind.STRING,
                "３" to ScalarKind.STRING,
            ).plus(booleanCases).forEach { (content, expected) ->
                test("classifies the scalar '$content' as $expected") {
                    classifyScalar(content) shouldBe expected
                }

                test("agrees with the regular expression implementation on the scalar '$content'") {
                    classifyScalar(content) shouldBe classifyByRegex(content)
                }
            }
        }

        for ((prefix, digits) in mapOf("0x" to "0123456789abcdefABCDEF", "0o" to "01234567")) {
            for (digit in digits) {
                test("accepts '$digit' at every position in a $prefix integer") {
                    for (content in radixSamples(prefix, digit)) {
                        withClue(content) {
                            classifyScalar(content) shouldBe ScalarKind.INT_RADIX
                        }
                    }
                }
            }

            test("rejects non-digits at every position in a $prefix integer") {
                val invalidDigits =
                    ('\u0000'..'\u007f').filter { it !in digits } +
                        listOf('٣', '３', 'Ａ', 'Ｆ', 'ａ', 'ｆ')
                for (digit in invalidDigits) {
                    for ((position, content) in radixSamples(prefix, digit).withIndex()) {
                        withClue("$prefix integer, character code ${digit.code}, sample $position") {
                            classifyScalar(content) shouldBe ScalarKind.STRING
                        }
                    }
                }
            }
        }

        test("recognizes only Core Schema boolean spellings among case variants and mutations") {
            val variants =
                listOf("true", "false").flatMap { literal ->
                    List(1 shl literal.length) { mask ->
                        buildString {
                            for ((index, character) in literal.withIndex()) {
                                append(if ((mask and (1 shl index)) == 0) character else character.uppercaseChar())
                            }
                        }
                    }
                }

            val random = Random(7)
            val alphabet = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ+-. \t\n"
            val samples =
                variants +
                    List(2_000) {
                        val characters = variants[random.nextInt(variants.size)].toMutableList()
                        when (random.nextInt(3)) {
                            0 -> characters.add(random.nextInt(characters.size + 1), alphabet[random.nextInt(alphabet.length)])
                            1 -> characters.removeAt(random.nextInt(characters.size))
                            2 -> characters[random.nextInt(characters.size)] = alphabet[random.nextInt(alphabet.length)]
                        }
                        characters.joinToString("")
                    }

            val disagreements =
                samples.mapNotNull { content ->
                    val expected = booleanCases[content] ?: ScalarKind.STRING
                    val actual = classifyScalar(content)
                    if (actual == expected) null else "$content: scanner=$actual expected=$expected"
                }

            disagreements shouldBe emptyList()
        }

        test("agrees with the regular expressions on every short combination of number syntax") {
            val disagreements = mutableListOf<String>()

            fun check(
                text: String,
                remaining: Int,
            ) {
                val actual = classifyScalar(text)
                val expected = classifyByRegex(text)
                if (actual != expected) disagreements += "$text: scanner=$actual regex=$expected"

                if (remaining > 0) {
                    for (character in "01+-.eExo") check("$text$character", remaining - 1)
                }
            }

            check("", 4)

            disagreements shouldBe emptyList()
        }

        test("agrees with the regular expression implementation on random number-like text") {
            val random = Random(7)
            val alphabet = "0123456789abcdefABCDEF+-.xXoOtTnNiI"
            val samples =
                List(20_000) {
                    val length = 1 + random.nextInt(6)
                    buildString { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
                }

            val disagreements =
                samples
                    .filter { classifyScalar(it) != classifyByRegex(it) }
                    .map { "$it: scanner=${classifyScalar(it)} regex=${classifyByRegex(it)}" }

            disagreements shouldBe emptyList()
        }
    })

private fun radixSamples(
    prefix: String,
    digit: Char,
): List<String> = listOf("$prefix$digit", "$prefix${digit}12", "${prefix}1${digit}2", "${prefix}12$digit")

private val boolRegex = Regex("true|True|TRUE|false|False|FALSE")
private val infRegex = Regex("[-+]?\\.(inf|Inf|INF)")
private val nanRegex = Regex("\\.(nan|NaN|NAN)")
private val radixIntRegex = Regex("0(x[0-9a-fA-F]+|o[0-7]+)")
private val yamlIntRegex = Regex("[-+]?[0-9]+")
private val yamlFloatRegex = Regex("[-+]?(\\.[0-9]+|[0-9]+(\\.[0-9]*)?)([eE][-+]?[0-9]+)?")

/** Independent implementation of the same rules, used only to cross-check [classifyScalar]. */
internal fun classifyByRegex(content: String): ScalarKind =
    when {
        boolRegex.matches(content) -> if (content[0] == 't' || content[0] == 'T') ScalarKind.TRUE else ScalarKind.FALSE
        infRegex.matches(content) -> if (content[0] == '-') ScalarKind.INF_NEGATIVE else ScalarKind.INF_POSITIVE
        nanRegex.matches(content) -> ScalarKind.NAN
        radixIntRegex.matches(content) -> ScalarKind.INT_RADIX
        yamlIntRegex.matches(content) -> ScalarKind.INT_DECIMAL
        yamlFloatRegex.matches(content) -> ScalarKind.FLOAT_DECIMAL
        else -> ScalarKind.STRING
    }
