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

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlReadCompatibility
import com.charleskorn.kaml.YamlReadCompatibilityReason
import com.charleskorn.kaml.YamlScalar
import com.charleskorn.kaml.YamlScalarFormatException
import com.charleskorn.kaml.report
import kotlinx.serialization.builtins.serializer
import kotlin.math.pow

internal inline fun <T : Any> YamlScalar.readWithCompatibility(
    compatibility: YamlReadCompatibility,
    reason: YamlReadCompatibilityReason,
    targetType: String,
    strict: () -> T,
    legacy: () -> T?,
): T {
    val value =
        try {
            return strict()
        } catch (error: YamlScalarFormatException) {
            if (compatibility !is YamlReadCompatibility.LegacyV0_110) throw error
            legacy() ?: throw error
        }

    if (compatibility.onUse != null) {
        val replacement =
            // JS represents Int, Float and Double as numbers, so runtime type checks cannot select the serializer.
            when (targetType) {
                "Float" -> Yaml.default.encodeToString(Float.serializer(), value as Float)
                "Double" -> Yaml.default.encodeToString(Double.serializer(), value as Double)
                else -> value.toString()
            }
        compatibility.report(reason, path, content, targetType, replacement)
    }
    return value
}

internal fun <T : Any> String.toLegacyIntegerOrNull(converter: (String, Int) -> T?): T? {
    val (digits, radix) =
        when {
            startsWith("-0x") -> "-" + substring(3) to 16
            startsWith("0x") -> substring(2) to 16
            startsWith("-0o") -> "-" + substring(3) to 8
            startsWith("0o") -> substring(2) to 8
            else -> this to 10
        }
    val normalized = StringBuilder(digits.length)
    for ((index, char) in digits.withIndex()) {
        if (index == 0 && (char == '+' || char == '-')) {
            normalized.append(char)
        } else {
            val digit = char.digitToIntOrNull(radix) ?: return null
            normalized.append(digit.digitToChar(radix))
        }
    }
    return converter(normalized.toString(), radix)
}

internal fun String.toLegacyDoubleOrNull(): Double? = toLegacyFloatingPointOrNull(singlePrecision = false)

internal fun String.toLegacyFloatOrNull(): Float? = toLegacyFloatingPointOrNull(singlePrecision = true)?.toFloat()

private fun String.toLegacyFloatingPointOrNull(singlePrecision: Boolean): Double? {
    var text = trim { it <= ' ' }
    when (text) {
        "Infinity", "+Infinity" -> return Double.POSITIVE_INFINITY
        "-Infinity" -> return Double.NEGATIVE_INFINITY
        "NaN", "+NaN", "-NaN" -> return Double.NaN
    }

    val unsigned = text.removePrefix("+").removePrefix("-")
    val radixPrefix = unsigned.startsWith("0x", ignoreCase = true) || unsigned.startsWith("0o", ignoreCase = true) || unsigned.startsWith("0b", ignoreCase = true)
    if ((!radixPrefix || text.any { it == 'p' || it == 'P' }) && text.lastOrNull() in listOf('f', 'F', 'd', 'D')) {
        text = text.dropLast(1)
    }

    return when (classifyScalar(text)) {
        ScalarKind.INT_DECIMAL, ScalarKind.FLOAT_DECIMAL -> {
            if (singlePrecision) text.toFloatOrNull()?.toDouble() else text.toDoubleOrNull()
        }

        else -> {
            parseLegacyRadixFloat(text, singlePrecision)
        }
    }
}

