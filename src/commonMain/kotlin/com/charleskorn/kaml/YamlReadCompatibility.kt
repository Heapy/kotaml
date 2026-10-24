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

/**
 * Controls whether reading accepts legacy scalar spellings. Encoding is unaffected.
 *
 * Numeric conversions always try the current rules first. [Strict] behaves like the corresponding
 * parameterless [YamlScalar] conversion. [LegacyV0_110] retries failures using legacy rules and reports
 * successful fallbacks to its optional callback. If both attempts fail, the original
 * [YamlScalarFormatException] is thrown.
 */
public sealed class YamlReadCompatibility {
    /** Uses the current scalar-reading rules without accepting or reporting legacy-only forms. */
    public data object Strict : YamlReadCompatibility()

    /**
     * Temporary migration support for legacy scalar syntax. Migrate reported values and return to
     * [Strict]; this mode will be removed in a future release.
     *
     * Accepts legacy signed radix integers, non-ASCII integer digits, floating point suffixes,
     * hexadecimal floats, radix integers as floats, and Infinity/NaN spellings on every platform.
     * Plain `Null` and `NULL` remain strings in values and keys. Empty plain keys remain empty strings;
     * empty values still resolve to null.
     *
     * [onUse] runs synchronously when compatibility is used: during parsing for null resolution,
     * and during typed conversion for numbers. Events may precede a later document failure;
     * callback exceptions propagate. The library does not retain events.
     */
    @Suppress("ktlint:standard:class-naming")
    public class LegacyV0_110(
        internal val onUse: ((YamlReadCompatibilityIssue) -> Unit)? = null,
    ) : YamlReadCompatibility()
}

/** A scalar accepted using the temporary [YamlReadCompatibility.LegacyV0_110] rules. */
public data class YamlReadCompatibilityIssue(
    val reason: YamlReadCompatibilityReason,
    val path: YamlPath,
    val originalValue: String,
    /** Kotlin numeric type name, or null for resolution performed before typed decoding. */
    val targetType: String?,
    /** YAML scalar text that can be used with strict reading while preserving the interpreted value. */
    val replacement: String,
) {
    public val location: Location
        get() = path.endLocation
}

/**
 * Identifies the legacy rule reported by [YamlReadCompatibilityIssue].
 * Numeric reasons arise during typed conversion; null-value and null-key reasons arise during parsing.
 */
public enum class YamlReadCompatibilityReason {
    LegacyInteger,
    LegacyFloatingPoint,
    LegacyNullValue,
    LegacyNullKey,
}

internal fun YamlReadCompatibility.LegacyV0_110.report(
    reason: YamlReadCompatibilityReason,
    path: YamlPath,
    originalValue: String,
    targetType: String?,
    replacement: String,
) {
    onUse?.invoke(YamlReadCompatibilityIssue(reason, path, originalValue, targetType, replacement))
}
