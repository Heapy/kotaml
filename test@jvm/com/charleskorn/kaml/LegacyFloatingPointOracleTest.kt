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

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlin.random.Random

class LegacyFloatingPointOracleTest :
    FlatFunSpec({
        test("portable hexadecimal parsing matches JVM Float and Double rounding") {
            val random = Random(111)
            val compatibility = YamlReadCompatibility.LegacyV0_110()
            repeat(5000) {
                val digits = List(random.nextInt(1, 100)) { random.nextInt(16).digitToChar(16) }.joinToString("")
                val point = random.nextInt(digits.length + 1)
                val sign = if (random.nextBoolean()) "-" else "+"
                val exponent = random.nextInt(-1600, 1600)
                val suffix = listOf("", "f", "F", "d", "D").random(random)
                val text = "${sign}0x${digits.take(point)}.${digits.drop(point)}p$exponent$suffix"
                withClue(text) {
                    YamlScalar(text, YamlPath.root).toDouble(compatibility).toBits() shouldBe text.toDouble().toBits()
                    YamlScalar(text, YamlPath.root).toFloat(compatibility).toBits() shouldBe text.toFloat().toBits()
                }
            }
        }
    })
