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

/** Scalar kinds needed for reading numbers and booleans and choosing string quoting. */
internal enum class ScalarKind {
    TRUE,
    FALSE,

    /** Decimal integer, including forms with a leading sign or zeros. */
    INT_DECIMAL,

    /** Hexadecimal or octal integer. */
    INT_RADIX,

    /** Decimal floating point number with a point, an exponent, or both. */
    FLOAT_DECIMAL,

    INF_POSITIVE,
    INF_NEGATIVE,
    NAN,
    STRING,
}

/** Recognizes Core Schema null text; callers handle scalar style and tags. */
internal fun String.isNullLiteral(): Boolean = isEmpty() || this == "null" || this == "Null" || this == "NULL" || this == "~"

/**
 * Classifies a plain scalar in a single pass over its characters, without allocating.
 *
 * Only ASCII digits count. The Kotlin standard library accepts Unicode digits such as `٣`, which
 * the YAML 1.2 Core Schema does not, so classification must not lean on it.
 */
internal fun classifyScalar(content: String): ScalarKind {
    if (content.isEmpty()) return ScalarKind.STRING

    return when (val first = content[0]) {
        't' -> if (content == "true") ScalarKind.TRUE else ScalarKind.STRING
        'T' -> if (content == "True" || content == "TRUE") ScalarKind.TRUE else ScalarKind.STRING
        'f' -> if (content == "false") ScalarKind.FALSE else ScalarKind.STRING
        'F' -> if (content == "False" || content == "FALSE") ScalarKind.FALSE else ScalarKind.STRING
        '-', '+', '.' -> classifyNumber(content)
        else -> if (first in '0'..'9') classifyNumber(content) else ScalarKind.STRING
    }
}

private fun classifyNumber(content: String): ScalarKind {
    val length = content.length
    var index = if (content[0] == '-' || content[0] == '+') 1 else 0
    if (index == length) return ScalarKind.STRING

    // The Core Schema permits a sign only on the decimal integer production.
    if (index == 0 && content[index] == '0' && index + 2 < length) {
        when (content[index + 1]) {
            'x' -> return if (isRadixDigits(content, index + 2, 16)) ScalarKind.INT_RADIX else ScalarKind.STRING
            'o' -> return if (isRadixDigits(content, index + 2, 8)) ScalarKind.INT_RADIX else ScalarKind.STRING
        }
    }

    val integerStart = index
    while (index < length && content[index] in '0'..'9') {
        index++
    }
    val hasInteger = index > integerStart

    if (index == length) return ScalarKind.INT_DECIMAL

    if (content[index] == '.') {
        if (!hasInteger) {
            val special = classifySpecial(content, index, length, negative = content[0] == '-')
            if (special != null) return special
        }

        index++
        val fractionStart = index
        while (index < length && content[index] in '0'..'9') {
            index++
        }
        if (!hasInteger && index == fractionStart) return ScalarKind.STRING
    } else if (!hasInteger) {
        return ScalarKind.STRING
    }

    if (index < length && (content[index] == 'e' || content[index] == 'E')) {
        index++
        if (index < length && (content[index] == '-' || content[index] == '+')) index++

        val exponentStart = index
        while (index < length && content[index] in '0'..'9') {
            index++
        }
        if (index == exponentStart) return ScalarKind.STRING
    }

    return if (index == length) ScalarKind.FLOAT_DECIMAL else ScalarKind.STRING
}

private fun classifySpecial(
    content: String,
    index: Int,
    length: Int,
    negative: Boolean,
): ScalarKind? {
    if (length - index != 4) return null

    return when {
        content.regionMatches(index, ".inf", 0, 4) ||
            content.regionMatches(index, ".Inf", 0, 4) ||
            content.regionMatches(index, ".INF", 0, 4) -> {
            if (negative) ScalarKind.INF_NEGATIVE else ScalarKind.INF_POSITIVE
        }

        index == 0 &&
            (
                content.regionMatches(0, ".nan", 0, 4) ||
                    content.regionMatches(0, ".NaN", 0, 4) ||
                    content.regionMatches(0, ".NAN", 0, 4)
            ) -> {
            ScalarKind.NAN
        }

        else -> {
            null
        }
    }
}

private fun isRadixDigits(
    content: String,
    from: Int,
    radix: Int,
): Boolean {
    for (index in from until content.length) {
        val digit =
            when (val character = content[index]) {
                in '0'..'9' -> character - '0'
                in 'a'..'f' -> character - 'a' + 10
                in 'A'..'F' -> character - 'A' + 10
                else -> return false
            }

        if (digit >= radix) return false
    }

    return true
}
