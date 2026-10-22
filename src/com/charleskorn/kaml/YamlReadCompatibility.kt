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

/** Controls the temporary migration support for reading scalars. Encoding is unaffected. */
public sealed class YamlReadCompatibility {
    public data object Strict : YamlReadCompatibility()

    /**
     * Temporary compatibility mode for a quick upgrade to 0.111.0. This mode will be removed in a
     * future release: migrate the reported input values and switch back to [Strict] as soon as possible.
     *
     * Accepts legacy signed radix integers, non-ASCII integer digits, floating point suffixes,
     * hexadecimal floats, radix integers as floats, and Infinity/NaN spellings on every platform.
     * Plain `Null` and `NULL` remain strings, both as values and as map keys.
     * Empty plain mapping keys remain empty strings; empty values still resolve to null.
     *
     * [onUse] is called synchronously for each use of compatibility, including null resolution while
     * parsing a [YamlNode]. Numeric conversions are reported only when requested by a numeric
     * deserializer or a [YamlScalar] conversion. Events can precede a later failure in the document;
     * exceptions from the callback propagate to the caller. No events are retained by the library.
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
