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
 * Transforms serialized object property names into the keys used in YAML mappings.
 *
 * The strategy receives names from the serialization descriptor, including explicit `@SerialName`
 * overrides. The same transformation is applied when writing properties and matching keys while
 * reading; it does not transform arbitrary map keys or enum values.
 *
 * Implementations should return a stable result for each input and preserve distinct names within
 * an object, so that properties can be decoded unambiguously. Configure it through [YamlConfiguration].
 */
public fun interface YamlNamingStrategy {
    public fun serialNameForYaml(serialName: String): String

    public companion object Builtins {
        /**
         * Converts property names to snake_case, treating acronyms as words: `HTTPServer` becomes `http_server`.
         */
        public val SnakeCase: YamlNamingStrategy =
            object : YamlNamingStrategy {
                override fun serialNameForYaml(serialName: String): String = serialName.toDelimitedCase('_')

                override fun toString(): String = "com.charleskorn.kaml.YamlNamingStrategy.SnakeCase"
            }

        /**
         * Converts property names to kebab-case, treating acronyms as words: `HTTPServer` becomes `http-server`.
         */
        public val KebabCase: YamlNamingStrategy =
            object : YamlNamingStrategy {
                override fun serialNameForYaml(serialName: String): String = serialName.toDelimitedCase('-')

                override fun toString(): String = "com.charleskorn.kaml.YamlNamingStrategy.KebabCase"
            }

        /**
         * Capitalizes words separated by characters outside `A-Z`, `a-z`, and `0-9`.
         * For example, `http_server` becomes `HttpServer`; existing capitalization within words is retained.
         */
        public val PascalCase: YamlNamingStrategy =
            object : YamlNamingStrategy {
                override fun serialNameForYaml(serialName: String): String =
                    serialName
                        .split(Regex("[^a-zA-Z0-9]+"))
                        .joinToString("") { it.replaceFirstChar(Char::titlecaseChar) }

                override fun toString(): String = "com.charleskorn.kaml.YamlNamingStrategy.PascalCase"
            }

        /** Applies [PascalCase], then lowercases the first letter: `http_server` becomes `httpServer`. */
        public val CamelCase: YamlNamingStrategy =
            object : YamlNamingStrategy {
                override fun serialNameForYaml(serialName: String): String =
                    PascalCase
                        .serialNameForYaml(serialName)
                        .replaceFirstChar(Char::lowercaseChar)

                override fun toString(): String = "com.charleskorn.kaml.YamlNamingStrategy.CamelCase"
            }

        private fun String.toDelimitedCase(delimiter: Char): String =
            buildString(length * 2) {
                var bufferedChar: Char? = null
                var previousCaseCharsCount = 0

                for (character in this@toDelimitedCase) {
                    if (character.isUpperCase()) {
                        if (previousCaseCharsCount == 0 && isNotEmpty() && last() != delimiter) {
                            append(delimiter)
                        }

                        bufferedChar?.let(::append)

                        previousCaseCharsCount++
                        bufferedChar = character.lowercaseChar()
                    } else {
                        if (bufferedChar != null) {
                            if (previousCaseCharsCount > 1 && character.isLetter()) {
                                append(delimiter)
                            }
                            append(bufferedChar)
                            previousCaseCharsCount = 0
                            bufferedChar = null
                        }
                        append(character)
                    }
                }

                if (bufferedChar != null) {
                    append(bufferedChar)
                }
            }
    }
}