// Parse the legacy JVM hexadecimal floats and JS radix integers in common code so migration does
// not depend on the host's String.toDouble grammar. Round directly to the requested IEEE precision.
private fun parseLegacyRadixFloat(
    text: String,
    singlePrecision: Boolean,
): Double? {
    val negative = text.startsWith('-')
    val start = if (negative || text.startsWith('+')) 1 else 0
    if (text.length < start + 3 || text[start] != '0') return null
    val digitBits =
        when (text[start + 1]) {
            'x', 'X' -> 4
            'o', 'O' -> 3
            'b', 'B' -> 1
            else -> return null
        }
    val exponentIndex = text.indexOfFirst { it == 'p' || it == 'P' }
    if (exponentIndex >= 0 && digitBits != 4) return null
    if (exponentIndex < 0 && start != 0) return null
    val end = if (exponentIndex < 0) text.length else exponentIndex
    val explicitExponent = if (exponentIndex < 0) 0L else text.substring(exponentIndex + 1).binaryExponentOrNull() ?: return null

    var digitCount = 0
    var point = -1
    var firstNonZero = -1
    var leadingBit = 0
    for (index in start + 2..<end) {
        val char = text[index]
        if (char == '.') {
            if (point >= 0 || exponentIndex < 0) return null
            point = digitCount
        } else {
            val digit = char.asciiDigitOrNull(1 shl digitBits) ?: return null
            if (digit != 0 && firstNonZero < 0) {
                firstNonZero = digitCount
                leadingBit = 31 - digit.countLeadingZeroBits()
            }
            digitCount++
        }
    }
    if (digitCount == 0) return null
    if (firstNonZero < 0) return if (negative) -0.0 else 0.0
    if (point < 0) point = digitCount

    val exponent = explicitExponent + (point.toLong() - firstNonZero - 1) * digitBits + leadingBit
    val precision = if (singlePrecision) 24 else 53
    val minUnitExponent = if (singlePrecision) -149 else -1074
    val maxExponent = if (singlePrecision) 127 else 1023
    if (exponent > maxExponent) return if (negative) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
    if (exponent < minUnitExponent - 1) return if (negative) -0.0 else 0.0

    // Subnormal values retain fewer bits. Keeping the guard and sticky bits also handles halfway
    // cases, including the tie between zero and the smallest subnormal, using ties-to-even.
    val keepBits = (exponent - minUnitExponent + 1).coerceIn(0, precision.toLong()).toInt()
    var significand = 0L
    var significantBits = 0
    var started = false
    var guard = false
    var sticky = false
    for (index in start + 2..<end) {
        if (text[index] == '.') continue
        val digit = text[index].asciiDigitOrNull(1 shl digitBits)!!
        for (bitIndex in digitBits - 1 downTo 0) {
            val bit = (digit shr bitIndex) and 1
            if (!started && bit == 0) continue
            started = true
            when {
                significantBits < keepBits -> significand = (significand shl 1) or bit.toLong()
                significantBits == keepBits -> guard = bit != 0
                bit != 0 -> sticky = true
            }
            // Only the retained bits and the guard position need counting.
            if (significantBits <= keepBits) significantBits++
        }
    }
    if (significantBits < keepBits) significand = significand shl (keepBits - significantBits)
    if (guard && (sticky || significand and 1L != 0L)) significand++
    val result = significand.toDouble() * 2.0.pow((exponent - keepBits + 1).toInt())
    return if (negative) -result else result
}

private fun Char.asciiDigitOrNull(radix: Int): Int? {
    val digit =
        when (this) {
            in '0'..'9' -> this - '0'
            in 'a'..'f' -> this - 'a' + 10
            in 'A'..'F' -> this - 'A' + 10
            else -> return null
        }
    return digit.takeIf { it < radix }
}

private fun String.binaryExponentOrNull(): Long? {
    val negative = startsWith('-')
    val start = if (negative || startsWith('+')) 1 else 0
    if (length == start) return null
    var exponent = 0L
    for (index in start..<length) {
        val digit = this[index].asciiDigitOrNull(10) ?: return null
        // Larger than any exponent offset a Kotlin String's mantissa can contribute.
        exponent = (exponent * 10 + digit).coerceAtMost(1L shl 40)
    }
    return if (negative) -exponent else exponent
}
