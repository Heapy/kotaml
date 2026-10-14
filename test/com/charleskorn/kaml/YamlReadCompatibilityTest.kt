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

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okio.Buffer

class YamlReadCompatibilityTest :
    FlatFunSpec({
        mapOf("-0x11" to -17, "-0o11" to -9, "٣" to 3, "１２" to 12, "-１２" to -12, "0xＦＦ" to 255).forEach { (text, expected) ->
            test("reads legacy integer '$text' only with compatibility enabled") {
                val issues = mutableListOf<YamlReadCompatibilityIssue>()
                val compatibility = YamlReadCompatibility.LegacyV0_110(issues::add)
                val yaml = Yaml(configuration = YamlConfiguration(readCompatibility = compatibility))

                shouldThrow<YamlScalarFormatException> { Yaml.default.decodeFromString<Int>(text) }
                yaml.decodeFromString<Int>(text) shouldBe expected
                yaml.decodeFromString<Long>(text) shouldBe expected.toLong()
                yaml.decodeFromString<Short>(text) shouldBe expected.toShort()
                if (expected in Byte.MIN_VALUE..Byte.MAX_VALUE) yaml.decodeFromString<Byte>(text) shouldBe expected.toByte()

                issues.map { it.targetType } shouldBe if (expected <= Byte.MAX_VALUE) listOf("Int", "Long", "Short", "Byte") else listOf("Int", "Long", "Short")
                issues.forEach {
                    it.reason shouldBe YamlReadCompatibilityReason.LegacyInteger
                    it.path shouldBe YamlPath.root
                    it.location shouldBe Location(1, 1)
                    it.originalValue shouldBe text
                    it.replacement shouldBe expected.toString()
                }
            }
        }

        mapOf(
            "1f" to 1.0,
            "1d" to 1.0,
            "1F" to 1.0,
            "1D" to 1.0,
            "0x1p3" to 8.0,
            "-0x1.8p+2" to -6.0,
            "0X1.Ap2F" to 6.5,
            "0x2" to 2.0,
            "0o11" to 9.0,
            "0b101" to 5.0,
            "Infinity" to Double.POSITIVE_INFINITY,
            "+Infinity" to Double.POSITIVE_INFINITY,
            "-Infinity" to Double.NEGATIVE_INFINITY,
            "NaN" to Double.NaN,
            "+NaN" to Double.NaN,
            "-NaN" to Double.NaN,
            "-0.0f" to -0.0,
        ).forEach { (text, expected) ->
            test("reads legacy floating point '$text' only with compatibility enabled") {
                val issues = mutableListOf<YamlReadCompatibilityIssue>()
                val compatibility = YamlReadCompatibility.LegacyV0_110(issues::add)
                val yaml = Yaml(configuration = YamlConfiguration(readCompatibility = compatibility))

                shouldThrow<YamlScalarFormatException> { Yaml.default.decodeFromString<Double>(text) }
                shouldThrow<YamlScalarFormatException> { Yaml.default.decodeFromString<Float>(text) }
                yaml.decodeFromString<Double>(text).toBits() shouldBe expected.toBits()
                yaml.decodeFromString<Float>(text).toBits() shouldBe expected.toFloat().toBits()

                issues.map { it.targetType } shouldBe listOf("Double", "Float")
                issues.forEach {
                    it.reason shouldBe YamlReadCompatibilityReason.LegacyFloatingPoint
                    it.originalValue shouldBe text
                    it.path shouldBe YamlPath.root
                    if (it.targetType == "Float") {
                        Yaml.default.decodeFromString<Float>(it.replacement).toBits() shouldBe expected.toFloat().toBits()
                    } else {
                        Yaml.default.decodeFromString<Double>(it.replacement).toBits() shouldBe expected.toBits()
                    }
                }
            }
        }

        test("direct scalar conversions require explicit compatibility and report every conversion") {
            val issues = mutableListOf<YamlReadCompatibilityIssue>()
            val compatibility = YamlReadCompatibility.LegacyV0_110(issues::add)
            val integer = YamlScalar("-0x11", YamlPath.root)
            val float = YamlScalar("0x1p3", YamlPath.root)

            shouldThrow<YamlScalarFormatException> { integer.toInt() }
            shouldThrow<YamlScalarFormatException> { integer.toInt(YamlReadCompatibility.Strict) }
            shouldThrow<YamlScalarFormatException> { float.toDouble() }
            integer.toByte(compatibility) shouldBe (-17).toByte()
            integer.toShort(compatibility) shouldBe (-17).toShort()
            integer.toInt(compatibility) shouldBe -17
            integer.toLong(compatibility) shouldBe -17L
            float.toFloat(compatibility) shouldBe 8.0f
            float.toDouble(compatibility) shouldBe 8.0
            issues.map { it.targetType } shouldBe listOf("Byte", "Short", "Int", "Long", "Float", "Double")
        }

        test("diagnostics retain a nested scalar's path and source location") {
            val issues = mutableListOf<YamlReadCompatibilityIssue>()
            val yaml = Yaml(configuration = YamlConfiguration(readCompatibility = YamlReadCompatibility.LegacyV0_110(issues::add)))

            yaml.decodeFromString<Map<String, List<Int>>>("numbers:\n  - -0x11") shouldBe mapOf("numbers" to listOf(-17))
            issues.single().path.toHumanReadableString() shouldBe "numbers[0]"
            issues.single().location shouldBe Location(2, 5)
        }

        listOf("Null", "NULL").forEach { text ->
            test("preserves plain '$text' values including nullable strings and node parsing") {
                val issues = mutableListOf<YamlReadCompatibilityIssue>()
                val yaml = Yaml(configuration = YamlConfiguration(readCompatibility = YamlReadCompatibility.LegacyV0_110(issues::add)))

                Yaml.default.decodeFromString<String?>(text) shouldBe null
                yaml.decodeFromString<String?>(text) shouldBe text
                yaml.parseToYamlNode(text).shouldBeInstanceOf<YamlScalar>().content shouldBe text
                issues.size shouldBe 2
                issues.forEach {
                    it.reason shouldBe YamlReadCompatibilityReason.LegacyNullValue
                    it.originalValue shouldBe text
                    it.targetType shouldBe null
                    it.replacement shouldBe "\"$text\""
                }
            }

            test("accepts and diagnoses a plain '$text' map key") {
                val issues = mutableListOf<YamlReadCompatibilityIssue>()
                val yaml = Yaml(configuration = YamlConfiguration(readCompatibility = YamlReadCompatibility.LegacyV0_110(issues::add)))

                shouldThrow<MalformedYamlException> { Yaml.default.parseToYamlNode("outer:\n  $text: 1") }
                yaml.decodeFromString<Map<String, Map<String, Int>>>("outer:\n  $text: 1") shouldBe mapOf("outer" to mapOf(text to 1))
                issues.single().reason shouldBe YamlReadCompatibilityReason.LegacyNullKey
                issues.single().path.toHumanReadableString() shouldBe "outer.$text"
                issues.single().location shouldBe Location(2, 3)
                issues.single().replacement shouldBe "\"$text\""
            }
        }

        test("does not report Core Schema numbers, actual nulls, quoted null strings or numeric-looking strings") {
            val yaml = Yaml(configuration = YamlConfiguration(readCompatibility = YamlReadCompatibility.LegacyV0_110 { error("Unexpected compatibility event: $it") }))
            for (text in listOf("1", "+7", "1.25", "1e30", ".inf", "-.inf", "+.inf", ".nan")) {
                yaml.decodeFromString<Double>(text).toBits() shouldBe Yaml.default.decodeFromString<Double>(text).toBits()
                yaml.decodeFromString<Float>(text).toBits() shouldBe Yaml.default.decodeFromString<Float>(text).toBits()
            }
            yaml.decodeFromString<Int>("0x11") shouldBe 17
            yaml.decodeFromString<Int>("0o11") shouldBe 9
            yaml.decodeFromString<Boolean>("TRUE") shouldBe true
            for (text in listOf("Infinity", "NaN", "1f", "0x1p3")) yaml.decodeFromString<String>(text) shouldBe text
            for (text in listOf("null", "~")) yaml.decodeFromString<String?>(text) shouldBe null
            yaml.decodeFromString<Map<String, String?>>("empty:") shouldBe mapOf("empty" to null)
            for (text in listOf("Null", "NULL")) {
                yaml.decodeFromString<String?>("\"$text\"") shouldBe text
                yaml.decodeFromString<Map<String, Int>>("'$text': 1") shouldBe mapOf(text to 1)
            }
        }

        test("preserves integer range checks and rejects malformed legacy numbers without reporting") {
            val compatibility = YamlReadCompatibility.LegacyV0_110 { error("Unexpected compatibility event: $it") }
            shouldThrow<YamlScalarFormatException> { YamlScalar("-0x81", YamlPath.root).toByte(compatibility) }
            shouldThrow<YamlScalarFormatException> { YamlScalar("-0x8001", YamlPath.root).toShort(compatibility) }
            shouldThrow<YamlScalarFormatException> { YamlScalar("-0x80000001", YamlPath.root).toInt(compatibility) }
            shouldThrow<YamlScalarFormatException> { YamlScalar("-0x8000000000000001", YamlPath.root).toLong(compatibility) }
            shouldThrow<YamlScalarFormatException> { YamlScalar("１２８", YamlPath.root).toByte(compatibility) }
            for (text in listOf("a", "0x", "-0o", "0o8", "1_0", "1.5", "+", "")) {
                shouldThrow<YamlScalarFormatException> { YamlScalar(text, YamlPath.root).toInt(compatibility) }
            }
            for (text in listOf("f", "Infinityf", "NaNf", "0x", "0x1p", "0x1p+", "0x1p1.0", "0x1p3fF", "0x1..2p0", "0b2", "-0x2", "1e-", "")) {
                shouldThrow<YamlScalarFormatException> { YamlScalar(text, YamlPath.root).toFloat(compatibility) }
                shouldThrow<YamlScalarFormatException> { YamlScalar(text, YamlPath.root).toDouble(compatibility) }
            }
        }

        test("retains the minimum signed integer values") {
            val compatibility = YamlReadCompatibility.LegacyV0_110()
            YamlScalar("-0x80", YamlPath.root).toByte(compatibility) shouldBe Byte.MIN_VALUE
            YamlScalar("-0x8000", YamlPath.root).toShort(compatibility) shouldBe Short.MIN_VALUE
            YamlScalar("-0x80000000", YamlPath.root).toInt(compatibility) shouldBe Int.MIN_VALUE
            YamlScalar("-0x8000000000000000", YamlPath.root).toLong(compatibility) shouldBe Long.MIN_VALUE
        }

        test("rounds hexadecimal floats at normal, subnormal and overflow boundaries") {
            val compatibility = YamlReadCompatibility.LegacyV0_110()
            mapOf(
                "0x1.00000000000008p0" to 1.0,
                "0x1.00000000000008001p0" to Double.fromBits(1.0.toBits() + 1),
                "0x1p-1074" to Double.MIN_VALUE,
                "0x1p-1075" to 0.0,
                "-0x1p-1075" to -0.0,
                "0x1.0000000000001p-1075" to Double.MIN_VALUE,
                "0x1.fffffffffffffp1023" to Double.MAX_VALUE,
                "0x1.fffffffffffff8p1023" to Double.POSITIVE_INFINITY,
                "0x0p99999999999999999999" to 0.0,
                "0x1p99999999999999999999" to Double.POSITIVE_INFINITY,
                "-0x1p-99999999999999999999" to -0.0,
            ).forEach { (text, expected) ->
                YamlScalar(text, YamlPath.root).toDouble(compatibility).toBits() shouldBe expected.toBits()
            }
            mapOf(
                "0x1.000001p0" to 1.0f,
                "0x1.00000100000000001p0" to Float.fromBits(1.0f.toBits() + 1),
                "0x1p-149" to Float.MIN_VALUE,
                "0x1p-150" to 0.0f,
                "0x1.00000001p-150" to Float.MIN_VALUE,
                "0x1.fffffep127" to Float.MAX_VALUE,
                "0x1.ffffffp127" to Float.POSITIVE_INFINITY,
            ).forEach { (text, expected) ->
                YamlScalar(text, YamlPath.root).toFloat(compatibility).toBits() shouldBe expected.toBits()
            }
        }

        test("supports reading from a source and converting an already parsed scalar") {
            val yaml = Yaml(configuration = YamlConfiguration(readCompatibility = YamlReadCompatibility.LegacyV0_110()))
            yaml.decodeFromSource<Int>(Buffer().writeUtf8("-0x11")) shouldBe -17
            yaml.decodeFromYamlNode<Double>(Yaml.default.parseToYamlNode("Infinity")) shouldBe Double.POSITIVE_INFINITY
            yaml.decodeFromString<Double>("\" 1f \"") shouldBe 1.0
        }

        test("allows migrating a legacy document and reading the new output in strict mode") {
            val issues = mutableListOf<YamlReadCompatibilityIssue>()
            val yaml = Yaml(configuration = YamlConfiguration(readCompatibility = YamlReadCompatibility.LegacyV0_110(issues::add)))
            val value = yaml.decodeFromString<LegacyDocument>("count: -0x11\nrate: Infinity\nname: Null\nNull: 7")
            value shouldBe LegacyDocument(-17, Double.POSITIVE_INFINITY, "Null", 7)
            issues.size shouldBe 4
            issues.map { it.reason }.toSet() shouldBe YamlReadCompatibilityReason.entries.toSet()
            issues.clear()

            val output = yaml.encodeToString(value)
            output shouldBe "count: -17\nrate: .inf\nname: \"Null\"\n\"Null\": 7\n"
            Yaml.default.decodeFromString<LegacyDocument>(output) shouldBe value
            yaml.decodeFromString<LegacyDocument>(output) shouldBe value
            issues shouldBe emptyList()
        }

        test("reports null resolution once at an anchor definition while preserving alias values") {
            val issues = mutableListOf<YamlReadCompatibilityIssue>()
            val yaml = Yaml(configuration = YamlConfiguration(anchorsAndAliases = AnchorsAndAliases.Permitted(), readCompatibility = YamlReadCompatibility.LegacyV0_110(issues::add)))
            yaml.decodeFromString<List<String?>>("- &name Null\n- *name") shouldBe listOf("Null", "Null")
            issues.size shouldBe 1
        }

        test("keeps malformed documents invalid and propagates callback failures") {
            val compatibility = YamlReadCompatibility.LegacyV0_110 { error("Unexpected compatibility event") }
            val yaml = Yaml(configuration = YamlConfiguration(readCompatibility = compatibility))
            for (text in listOf("null: 1", "~: 1", "!thing Null: 1", "[1, 2]: 3")) {
                shouldThrow<MalformedYamlException> { yaml.parseToYamlNode(text) }
            }

            val failure = IllegalStateException("Cannot report migration issue")
            val failing = YamlReadCompatibility.LegacyV0_110 { throw failure }
            shouldThrow<IllegalStateException> { YamlScalar("-0x11", YamlPath.root).toInt(failing) } shouldBe failure
            shouldThrow<IllegalStateException> { Yaml(configuration = YamlConfiguration(readCompatibility = failing)).parseToYamlNode("Null") } shouldBe failure
        }
    })

@Serializable
private data class LegacyDocument(
    val count: Int,
    val rate: Double,
    val name: String?,
    @SerialName("Null") val key: Int,
)
